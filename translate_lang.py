#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Translate extracted strings for a single language (run one per lang in parallel)."""
import json
import re
import time
import sys
from pathlib import Path

PROJECT = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
EXTRACTED = PROJECT / "extracted_strings.json"

sys.path.insert(0, str(PROJECT / ".local_pylibs"))
from googletrans import Translator

PLACEHOLDER_RE = re.compile(r'(%\d+\$[sdxf])')

def protect_placeholders(text):
    placeholders = []
    counter = [0]
    def repl(m):
        counter[0] += 1
        placeholders.append(m.group(1))
        return f"__PH{counter[0]}__"
    protected = PLACEHOLDER_RE.sub(repl, text)
    return protected, placeholders

def restore_placeholders(text, placeholders):
    for i, ph in enumerate(placeholders, 1):
        text = text.replace(f"__PH{i}__", ph)
    return text

def clean_translation(text):
    text = re.sub(r'\s*(__PH\d+__)\s*', r'\1', text)
    return text

def translate_with_retry(translator, text, dest, max_retries=5):
    for attempt in range(max_retries):
        try:
            result = translator.translate(text, src='zh-cn', dest=dest)
            return result.text if result else None
        except Exception as e:
            wait = 2 ** attempt
            print(f"  [{dest}] Retry {attempt+1}/{max_retries} after {wait}s: {e}", flush=True)
            time.sleep(wait)
    return None

def main():
    lang = sys.argv[1]
    out_file = PROJECT / f"translations_{lang}.json"
    data = json.loads(EXTRACTED.read_text(encoding="utf-8"))
    existing = {}
    if out_file.exists():
        existing = json.loads(out_file.read_text(encoding="utf-8"))

    translator = Translator()
    strings = data['strings']
    total = len(strings)
    translated_count = 0
    failed = []

    print(f"[{lang}] Translating {total} strings...", flush=True)

    for idx, entry in enumerate(strings, 1):
        zh = entry['zh']
        if zh in existing and existing[zh]:
            print(f"[{lang} {idx}/{total}] SKIP", flush=True)
            continue

        protected, placeholders = protect_placeholders(zh)
        trans = translate_with_retry(translator, protected, lang)
        if trans is None:
            failed.append(zh)
            trans = zh
        else:
            trans = clean_translation(trans)
            trans = restore_placeholders(trans, placeholders)

        existing[zh] = trans
        translated_count += 1
        if translated_count % 5 == 0:
            out_file.write_text(json.dumps(existing, ensure_ascii=False, indent=2), encoding="utf-8")
            print(f"[{lang}] Saved progress ({translated_count} new)", flush=True)
        time.sleep(0.22)

    out_file.write_text(json.dumps(existing, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[{lang}] Done. New: {translated_count}, Failed: {len(failed)}", flush=True)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python translate_lang.py <en|ja|de>")
        sys.exit(1)
    main()
