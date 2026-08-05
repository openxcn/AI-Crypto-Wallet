#!/usr/bin/env python3
import pathlib, re
from xml.etree import ElementTree as ET

PROJECT = pathlib.Path('app/src/main/res')
for lang in ['values','values-en','values-ja','values-de']:
    path = PROJECT / lang / 'strings.xml'
    if not path.exists():
        continue
    raw = path.read_text(encoding='utf-8')
    # count &apos;
    apos_entities = raw.count('&apos;')
    # parse strings and count unescaped ' in values
    unescaped = 0
    escaped_already = 0
    try:
        root = ET.parse(path).getroot()
        for s in root.findall('string'):
            text = s.text or ''
            for m in re.finditer(r"(?<!\\)'", text):
                unescaped += 1
            for _ in re.finditer(r"\\'", text):
                escaped_already += 1
    except Exception as e:
        print(f'{lang}: parse error {e}')
    print(f'{lang}: &apos; entities={apos_entities}, unescaped apostrophes={unescaped}, already escaped\\\'={escaped_already}')
