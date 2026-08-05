#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Rename string resources with Chinese characters to ASCII-only names."""
import json
import re
from pathlib import Path

PROJECT = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
JAVA_DIR = PROJECT / "app/src/main/java/com/aicryptowallet/app"
LAYOUT_DIR = PROJECT / "app/src/main/res/layout"
VALUES_DIRS = [
    PROJECT / "app/src/main/res/values",
    PROJECT / "app/src/main/res/values-en",
    PROJECT / "app/src/main/res/values-ja",
    PROJECT / "app/src/main/res/values-de",
]

def sanitize(text):
    # Remove format specifiers, HTML tags, special chars, emojis
    text = re.sub(r'%\d+\$[sdxf]', '', text)
    text = re.sub(r'<[^>]+>', '', text)
    text = re.sub(r'[^\w\s]', ' ', text)
    text = re.sub(r'[\U00010000-\U0010ffff]', ' ', text)  # emojis
    words = text.strip().lower().split()
    # Take first 4 meaningful words, filter out very short words except first
    filtered = []
    for w in words:
        if len(w) > 1 or not filtered:
            filtered.append(w)
    return '_'.join(filtered[:4]) if filtered else 'item'

def main():
    # Load English translations
    trans = json.loads((PROJECT / "translations.json").read_text(encoding="utf-8"))

    # Build old_name -> (zh, en) mapping from values/strings.xml
    old_to_zh = {}
    zh_to_old = {}
    zh_to_en = {zh: vals.get('en', zh) for zh, vals in trans.items()}

    zh_strings_xml = VALUES_DIRS[0] / "strings.xml"
    content = zh_strings_xml.read_text(encoding="utf-8")
    for m in re.finditer(r'<string name="([^"]+)">(.*?)</string>', content, re.S):
        name, value = m.group(1), m.group(2)
        old_to_zh[name] = value
        zh_to_old[value] = name

    # Generate new ASCII names
    old_to_new = {}
    used_names = set()
    for old_name, zh in old_to_zh.items():
        # Determine prefix from old name
        prefix = 'str'
        for p in ['text_', 'toast_', 'title_', 'msg_', 'btn_', 'hint_', 'label_', 'str_']:
            if old_name.startswith(p):
                prefix = p.rstrip('_')
                break

        en = zh_to_en.get(zh, zh)
        suffix = sanitize(en)
        if not suffix or suffix == 'item':
            suffix = sanitize(zh)
        new_name = f"{prefix}_{suffix}".lower()
        # Make unique
        base = new_name
        i = 2
        while new_name in used_names:
            new_name = f"{base}_{i}"
            i += 1
        used_names.add(new_name)
        old_to_new[old_name] = new_name

    print(f"Renaming {len(old_to_new)} resources")

    # Update strings.xml files
    for d in VALUES_DIRS:
        f = d / "strings.xml"
        if not f.exists():
            continue
        content = f.read_text(encoding="utf-8")
        def repl(m):
            old_name = m.group(1)
            new_name = old_to_new.get(old_name, old_name)
            return f'<string name="{new_name}">'
        new_content = re.sub(r'<string name="([^"]+)">', repl, content)
        f.write_text(new_content, encoding="utf-8")
        print(f"  Updated {f}")

    # Update Java source files
    java_files = list(JAVA_DIR.rglob("*.java"))
    for f in java_files:
        content = f.read_text(encoding="utf-8")
        new_content = content
        for old_name, new_name in old_to_new.items():
            new_content = new_content.replace(f"R.string.{old_name}", f"R.string.{new_name}")
        if new_content != content:
            f.write_text(new_content, encoding="utf-8")
            print(f"  Updated {f.relative_to(PROJECT)}")

    # Update XML layout files
    xml_files = list(LAYOUT_DIR.rglob("*.xml"))
    for f in xml_files:
        content = f.read_text(encoding="utf-8")
        new_content = content
        for old_name, new_name in old_to_new.items():
            new_content = new_content.replace(f"@string/{old_name}", f"@string/{new_name}")
        if new_content != content:
            f.write_text(new_content, encoding="utf-8")
            print(f"  Updated {f.relative_to(PROJECT)}")

    # Update extracted_strings.json and i18n_mapping.json for consistency
    extracted = json.loads((PROJECT / "extracted_strings.json").read_text(encoding="utf-8"))
    for entry in extracted['strings']:
        old_name = entry['name']
        entry['name'] = old_to_new.get(old_name, old_name)
    (PROJECT / "extracted_strings.json").write_text(json.dumps(extracted, ensure_ascii=False, indent=2), encoding="utf-8")

    mapping = json.loads((PROJECT / "i18n_mapping.json").read_text(encoding="utf-8"))
    new_fmt_to_name = {}
    for fmt, old_name in mapping['fmt_to_name'].items():
        new_fmt_to_name[fmt] = old_to_new.get(old_name, old_name)
    mapping['fmt_to_name'] = new_fmt_to_name
    (PROJECT / "i18n_mapping.json").write_text(json.dumps(mapping, ensure_ascii=False, indent=2), encoding="utf-8")

    print("Done. Wrote name mapping to name_mapping.json")
    (PROJECT / "name_mapping.json").write_text(json.dumps(old_to_new, ensure_ascii=False, indent=2), encoding="utf-8")

if __name__ == "__main__":
    main()
