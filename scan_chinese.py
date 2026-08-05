#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import os
import re
import sys
from pathlib import Path

print("START SCAN", flush=True)
PROJECT = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
JAVA_DIR = PROJECT / "app/src/main/java/com/aicryptowallet/app"
LAYOUT_DIR = PROJECT / "app/src/main/res/layout"
CHINESE_RE = re.compile(r'[\u4e00-\u9fff]')

def scan(path, ftype):
    results = []
    try:
        text = path.read_text(encoding="utf-8")
    except Exception as e:
        return [(path, 0, f"ERROR: {e}")]
    for i, line in enumerate(text.splitlines(), 1):
        if CHINESE_RE.search(line):
            results.append((path, i, line))
    return results

java_files = sorted(JAVA_DIR.rglob("*.java"))
xml_files = sorted(LAYOUT_DIR.rglob("*.xml"))
print(f"Java: {len(java_files)} XML: {len(xml_files)}", flush=True)
all_results = []
for f in java_files:
    all_results.extend(scan(f, "java"))
for f in xml_files:
    all_results.extend(scan(f, "xml"))

by_file = {}
for path, line_no, line in all_results:
    by_file.setdefault(str(path), []).append((line_no, line))

out_path = PROJECT / "chinese_scan_report.txt"
print(f"Writing report to {out_path}", flush=True)
print(f"Total results: {len(all_results)} files: {len(by_file)}", flush=True)
with out_path.open("w", encoding="utf-8") as out:
    out.write(f"Total candidate lines: {len(all_results)} in {len(by_file)} files.\n\n")
    for fp in sorted(by_file.keys()):
        rel = os.path.relpath(fp, str(PROJECT))
        out.write(f"===== {rel} =====\n")
        for line_no, line in by_file[fp]:
            out.write(f"{line_no}: {line}\n")
        out.write("\n")
print("DONE", flush=True)
sys.stdout.flush()
