#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Translate using MyMemory API with caching."""
import json
import re
import time
import sys
import requests
from pathlib import Path
from urllib.parse import quote

PROJECT = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
EXTRACTED = PROJECT / "extracted_strings.json"

# Use a dummy email to get higher quota; replace with real if available
EMAIL = "user@example.com"

PLACEHOLDER_RE = re.compile(r'(%\d+\$[sdxf])')

def protect_placeholders(text):
    placeholders = []
    counter = [0]
    def repl(m):
        counter[0] += 1
        placeholders.append(m.group(1))
        return f"｟{counter[0]}｠"
    protected = PLACEHOLDER_RE.sub(repl, text)
    return protected, placeholders

def restore_placeholders(text, placeholders):
    # MyMemory may insert spaces around placeholders; be tolerant
    for i, ph in enumerate(placeholders, 1):
        text = re.sub(rf'｟\s*{i}\s*｠', ph, text)
    return text

def translate(text, dest):
    protected, placeholders = protect_placeholders(text)
    encoded = quote(protected)
    url = f"https://api.mymemory.translated.net/get?q={encoded}&langpair=zh-CN|{dest}&de={EMAIL}"
    try:
        r = requests.get(url, timeout=15)
        data = r.json()
        if data.get('responseStatus') == 200:
            trans = data['responseData']['translatedText']
            # MyMemory sometimes returns the same text if it fails
            if trans and trans != protected:
                return restore_placeholders(trans, placeholders)
    except Exception as e:
        print(f"  Error: {e}", flush=True)
    return None

def main():
    lang = sys.argv[1] if len(sys.argv) > 1 else 'en'
    out_file = PROJECT / f"translations_{lang}.json"
    data = json.loads(EXTRACTED.read_text(encoding="utf-8"))
    existing = {}
    if out_file.exists():
        existing = json.loads(out_file.read_text(encoding="utf-8"))

    strings = data['strings']
    total = len(strings)
    translated_count = 0
    failed = []

    print(f"[{lang}] Translating {total} strings with MyMemory...", flush=True)

    for idx, entry in enumerate(strings, 1):
        zh = entry['zh']
        if zh in existing and existing[zh] and existing[zh] != zh:
            print(f"[{lang} {idx}/{total}] SKIP", flush=True)
            continue

        trans = translate(zh, lang)
        if trans is None:
            failed.append(zh)
            trans = zh
            print(f"[{lang} {idx}/{total}] FAILED: {zh[:50]}", flush=True)
        else:
            print(f"[{lang} {idx}/{total}] OK: {zh[:50]} -> {trans[:50]}", flush=True)

        existing[zh] = trans
        translated_count += 1
        if translated_count % 5 == 0:
            out_file.write_text(json.dumps(existing, ensure_ascii=False, indent=2), encoding="utf-8")
            print(f"[{lang}] Saved progress ({translated_count} new)", flush=True)
        time.sleep(0.6)

    out_file.write_text(json.dumps(existing, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[{lang}] Done. New: {translated_count}, Failed: {len(failed)}", flush=True)

if __name__ == "__main__":
    main()
