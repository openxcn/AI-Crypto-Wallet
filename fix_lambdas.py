#!/usr/bin/env python3
import pathlib

root = pathlib.Path('app/src/main/java/com/aicryptowallet/app')
changed = 0
for p in root.rglob('*.java'):
    text = p.read_text(encoding='utf-8')
    if ') ->)' in text:
        new_text = text.replace(') ->)', ') -> {')
        p.write_text(new_text, encoding='utf-8')
        count = text.count(') ->)')
        changed += count
        print(f'{p}: {count}')
print(f'Total replacements: {changed}')
