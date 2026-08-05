#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import os, re, xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(r"c:\Users\Administrator\Documents\trae work\ai_crypto_wallet_android")
JAVA_DIR = ROOT / "app" / "src" / "main" / "java" / "com" / "aicryptowallet" / "app"
RES_DIR = ROOT / "app" / "src" / "main" / "res"
STRINGS = ROOT / "app" / "src" / "main" / "res" / "values" / "strings.xml"

def parse_strings(path):
    data = {}
    if not path.exists():
        return data
    root = ET.parse(path).getroot()
    for s in root.findall("string"):
        name = s.get("name")
        if name:
            data[name] = "".join(s.itertext())
    return data

zh_strings = parse_strings(STRINGS)
valid_names = set(zh_strings.keys())

def strip_non_ascii_suffix(name):
    """Remove trailing non-ASCII chars and underscore from a resource name."""
    # Strip trailing CJK and any preceding underscore
    s = name
    while s and (ord(s[-1]) > 127 or s[-1] == "_"):
        s = s[:-1]
    # also strip trailing underscores
    s = s.rstrip("_")
    return s

def find_valid_base(name):
    """Try to find a valid name by stripping suffixes."""
    if name in valid_names:
        return name
    # strip CJK suffix
    base = strip_non_ascii_suffix(name)
    if base and base in valid_names:
        return base
    # strip numeric suffixes like _2, _3
    base2 = re.sub(r"_\d+$", "", name)
    if base2 and base2 != name and base2 in valid_names:
        return base2
    # combine: strip CJK then numeric
    base3 = re.sub(r"_\d+$", "", base)
    if base3 and base3 != base and base3 in valid_names:
        return base3
    return None

# Build replacement map
replacement_map = {}
for name in list(valid_names):
    # also map invalid versions we might encounter
    pass

print("=== FIXING JAVA REFERENCES ===")
java_fixed = 0
java_unfixed = []
for f in sorted(JAVA_DIR.rglob("*.java")):
    content = f.read_text(encoding="utf-8")
    new_content = content
    for m in re.finditer(r"R\.string\.([A-Za-z0-9_\u4e00-\u9fff]+)", content):
        name = m.group(1)
        if name in valid_names:
            continue
        base = find_valid_base(name)
        if base:
            replacement_map[name] = base
            new_content = new_content.replace(f"R.string.{name}", f"R.string.{base}")
            java_fixed += 1
        else:
            java_unfixed.append((str(f), name))
    if new_content != content:
        f.write_text(new_content, encoding="utf-8")

print(f"Fixed {java_fixed} Java references")
print(f"Unfixed Java references: {len(java_unfixed)}")
for path, name in java_unfixed[:20]:
    print(f"  {path}: R.string.{name}")

print("\n=== FIXING XML REFERENCES ===")
xml_fixed = 0
xml_unfixed = []
for f in sorted(RES_DIR.rglob("*.xml")):
    try:
        content = f.read_text(encoding="utf-8")
    except Exception:
        continue
    new_content = content
    for m in re.finditer(r"@string/([A-Za-z0-9_\u4e00-\u9fff]+)", content):
        name = m.group(1)
        if name in valid_names:
            continue
        base = find_valid_base(name)
        if base:
            replacement_map[name] = base
            new_content = new_content.replace(f"@string/{name}", f"@string/{base}")
            xml_fixed += 1
        else:
            xml_unfixed.append((str(f), name))
    if new_content != content:
        f.write_text(new_content, encoding="utf-8")

print(f"Fixed {xml_fixed} XML references")
print(f"Unfixed XML references: {len(xml_unfixed)}")
for path, name in xml_unfixed[:20]:
    print(f"  {path}: @string/{name}")

print("\n=== REPLACEMENTS MADE ===")
for old, new in sorted(replacement_map.items()):
    print(f"  {old} -> {new}")
