#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Apply a manual old->new resource name mapping to strings.xml, Java, and XML."""
import json
import re
from pathlib import Path

PROJECT = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
JAVA_DIR = PROJECT / "app/src/main/java/com/aicryptowallet/app"
RES_DIR = PROJECT / "app/src/main/res"
VALUES_DIRS = [
    PROJECT / "app/src/main/res/values",
    PROJECT / "app/src/main/res/values-en",
    PROJECT / "app/src/main/res/values-ja",
    PROJECT / "app/src/main/res/values-de",
]

def main():
    mapping = json.loads((PROJECT / "nonascii_name_mapping.json").read_text(encoding='utf-8'))
    # Filter only mappings that actually change
    mapping = {k: v for k, v in mapping.items() if k != v}
    if not mapping:
        print("No changes needed.")
        return
    print(f"Applying {len(mapping)} name changes:")
    for old, new in sorted(mapping.items()):
        print(f"  {old} -> {new}")

    # Update strings.xml files
    for d in VALUES_DIRS:
        f = d / "strings.xml"
        if not f.exists():
            continue
        content = f.read_text(encoding='utf-8')
        def repl(m):
            old_name = m.group(1)
            new_name = mapping.get(old_name, old_name)
            return f'<string name="{new_name}">'
        new_content = re.sub(r'<string name="([^"]+)">', repl, content)
        if new_content != content:
            f.write_text(new_content, encoding='utf-8')
            print(f"  Updated {f.relative_to(PROJECT)}")

    # Update Java
    for f in JAVA_DIR.rglob("*.java"):
        content = f.read_text(encoding='utf-8')
        new_content = content
        for old_name in sorted(mapping, key=len, reverse=True):
            new_content = new_content.replace(f"R.string.{old_name}", f"R.string.{mapping[old_name]}")
        if new_content != content:
            f.write_text(new_content, encoding='utf-8')
            print(f"  Updated {f.relative_to(PROJECT)}")

    # Update all XML under res
    for f in RES_DIR.rglob("*.xml"):
        content = f.read_text(encoding='utf-8')
        new_content = content
        for old_name in sorted(mapping, key=len, reverse=True):
            new_content = new_content.replace(f"@string/{old_name}", f"@string/{mapping[old_name]}")
        if new_content != content:
            f.write_text(new_content, encoding='utf-8')
            print(f"  Updated {f.relative_to(PROJECT)}")

    # Update mapping JSONs
    for mf_name in ["extracted_strings.json", "i18n_mapping.json"]:
        mf = PROJECT / mf_name
        if mf.exists():
            data = json.loads(mf.read_text(encoding='utf-8'))
            if isinstance(data, dict):
                if 'strings' in data:
                    for entry in data['strings']:
                        if 'name' in entry:
                            entry['name'] = mapping.get(entry['name'], entry['name'])
                if 'fmt_to_name' in data:
                    new_map = {}
                    for fmt, old_name in data['fmt_to_name'].items():
                        new_map[fmt] = mapping.get(old_name, old_name)
                    data['fmt_to_name'] = new_map
                mf.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
                print(f"  Updated {mf_name}")
    print("Done.")

if __name__ == "__main__":
    main()
