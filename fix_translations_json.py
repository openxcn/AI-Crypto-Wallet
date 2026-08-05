#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Fix placeholder mismatches in translations.json."""
import json
import re
from pathlib import Path

PROJECT = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
EXTRACTED = PROJECT / "extracted_strings.json"
TRANSLATIONS = PROJECT / "translations.json"
PLACEHOLDER_RE = re.compile(r'(%\d+\$[sdxf])')

def get_placeholders(text):
    return PLACEHOLDER_RE.findall(text)

def fix_placeholders(zh, trans):
    zh_ph = get_placeholders(zh)
    if not zh_ph:
        return trans
    trans_ph = get_placeholders(trans)
    if len(trans_ph) == len(zh_ph):
        return trans

    # Try to repair by replacing placeholder-like tokens
    result = trans
    for i, ph in enumerate(zh_ph, 1):
        # Skip if already present
        if ph in result:
            continue
        # Patterns that MyMemory commonly produces
        patterns = [
            rf'%\s*{i}\s*\$?\s*[sdxf]?',  # %1, %1.
            rf'"\s*{i}\s*"',              # "1"
            rf'(?<![\d.]){i}(?![\d.])',    # standalone 1
        ]
        for pat in patterns:
            new_result, count = re.subn(pat, ph, result, count=1)
            if count > 0:
                result = new_result
                break

    # If still mismatch, fallback to Chinese with placeholders
    if len(get_placeholders(result)) != len(zh_ph):
        return zh
    return result

def main():
    data = json.loads(EXTRACTED.read_text(encoding="utf-8"))
    trans = json.loads(TRANSLATIONS.read_text(encoding="utf-8"))

    fixed = 0
    fallback = 0
    mismatches = []

    for entry in data['strings']:
        zh = entry['zh']
        if zh not in trans:
            continue
        zh_ph = get_placeholders(zh)
        if not zh_ph:
            continue
        for lang in ['en', 'ja', 'de']:
            text = trans[zh].get(lang, zh)
            fixed_text = fix_placeholders(zh, text)
            if fixed_text != text:
                trans[zh][lang] = fixed_text
                fixed += 1
                if fixed_text == zh:
                    fallback += 1
                    mismatches.append((lang, zh[:60], text[:60]))

    TRANSLATIONS.write_text(json.dumps(trans, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Fixed {fixed} translations, {fallback} fallbacks to Chinese")
    if mismatches:
        print("Sample mismatches:")
        for lang, zh, text in mismatches[:10]:
            print(f"  [{lang}] {zh} -> {text}")

if __name__ == "__main__":
    main()
