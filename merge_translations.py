#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Merge per-language translation JSONs into translations.json expected by replace_i18n.py."""
import json
import re
from pathlib import Path

PROJECT = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
EXTRACTED = PROJECT / "extracted_strings.json"

def main():
    data = json.loads(EXTRACTED.read_text(encoding="utf-8"))
    zh_strings = [s['zh'] for s in data['strings']]

    merged = {}
    for lang in ['en', 'ja', 'de']:
        f = PROJECT / f"translations_{lang}.json"
        if not f.exists():
            print(f"Missing {f}")
            continue
        trans = json.loads(f.read_text(encoding="utf-8"))
        for zh in zh_strings:
            merged.setdefault(zh, {})[lang] = trans.get(zh, zh)

    # Fill any missing Chinese fallback
    for zh in zh_strings:
        for lang in ['en', 'ja', 'de']:
            if lang not in merged.get(zh, {}):
                merged.setdefault(zh, {})[lang] = zh

    (PROJECT / "translations.json").write_text(json.dumps(merged, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Merged {len(merged)} strings into translations.json")

if __name__ == "__main__":
    main()
