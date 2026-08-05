#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Rename remaining non-ASCII string resource names to ASCII."""
import json
import re
from pathlib import Path

PROJECT = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
JAVA_DIR = PROJECT / "app/src/main/java/com/aicryptowallet/app"
LAYOUT_DIR = PROJECT / "app/src/main/res/layout"
RES_DIR = PROJECT / "app/src/main/res"
VALUES_DIRS = [
    PROJECT / "app/src/main/res/values",
    PROJECT / "app/src/main/res/values-en",
    PROJECT / "app/src/main/res/values-ja",
    PROJECT / "app/src/main/res/values-de",
]

def sanitize(text):
    text = re.sub(r'%\d+\$[sdxf]', '', text)
    text = re.sub(r'<[^>]+>', '', text)
    text = re.sub(r'[^\w\s]', ' ', text)
    text = re.sub(r'[\U00010000-\U0010ffff]', ' ', text)
    words = text.strip().lower().split()
    filtered = []
    for w in words:
        if len(w) > 1 or not filtered:
            filtered.append(w)
    return '_'.join(filtered[:5]) if filtered else 'item'

def main():
    # Load existing name_mapping.json for hints if available
    mapping_hints = {}
    name_mapping_file = PROJECT / "name_mapping.json"
    if name_mapping_file.exists():
        nm = json.loads(name_mapping_file.read_text(encoding='utf-8'))
        for key, old_name in nm.items():
            if old_name and isinstance(old_name, str):
                mapping_hints[old_name] = key

    # Read English strings to generate names
    en_strings = {}
    en_file = VALUES_DIRS[1] / "strings.xml"
    if en_file.exists():
        content = en_file.read_text(encoding='utf-8')
        for m in re.finditer(r'<string name="([^"]+)">(.*?)</string>', content, re.S):
            en_strings[m.group(1)] = m.group(2)

    # Collect non-ASCII names from values (should be same across languages)
    zh_file = VALUES_DIRS[0] / "strings.xml"
    zh_content = zh_file.read_text(encoding='utf-8')
    old_names = []
    for m in re.finditer(r'<string name="([^"]+)">', zh_content):
        name = m.group(1)
        if any(ord(c) > 127 for c in name):
            old_names.append(name)

    # Determine prefix from old name
    def get_prefix(old_name):
        for p in ['text_', 'toast_', 'title_', 'msg_', 'btn_', 'hint_', 'label_', 'str_']:
            if old_name.startswith(p):
                return p.rstrip('_')
        return 'str'

    old_to_new = {}
    used_names = set()
    for old_name in old_names:
        # Use hint if available and valid ASCII
        hint = mapping_hints.get(old_name)
        if hint and re.match(r'^[a-zA-Z_][a-zA-Z0-9_]*$', hint):
            base = f"{get_prefix(old_name)}_{hint}"
        else:
            en = en_strings.get(old_name, '')
            suffix = sanitize(en)
            if not suffix or suffix == 'item':
                # fallback: use old name with chinese removed/transliterated simply
                suffix = sanitize(re.sub(r'[^\w\s]', ' ', old_name))
            base = f"{get_prefix(old_name)}_{suffix}"
        base = re.sub(r'_+', '_', base).strip('_').lower()
        if not base or base == get_prefix(old_name):
            base = f"{get_prefix(old_name)}_res"
        new_name = base
        i = 2
        while new_name in used_names:
            new_name = f"{base}_{i}"
            i += 1
        used_names.add(new_name)
        old_to_new[old_name] = new_name

    print(f"Renaming {len(old_to_new)} resources:")
    for old, new in sorted(old_to_new.items()):
        print(f"  {old} -> {new}")

    # Update strings.xml files
    for d in VALUES_DIRS:
        f = d / "strings.xml"
        if not f.exists():
            continue
        content = f.read_text(encoding='utf-8')
        def repl(m):
            old_name = m.group(1)
            new_name = old_to_new.get(old_name, old_name)
            return f'<string name="{new_name}">'
        new_content = re.sub(r'<string name="([^"]+)">', repl, content)
        if new_content != content:
            f.write_text(new_content, encoding='utf-8')
            print(f"  Updated {f.relative_to(PROJECT)}")

    # Update Java source files
    java_files = list(JAVA_DIR.rglob("*.java"))
    for f in java_files:
        content = f.read_text(encoding='utf-8')
        new_content = content
        # longest first to avoid partial replacements
        for old_name in sorted(old_to_new, key=len, reverse=True):
            new_content = new_content.replace(f"R.string.{old_name}", f"R.string.{old_to_new[old_name]}")
        if new_content != content:
            f.write_text(new_content, encoding='utf-8')
            print(f"  Updated {f.relative_to(PROJECT)}")

    # Update all XML files under res (layouts, menus, etc.)
    xml_files = list(RES_DIR.rglob("*.xml"))
    for f in xml_files:
        content = f.read_text(encoding='utf-8')
        new_content = content
        for old_name in sorted(old_to_new, key=len, reverse=True):
            new_content = new_content.replace(f"@string/{old_name}", f"@string/{old_to_new[old_name]}")
        if new_content != content:
            f.write_text(new_content, encoding='utf-8')
            print(f"  Updated {f.relative_to(PROJECT)}")

    # Update mapping files if present
    for mf_name in ["extracted_strings.json", "i18n_mapping.json"]:
        mf = PROJECT / mf_name
        if mf.exists():
            data = json.loads(mf.read_text(encoding='utf-8'))
            if isinstance(data, dict):
                if 'strings' in data:
                    for entry in data['strings']:
                        if 'name' in entry:
                            entry['name'] = old_to_new.get(entry['name'], entry['name'])
                if 'fmt_to_name' in data:
                    new_map = {}
                    for fmt, old_name in data['fmt_to_name'].items():
                        new_map[fmt] = old_to_new.get(old_name, old_name)
                    data['fmt_to_name'] = new_map
                mf.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
                print(f"  Updated {mf_name}")

    # Save mapping
    (PROJECT / "nonascii_name_mapping.json").write_text(json.dumps(old_to_new, ensure_ascii=False, indent=2), encoding='utf-8')
    print("Done. Mapping saved to nonascii_name_mapping.json")

if __name__ == "__main__":
    main()
