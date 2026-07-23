#!/usr/bin/env python3
"""Own the cross-step host flock and release it with PID-reuse protection."""

from __future__ import annotations

import argparse
import fcntl
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time


def start_ticks(pid: int) -> int:
    tail = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].strip().split()
    return int(tail[19])


def proc_parent(pid: int) -> int:
    tail = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].strip().split()
    return int(tail[1])


def proc_cmdline(pid: int) -> str:
    return Path(f"/proc/{pid}/cmdline").read_bytes().replace(b"\0", b" ").decode(
        "utf-8", "replace"
    )


def find_runner_owner(pid: int | None = None) -> tuple[int, int]:
    current = os.getpid() if pid is None else pid
    seen = set()
    while current > 1 and current not in seen:
        seen.add(current)
        if "Runner.Worker" in proc_cmdline(current):
            return current, start_ticks(current)
        current = proc_parent(current)
    raise RuntimeError("host lease must be started by a GitHub Runner.Worker descendant")


def owner_alive(pid: int, expected_start_ticks: int) -> bool:
    try:
        return start_ticks(pid) == expected_start_ticks and "Runner.Worker" in proc_cmdline(pid)
    except (FileNotFoundError, ProcessLookupError, PermissionError):
        return False


def write_state(path: Path, lock: str, owner_pid: int, owner_start_ticks: int) -> None:
    value = {
        "schema_version": 1,
        "pid": os.getpid(),
        "pgid": os.getpgrp(),
        "start_ticks": start_ticks(os.getpid()),
        "lock": lock,
        "owner_pid": owner_pid,
        "owner_start_ticks": owner_start_ticks,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, raw_tmp = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    tmp = Path(raw_tmp)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(tmp, path)
    finally:
        try:
            os.close(fd)
        except OSError:
            pass
        tmp.unlink(missing_ok=True)


def hold(args) -> int:
    fd = os.open(args.lock, os.O_WRONLY | os.O_APPEND)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        print(f"host lease is busy: {args.lock}", file=sys.stderr)
        return 75
    stop = False

    def request_stop(_signum, _frame):
        nonlocal stop
        stop = True

    signal.signal(signal.SIGTERM, request_stop)
    signal.signal(signal.SIGINT, request_stop)
    write_state(Path(args.state), args.lock, args.owner_pid, args.owner_start_ticks)
    deadline = time.monotonic() + args.ttl
    while (
        not stop
        and time.monotonic() < deadline
        and owner_alive(args.owner_pid, args.owner_start_ticks)
    ):
        time.sleep(1)
    Path(args.state).unlink(missing_ok=True)
    os.close(fd)
    return 0


def start(args) -> int:
    state = Path(args.state)
    state.unlink(missing_ok=True)
    try:
        owner_pid, owner_start = find_runner_owner()
    except (OSError, RuntimeError) as exc:
        print(f"cannot bind host lease to runner owner: {exc}", file=sys.stderr)
        return 2
    command = [
        sys.executable, str(Path(__file__).resolve()), "hold",
        "--lock", args.lock, "--state", args.state, "--ttl", str(args.ttl),
        "--owner-pid", str(owner_pid), "--owner-start-ticks", str(owner_start),
    ]
    # Truly detach across GitHub steps: inheriting the step's stdout/stderr pipes
    # can keep the run command open until the 25-hour holder exits even though
    # this launcher returned.  State-file readiness is the only IPC we need.
    proc = subprocess.Popen(
        command,
        start_new_session=True,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        close_fds=True,
    )
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if state.exists():
            value = json.loads(state.read_text())
            if value.get("pid") == proc.pid and value.get("pgid") == proc.pid:
                print(f"host lease acquired: {args.lock} (holder {proc.pid})")
                return 0
        rc = proc.poll()
        if rc is not None:
            return rc
        time.sleep(0.1)
    proc.terminate()
    proc.wait(timeout=5)
    print("host lease holder did not become ready", file=sys.stderr)
    return 1


def load_state(path: Path) -> dict:
    def pairs(items):
        out = {}
        for key, value in items:
            if key in out:
                raise ValueError(f"duplicate key {key!r}")
            out[key] = value
        return out
    raw = path.read_bytes()
    value = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs)
    if (
        set(value) != {
            "schema_version", "pid", "pgid", "start_ticks", "lock",
            "owner_pid", "owner_start_ticks",
        }
        or type(value["schema_version"]) is not int
        or value["schema_version"] != 1
    ):
        raise ValueError("invalid lease-state schema")
    for key in ("pid", "pgid", "start_ticks", "owner_pid", "owner_start_ticks"):
        if type(value[key]) is not int or value[key] < 1:
            raise ValueError(f"invalid lease-state {key}")
    if not isinstance(value["lock"], str) or not value["lock"].startswith("/"):
        raise ValueError("invalid lease-state lock path")
    canonical = json.dumps(value, sort_keys=True, separators=(",", ":")).encode() + b"\n"
    if raw != canonical:
        raise ValueError("lease-state is not canonical JSON")
    return value


def release(args) -> int:
    path = Path(args.state)
    if not path.exists():
        print("no local host lease state")
        return 0
    try:
        value = load_state(path)
        pid = value["pid"]
        cmdline = Path(f"/proc/{pid}/cmdline").read_bytes().replace(b"\0", b" ").decode("utf-8", "replace")
        valid = (
            value["pgid"] == pid
            and os.getpgid(pid) == pid
            and start_ticks(pid) == value["start_ticks"]
            and "host_lease.py hold" in cmdline
            and value["lock"] in cmdline
        )
    except (FileNotFoundError, ProcessLookupError):
        valid = False
    except Exception as exc:
        print(f"invalid host lease state: {exc}", file=sys.stderr)
        return 2
    if not valid:
        path.unlink(missing_ok=True)
        print("stale host lease state removed; no process signalled")
        return 0
    os.killpg(pid, signal.SIGTERM)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            break
        time.sleep(0.1)
    else:
        os.killpg(pid, signal.SIGKILL)
    path.unlink(missing_ok=True)
    print("host lease released")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    for name, func in (("start", start), ("hold", hold)):
        p = sub.add_parser(name)
        p.add_argument("--lock", required=True)
        p.add_argument("--state", required=True)
        p.add_argument("--ttl", type=int, default=90000)
        if name == "hold":
            p.add_argument("--owner-pid", type=int, required=True)
            p.add_argument("--owner-start-ticks", type=int, required=True)
        p.set_defaults(func=func)
    p = sub.add_parser("release")
    p.add_argument("--state", required=True)
    p.set_defaults(func=release)
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
