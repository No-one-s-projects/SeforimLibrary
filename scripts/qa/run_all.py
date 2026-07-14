#!/usr/bin/env python3
"""מריץ את כל סקריפטי ה-QA הבר-סקריפטיים (סעיף 10) ומסכם pass/fail."""
import argparse
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# (script, needs_sefaria_dir, accepts_expect_snapshot, needs_metrics)
SCRIPTS = [("check3_elucidation.py", False, False, False),
           ("check5_import_metrics.py", False, False, True),
           ("check7_provenance.py", False, True, False),
           ("check8_integrity.py", False, False, False),
           ("check1_dependence_count.py", True, True, False),
           ("check2_book_base_text.py", True, True, False),
           ("check6_metadata_rowbyrow.py", True, False, False)]


def main():
    ap = argparse.ArgumentParser(description="run_all: כל בדיקות ה-QA")
    ap.add_argument("--db", required=True)
    ap.add_argument("--sefaria-dir",
                    help="נדרש לבדיקות 1/2/6; בהיעדרו הן מדולגות")
    ap.add_argument("--metrics",
                    help="נתיב <db>.link-import-metrics.json לבדיקה 5; "
                         "בהיעדרו בדיקה 5 מדולגת (אין ניחוש נתיב ברירת-מחדל)")
    ap.add_argument("--expect-snapshot", action="store_true")
    args = ap.parse_args()

    results = []
    for name, needs_sefaria, accepts_snapshot, needs_metrics in SCRIPTS:
        if needs_sefaria and not args.sefaria_dir:
            results.append((name, "SKIP", "אין --sefaria-dir"))
            continue
        if needs_metrics and not args.metrics:
            results.append((name, "SKIP", "אין --metrics"))
            continue
        cmd = [sys.executable, os.path.join(HERE, name), "--db", args.db]
        if needs_sefaria:
            cmd += ["--sefaria-dir", args.sefaria_dir]
        if needs_metrics:
            cmd += ["--metrics", args.metrics]
        if args.expect_snapshot and accepts_snapshot:
            cmd.append("--expect-snapshot")
        print(f"\n===== {name} =====")
        rc = subprocess.run(cmd).returncode
        results.append((name, "PASS" if rc == 0 else "FAIL", f"rc={rc}"))

    print("\n===== סיכום =====")
    failed = 0
    for name, status, note in results:
        print(f"  {status:4}  {name}  ({note})")
        if status == "FAIL":
            failed += 1
    if failed:
        print(f"\n{failed} בדיקות נכשלו")
        sys.exit(1)
    print("\nכל הבדיקות עברו")
    sys.exit(0)


if __name__ == "__main__":
    main()
