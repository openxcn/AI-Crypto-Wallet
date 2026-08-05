#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json, os, re, xml.etree.ElementTree as ET
from pathlib import Path
from collections import defaultdict

PROJECT = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
VALUES = {
    "zh": PROJECT / "app/src/main/res/values/strings.xml",
    "en": PROJECT / "app/src/main/res/values-en/strings.xml",
    "ja": PROJECT / "app/src/main/res/values-ja/strings.xml",
    "de": PROJECT / "app/src/main/res/values-de/strings.xml",
}
JAVA_DIR = PROJECT / "app/src/main/java/com/aicryptowallet/app"
LAYOUT_DIR = PROJECT / "app/src/main/res/layout"

CH = re.compile(r"[\u4e00-\u9fff]")

def load_translations(lang):
    p = PROJECT / f"translations_{lang}.json"
    if p.exists():
        return json.loads(p.read_text(encoding="utf-8"))
    return {}

trans = {lang: load_translations(lang) for lang in ["en","ja","de"]}

# 1. strings.xml Chinese values
zh_values = set()
invalid_names = []
root = ET.parse(VALUES["zh"]).getroot()
for s in root.findall("string"):
    n = s.get("name"); v = "".join(s.itertext())
    if CH.search(v):
        zh_values.add(v)
    if not re.match(r"^[a-z][a-z0-9_]*$", n):
        invalid_names.append((n, v))

# 2. Java hardcoded Chinese user-facing strings
java_values = set()
java_occurrences = []
USER_PATTERNS = [
    (r"\.setText\s*\(", 0), (r"\.setTitle\s*\(", 0), (r"\.setMessage\s*\(", 0),
    (r"\.setHint\s*\(", 0), (r"\.setError\s*\(", 0),
    (r"\.setPositiveButton\s*\(", 0), (r"\.setNegativeButton\s*\(", 0), (r"\.setNeutralButton\s*\(", 0),
    (r"Toast\.makeText\s*\(", 1), (r"\.setContentTitle\s*\(", 0), (r"\.setContentText\s*\(", 0),
    (r"\.setSubText\s*\(", 0), (r"\.setTicker\s*\(", 0), (r"\.setDialogTitle\s*\(", 0),
    (r"\.setSummary\s*\(", 0), (r"\.setAction\s*\(", 0), (r"\.setLabel\s*\(", 0),
    (r"\.setPlaceholderText\s*\(", 0), (r"\.setPrompt\s*\(", 0), (r"Snackbar\.make\s*\(", 1),
]
SKIP = [r"^\s*//", r"^\s*\*", r"\bLog\.", r"\bLogger\.", r"System\.out\."]
STRING_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')

def remove_block_comments(text):
    return re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)

def extract_args(line, start):
    i = line.find("(", start)
    if i == -1: return []
    depth=0; in_str=False; esc=False; cur=[]; args=[]
    i+=1
    while i < len(line):
        c=line[i]
        if esc: esc=False; cur.append(c)
        elif c=="\\": esc=True; cur.append(c)
        elif c=='"': in_str=not in_str; cur.append(c)
        elif not in_str:
            if c=="(": depth+=1; cur.append(c)
            elif c==")":
                if depth==0:
                    if cur: args.append("".join(cur).strip())
                    return args
                depth-=1; cur.append(c)
            elif c=="," and depth==0:
                if cur: args.append("".join(cur).strip())
                cur=[]
            else: cur.append(c)
        else: cur.append(c)
        i+=1
    return args

def tokenize(expr):
    tokens=[]; i=0
    while i<len(expr):
        if expr[i] in " \t\n+": i+=1; continue
        if expr[i]=='"':
            j=i+1
            while j<len(expr):
                if expr[j]=="\\" and j+1<len(expr): j+=2
                elif expr[j]=='"': break
                else: j+=1
            tokens.append(("str", expr[i+1:j]))
            i=j+1
        else:
            j=i; depth=0; ins=False; esc=False
            while j<len(expr):
                c=expr[j]
                if esc: esc=False
                elif c=="\\": esc=True
                elif c=='"': ins=not ins
                elif not ins:
                    if c=="(": depth+=1
                    elif c==")": depth-=1
                    elif c=="+" and depth==0: break
                j+=1
            tok=expr[i:j].strip()
            if tok: tokens.append(("var", tok))
            i=j
    return tokens

