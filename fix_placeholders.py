#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Fix placeholder formatting in translation JSON files."""
import json
import re
from pathlib import Path

PROJECT = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
EXTRACTED = PROJECT / "extracted_strings.json"
PLACEHOLDER_RE = re.compile(r'(%\d+\$[sdxf])')
BROKEN_RE = re.compile(r'__\s*PH\s*(\d+)\s*__')

def main():
    data = json.loads(EXTRACTED.read_text(encoding="utf-8"))
    zh_to_placeholders = {}
    for entry in data['strings']:
        zh = entry['zh']
        zh_to_placeholders[zh] = PLACEHOLDER_RE.findall(zh)

    for lang in ['en', 'ja', 'de']:
        f = PROJECT / f"translations_{lang}.json"
        if not f.exists():
            continue
        trans = json.loads(f.read_text(encoding="utf-8"))
        fixed_count = 0
        for zh, text in trans.items():
            placeholders = zh_to_placeholders.get(zh, [])
            if not placeholders:
                continue
            # Fix broken __ PH1 __ style
            def repl(m):
                idx = int(m.group(1)) - 1
                if 0 <= idx < len(placeholders):
                    return placeholders[idx]
                return m.group(0)
            new_text = BROKEN_RE.sub(repl, text)
            # Also fix any ｟1｠ style if left
            for i, ph in enumerate(placeholders, 1):
                new_text = re.sub(rf'｟\s*{i}\s*｠', ph, new_text)
            if new_text != text:
                trans[zh] = new_text
                fixed_count += 1
        f.write_text(json.dumps(trans, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"[{lang}] Fixed {fixed_count} placeholders")

if __name__ == "__main__":
    main()
