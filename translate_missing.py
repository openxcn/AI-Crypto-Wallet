#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Translate missing Chinese strings using MyMemory and update translation JSON files."""
import json, re, time, sys, requests
from pathlib import Path
from urllib.parse import quote
from collections import defaultdict

PROJECT = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
EMAIL = "user@example.com"
PLACEHOLDER_RE = re.compile(r"(%\d+\$[sdxf])")
CH = re.compile(r"[\u4e00-\u9fff]")

def load_translations(lang):
    p = PROJECT / f"translations_{lang}.json"
    if p.exists():
        return json.loads(p.read_text(encoding="utf-8"))
    return {}

def protect(text):
    placeholders = []
    counter = [0]
    def repl(m):
        counter[0] += 1
        placeholders.append(m.group(1))
        return f"｟{counter[0]}｠"
    return PLACEHOLDER_RE.sub(repl, text), placeholders

def restore(text, placeholders):
    for i, ph in enumerate(placeholders, 1):
        text = re.sub(rf'｟\s*{i}\s*｠', ph, text)
    return text

def translate(text, dest):
    protected, placeholders = protect(text)
    url = f"https://api.mymemory.translated.net/get?q={quote(protected)}&langpair=zh-CN|{dest}&de={EMAIL}"
    try:
        r = requests.get(url, timeout=15)
        data = r.json()
        if data.get("responseStatus") == 200:
            trans = data["responseData"]["translatedText"]
            if trans and trans != protected:
                return restore(trans, placeholders)
    except Exception as e:
        print(f"  API error: {e}", flush=True)
    return None

def get_all_zh_values():
    import xml.etree.ElementTree as ET
    path = PROJECT / "app/src/main/res/values/strings.xml"
    root = ET.parse(path).getroot()
    values = set()
    for s in root.findall("string"):
        v = "".join(s.itertext())
        if CH.search(v):
            values.add(v)
    return values

def main():
    lang = sys.argv[1] if len(sys.argv) > 1 else "en"
    out_file = PROJECT / f"translations_{lang}.json"
    existing = load_translations(lang)
    zh_values = get_all_zh_values()
    # Also include hardcoded Java Chinese strings from coverage report if available
    java_values = set()
    cov = PROJECT / "coverage_report.txt"
    if cov.exists():
        in_java = False
        for line in cov.read_text(encoding="utf-8").splitlines():
            if line.startswith("Java occurrences:"):
                in_java = True
                continue
            if line.startswith("XML occurrences:"):
                break
            if in_java and "[" in line and "]" in line:
                # format: file:line [method] fmt
                parts = line.split("]", 1)
                if len(parts) == 2:
                    fmt = parts[1].strip()
                    if CH.search(fmt):
                        java_values.add(fmt)
    all_values = sorted(zh_values | java_values)
    missing = [v for v in all_values if v not in existing or not existing[v] or existing[v] == v]
    print(f"[{lang}] Missing translations: {len(missing)}", flush=True)
    done = 0
    failed = []
    for idx, v in enumerate(missing, 1):
        trans = translate(v, lang)
        if trans is None or trans == v:
            failed.append(v)
            # keep existing/fallback (Chinese)
            print(f"[{lang} {idx}/{len(missing)}] FAILED: {v[:60]}", flush=True)
        else:
            existing[v] = trans
            done += 1
            print(f"[{lang} {idx}/{len(missing)}] OK: {v[:40]} -> {trans[:40]}", flush=True)
        if idx % 5 == 0:
            out_file.write_text(json.dumps(existing, ensure_ascii=False, indent=2), encoding="utf-8")
            print(f"[{lang}] saved progress", flush=True)
        time.sleep(0.5)
    out_file.write_text(json.dumps(existing, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[{lang}] Done. Translated {done}, failed {len(failed)}", flush=True)

if __name__ == "__main__":
    main()
