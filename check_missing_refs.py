#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import os, re, xml.etree.ElementTree as ET

ROOT = r"c:\Users\Administrator\Documents\trae work\ai_crypto_wallet_android"
JAVA_DIR = os.path.join(ROOT, "app", "src", "main", "java", "com", "aicryptowallet", "app")
RES_DIR = os.path.join(ROOT, "app", "src", "main", "res")
STRINGS = os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml")

def parse_strings(path):
    data = set()
    if not os.path.exists(path):
        return data
    root = ET.parse(path).getroot()
    for s in root.findall("string"):
        name = s.get("name")
        if name:
            data.add(name)
    return data

valid = parse_strings(STRINGS)

print("=== MISSING R.string REFERENCES IN JAVA ===")
java_missing = []
for root_dir, _, files in os.walk(JAVA_DIR):
    for f in files:
        if not (f.endswith(".java") or f.endswith(".kt")):
            continue
        path = os.path.join(root_dir, f)
        with open(path, "r", encoding="utf-8") as fp:
            for i, line in enumerate(fp, 1):
                for m in re.finditer(r"R\.string\.([A-Za-z0-9_\u4e00-\u9fff]+)", line):
                    name = m.group(1)
                    if name not in valid:
                        java_missing.append((path, i, name))
                        print(f"{path}:{i} R.string.{name}")

print(f"\nTotal Java missing: {len(java_missing)}")

print("\n=== MISSING @string REFERENCES IN XML ===")
xml_missing = []
for root_dir, _, files in os.walk(RES_DIR):
    for f in files:
        if not f.endswith(".xml"):
            continue
        path = os.path.join(root_dir, f)
        with open(path, "r", encoding="utf-8") as fp:
            for i, line in enumerate(fp, 1):
                for m in re.finditer(r"@string/([A-Za-z0-9_\u4e00-\u9fff]+)", line):
                    name = m.group(1)
                    if name not in valid:
                        xml_missing.append((path, i, name))
                        print(f"{path}:{i} @string/{name}")

print(f"\nTotal XML missing: {len(xml_missing)}")
