#!/usr/bin/env python3
"""Remove only a Linker-owned NER process group while holding the host lease.

The shared JSON is not a PID file: it binds the session leader to its Linux
/proc start time, uid, process group and full command line.  A missing or stale
identity is never authority to signal an unrelated process.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import time


IDENTITY_KEYS = {"pid", "pgid", "start_ticks", "uid", "cmdline"}


def strict_json(path: Path) -> dict:
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError(f"duplicate key {key!r}")
            result[key] = value
        return result

    raw = path.read_bytes()
    value = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs)
    if set(value) != {"schema_version", "kind", "identity"}:
        raise ValueError("unexpected process-scope keys")
    if type(value["schema_version"]) is not int or value["schema_version"] != 1 or value["kind"] != "ner-gunicorn":
        raise ValueError("unexpected process-scope schema or kind")
    identity = value["identity"]
    if not isinstance(identity, dict) or set(identity) != IDENTITY_KEYS:
        raise ValueError("unexpected process identity keys")
    for key in ("pid", "pgid", "start_ticks", "uid"):
        if type(identity[key]) is not int or identity[key] < 0:
            raise ValueError(f"{key} must be a non-negative integer")
    if identity["pid"] < 1 or identity["pgid"] != identity["pid"]:
        raise ValueError("scope leader must have pid == pgid > 0")
    if not isinstance(identity["cmdline"], str) or "app:create_app()" not in identity["cmdline"]:
        raise ValueError("scope command is not the Linker NER server")
    canonical = json.dumps(value, sort_keys=True, separators=(",", ":")).encode() + b"\n"
    if raw != canonical:
        raise ValueError("process-scope state is not canonical JSON")
    return value


def proc_identity(pid: int) -> dict:
    stat = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8")
    tail = stat.rsplit(")", 1)[1].strip().split()
    return {
        "pid": pid,
        "pgid": os.getpgid(pid),
        "start_ticks": int(tail[19]),
        "uid": Path(f"/proc/{pid}").stat().st_uid,
        "cmdline": Path(f"/proc/{pid}/cmdline")
        .read_bytes()
        .replace(b"\0", b" ")
        .decode("utf-8", "replace")
        .strip(),
    }


def port_is_open(port: int) -> bool:
    with socket.socket() as sock:
        sock.settimeout(0.3)
        return sock.connect_ex(("127.0.0.1", port)) == 0


def group_has_live_members(pgid: int) -> bool:
    proc_root = Path("/proc")
    if not proc_root.is_dir():
        return False
    for entry in proc_root.iterdir():
        if not entry.name.isdigit():
            continue
        try:
            tail = (entry / "stat").read_text(encoding="utf-8").rsplit(")", 1)[1].strip().split()
            if int(tail[2]) == pgid and tail[0] != "Z":
                return True
        except (FileNotFoundError, PermissionError, ProcessLookupError, ValueError):
            continue
    return False


def remove_state(path: Path) -> None:
    try:
        path.unlink(missing_ok=True)
    except PermissionError:
        subprocess.run(["sudo", "rm", "-f", "--", str(path)], check=True)


def signal_group(pgid: int, sig: signal.Signals, owner_uid: int) -> None:
    if owner_uid == os.geteuid():
        os.killpg(pgid, sig)
    else:
        subprocess.run(
            ["sudo", "/bin/kill", f"-{sig.name}", "--", f"-{pgid}"],
            check=True,
        )


def reap(args) -> int:
    state = Path(args.state)
    if not state.exists():
        if port_is_open(args.port):
            print(
                f"refusing build: :{args.port} is live without Linker ownership state",
                file=sys.stderr,
            )
            return 2
        print("no persisted Linker NER scope")
        return 0

    try:
        value = strict_json(state)
    except Exception as exc:
        print(f"invalid Linker NER ownership state: {exc}", file=sys.stderr)
        return 2
    identity = value["identity"]
    try:
        matches = proc_identity(identity["pid"]) == identity
    except (FileNotFoundError, ProcessLookupError):
        matches = False

    if not matches:
        if group_has_live_members(identity["pgid"]):
            print(
                "Linker NER leader identity is stale but descendants remain; preserving scope and refusing build",
                file=sys.stderr,
            )
            return 2
        if port_is_open(args.port):
            print(
                "Linker NER state is stale but :5051 is still live; refusing an unsafe kill",
                file=sys.stderr,
            )
            return 2
        remove_state(state)
        print("removed stale Linker NER scope; no process was signalled")
        return 0

    pgid = identity["pgid"]
    signal_group(pgid, signal.SIGTERM, identity["uid"])
    deadline = time.monotonic() + args.grace
    while time.monotonic() < deadline and group_has_live_members(pgid):
        time.sleep(0.2)
    if group_has_live_members(pgid):
        signal_group(pgid, signal.SIGKILL, identity["uid"])
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and group_has_live_members(pgid):
            time.sleep(0.1)
    if group_has_live_members(pgid) or port_is_open(args.port):
        print("Linker NER descendants survived teardown", file=sys.stderr)
        return 1
    remove_state(state)
    print(f"reaped Linker-owned NER process group {pgid}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--state", default="/run/lock/otzaria/linker-ner.scope.json")
    parser.add_argument("--port", type=int, default=5051)
    parser.add_argument("--grace", type=float, default=20)
    return reap(parser.parse_args())


if __name__ == "__main__":
    raise SystemExit(main())
