#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import os, re, xml.etree.ElementTree as ET

ROOT = r"c:\Users\Administrator\Documents\trae work\ai_crypto_wallet_android"
JAVA_DIR = os.path.join(ROOT, "app", "src", "main", "java", "com", "aicryptowallet", "app")
LAYOUT_DIR = os.path.join(ROOT, "app", "src", "main", "res", "layout")
VALUES = {
    "zh": os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml"),
    "en": os.path.join(ROOT, "app", "src", "main", "res", "values-en", "strings.xml"),
    "ja": os.path.join(ROOT, "app", "src", "main", "res", "values-ja", "strings.xml"),
    "de": os.path.join(ROOT, "app", "src", "main", "res", "values-de", "strings.xml"),
}

CH_RE = re.compile(r"[\u4e00-\u9fff]")

def has_ch(s):
    return bool(CH_RE.search(s))

def parse_strings(path):
    data = {}
    if not os.path.exists(path):
        return data
    tree = ET.parse(path)
    root = tree.getroot()
    for s in root.findall("string"):
        name = s.get("name")
        if name:
            data[name] = "".join(s.itertext())
    return data

strings = {loc: parse_strings(p) for loc, p in VALUES.items()}
zh_keys = set(strings["zh"].keys())

def report_untranslated():
    print("=== UNTRANSLATED CHINESE VALUES IN NON-DEFAULT LOCALES ===")
    for loc in ["en", "ja", "de"]:
        print(f"\n--- {loc} ---")
        count = 0
        for name, val in strings[loc].items():
            if has_ch(val):
                count += 1
                esc = val[:120].replace("\n", "\\n")
                print(f"  {name}: {esc}")
        print(f"  TOTAL: {count}")

LAYOUT_TEXT_ATTRS = re.compile(r"\b(android:(?:text|hint|title|contentDescription|summary|dialogTitle|dialogMessage|entries|entryValues|positiveButtonText|negativeButtonText|neutralButtonText))\s*=\s*\"([^\"]*)\"")
STRING_REF_RE = re.compile(r"@string/([A-Za-z0-9_]+)")

def report_layout():
    print("\n=== HARDCODED CHINESE IN XML LAYOUTS ===")
    hard_files = {}
    for root_dir, _, files in os.walk(LAYOUT_DIR):
        for f in files:
            if not f.endswith(".xml"):
                continue
            path = os.path.join(root_dir, f)
            with open(path, "r", encoding="utf-8") as fp:
                for i, line in enumerate(fp, 1):
                    # hardcoded Chinese in known UI attrs
                    for m in LAYOUT_TEXT_ATTRS.finditer(line):
                        val = m.group(2)
                        if has_ch(val) and "@string/" not in val:
                            hard_files.setdefault(path, []).append((i, m.group(1), val))
                    # missing @string refs
                    for m in STRING_REF_RE.finditer(line):
                        name = m.group(1)
                        if name not in zh_keys:
                            hard_files.setdefault(path + " [MISSING]", []).append((i, name, ""))
    for path, items in sorted(hard_files.items()):
        print(f"\n{path}")
        for line, attr, val in items:
            if val:
                esc = val[:120].replace("\n", "\\n")
                print(f"  L{line} [{attr}] {esc}")
            else:
                print(f"  L{line} missing @string/{attr}")

JAVA_STRING_RE = re.compile(r'"([^"\\]*(?:\\.[^"\\]*)*)"')
COMMENT_LINE_RE = re.compile(r"^\s*//")
LOG_RE = re.compile(r"\b(Log|Logger|System\.out\.print| Timber)\b")
USER_FACING_RE = re.compile(r"\b(setText|setTitle|setMessage|setHint|setPositiveButton|setNegativeButton|setNeutralButton|setButton|Toast|Snackbar|AlertDialog|showToast|setSummary|setDialogTitle|setDialogMessage|setContentDescription|new Builder)\b")

def remove_block_comments(text):
    return re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)

def report_java():
    print("\n=== HARDCODED CHINESE IN JAVA/KOTLIN SOURCE ===")
    total = 0
    for root_dir, _, files in os.walk(JAVA_DIR):
        for f in files:
            if not (f.endswith(".java") or f.endswith(".kt")):
                continue
            path = os.path.join(root_dir, f)
            with open(path, "r", encoding="utf-8") as fp:
                try:
                    raw = fp.read()
                except Exception as e:
                    continue
            no_block = remove_block_comments(raw)
            for i, raw_line in enumerate(no_block.splitlines(), 1):
                line = raw_line.strip()
                if not line or COMMENT_LINE_RE.match(line):
                    continue
                if LOG_RE.search(line):
                    continue
                if not USER_FACING_RE.search(line):
                    continue
                found = []
                for m in JAVA_STRING_RE.finditer(line):
                    s = m.group(1)
                    if has_ch(s):
                        found.append(s)
                if found:
                    total += len(found)
                    print(f"\n{path}:{i}")
                    for s in found:
                        esc = s[:200].replace("\n", "\\n")
                        print(f"  \"{esc}\"")
    print(f"\n  TOTAL CHINESE STRINGS: {total}")

if __name__ == "__main__":
    report_untranslated()
    report_layout()
    report_java()