for f in sorted(JAVA_DIR.rglob("*.java")):
    raw = f.read_text(encoding="utf-8")
    no_block = remove_block_comments(raw)
    for idx, raw_line in enumerate(no_block.splitlines(), 1):
        if not CH.search(raw_line): continue
        line = raw_line.strip()
        if any(re.search(p, line) for p in SKIP): continue
        for pat, arg_idx in USER_PATTERNS:
            for m in re.finditer(pat, raw_line):
                args = extract_args(raw_line, m.start())
                if arg_idx >= len(args): continue
                expr = args[arg_idx]
                toks = tokenize(expr)
                if not any(k=="str" and CH.search(v) for k,v in toks): continue
                # Build format string
                parts=[]; a=[]; vi=1
                for k,v in toks:
                    if k=="str": parts.append(v)
                    else: parts.append(f"%{vi}$s"); a.append(v); vi+=1
                fmt = "".join(parts)
                java_occurrences.append({"file":str(f),"line":idx,"method":m.group(0),"expr":expr,"fmt":fmt,"args":a})
                if CH.search(fmt): java_values.add(fmt)

# 3. XML hardcoded Chinese
xml_values = set()
xml_occurrences = []
XML_ATTRS = [("android:text","text"),("android:hint","hint"),("android:title","title"),("app:title","title"),
             ("android:contentDescription","desc"),("android:summary","summary"),("android:subtitle","subtitle")]
for f in sorted(LAYOUT_DIR.rglob("*.xml")):
    for idx, raw_line in enumerate(f.read_text(encoding="utf-8").splitlines(),1):
        if not CH.search(raw_line): continue
        for attr,_ in XML_ATTRS:
            for m in re.finditer(rf'({re.escape(attr)}\s*=\s*")([^"]*)"', raw_line):
                v=m.group(2)
                if v.startswith("@") or v.startswith("?"): continue
                if CH.search(v):
                    xml_occurrences.append({"file":str(f),"line":idx,"attr":attr,"value":v})
                    xml_values.add(v)

all_values = sorted(zh_values | java_values | xml_values)
print(f"zh strings.xml Chinese values: {len(zh_values)}")
print(f"Java hardcoded Chinese values: {len(java_values)} ({len(java_occurrences)} occurrences)")
print(f"XML hardcoded Chinese values: {len(xml_values)} ({len(xml_occurrences)} occurrences)")
print(f"Total unique Chinese values: {len(all_values)}")
print(f"Invalid resource names: {len(invalid_names)}")

missing = defaultdict(list)
for v in all_values:
    for lang in ["en","ja","de"]:
        if v not in trans[lang] or trans[lang][v] == v:
            missing[lang].append(v)
for lang in ["en","ja","de"]:
    print(f"Missing {lang} translations: {len(missing[lang])}")

# Write report
with open("coverage_report.txt","w",encoding="utf-8") as out:
    out.write("Invalid names:\n")
    for n,v in invalid_names:
        out.write(f"  {n} = {v}\n")
    out.write("\nJava occurrences:\n")
    for occ in java_occurrences:
        out.write(f"{occ['file']}:{occ['line']} [{occ['method']}] {occ['fmt']}\n")
    out.write("\nXML occurrences:\n")
    for occ in xml_occurrences:
        out.write(f"{occ['file']}:{occ['line']} [{occ['attr']}] {occ['value']}\n")
    out.write("\nMissing translations:\n")
    for lang in ["en","ja","de"]:
        out.write(f"\n{lang}:\n")
        for v in missing[lang]:
            out.write(f"  {v}\n")
print("Report written to coverage_report.txt")
