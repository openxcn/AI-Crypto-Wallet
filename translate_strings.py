#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Translate extracted Chinese strings to en/ja/de using Google Translate."""
import json
import re
import time
import os
import sys
from pathlib import Path

PROJECT = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
EXTRACTED = PROJECT / "extracted_strings.json"
TRANSLATIONS = PROJECT / "translations.json"

# Add local googletrans to path
sys.path.insert(0, str(PROJECT / ".local_pylibs"))

try:
    from googletrans import Translator, LANGUAGES
except Exception as e:
    print(f"Failed to import googletrans: {e}")
    sys.exit(1)

# Lang codes for Google Translate
LANG_MAP = {'en': 'en', 'ja': 'ja', 'de': 'de'}

PLACEHOLDER_RE = re.compile(r'(%\d+\$[sdxf])')

def protect_placeholders(text):
    """Replace format specifiers with placeholders that GT won't translate."""
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
    # Remove extra spaces around placeholders
    text = re.sub(r'\s*(__PH\d+__)\s*', r'\1', text)
    return text

def load_existing():
    if TRANSLATIONS.exists():
        return json.loads(TRANSLATIONS.read_text(encoding="utf-8"))
    return {}

def save_translations(data):
    TRANSLATIONS.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")

def translate_with_retry(translator, text, dest, max_retries=3):
    for attempt in range(max_retries):
        try:
            result = translator.translate(text, src='zh-cn', dest=dest)
            return result.text if result else None
        except Exception as e:
            print(f"  Retry {attempt+1}/{max_retries} for {dest}: {e}")
            time.sleep(2 ** attempt)
    return None

def main():
    data = json.loads(EXTRACTED.read_text(encoding="utf-8"))
    existing = load_existing()
    translator = Translator()

    strings = data['strings']
    total = len(strings)
    translated_count = 0
    failed = []

    print(f"Translating {total} unique strings to en/ja/de...", flush=True)

    for idx, entry in enumerate(strings, 1):
        zh = entry['zh']
        if zh in existing and all(k in existing[zh] for k in LANG_MAP):
            print(f"[{idx}/{total}] SKIP (cached): {zh[:50]}", flush=True)
            continue

        print(f"[{idx}/{total}] Translating: {zh[:70]}", flush=True)
        protected, placeholders = protect_placeholders(zh)
        entry_trans = {}
        for lang, gt_code in LANG_MAP.items():
            trans = translate_with_retry(translator, protected, gt_code)
            if trans is None:
                failed.append((zh, lang))
                entry_trans[lang] = zh  # fallback to Chinese
            else:
                trans = clean_translation(trans)
                trans = restore_placeholders(trans, placeholders)
                entry_trans[lang] = trans
            time.sleep(0.25)

        existing[zh] = entry_trans
        translated_count += 1
        save_translations(existing)
        if translated_count % 5 == 0:
            print(f"  Saved progress ({translated_count} new translations)", flush=True)

    save_translations(existing)
    print(f"\nDone. New translations: {translated_count}", flush=True)
    if failed:
        print(f"Failed translations: {len(failed)}", flush=True)
        for zh, lang in failed[:20]:
            print(f"  - {lang}: {zh}", flush=True)

if __name__ == "__main__":
    main()
