#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import xml.etree.ElementTree as ET
from pathlib import Path

PROJECT = Path(r"c:\Users\Administrator\Documents\trae work\ai_crypto_wallet_android")
names = ['btn_i_ve_saved','str_1_select_the_supplier','str_4_clock','str_save_img','str_this_software_is_an','str_world_first','text_coller_adresse']
for lang in ['values-en', 'values-de', 'values-ja', 'values']:
    path = PROJECT / 'app/src/main/res' / lang / 'strings.xml'
    if not path.exists():
        continue
    root = ET.parse(path).getroot()
    print(f"\n=== {lang} ===")
    for name in names:
        for s in root.findall('string'):
            if s.get('name') == name:
                v = ''.join(s.itertext())
                print(f"{name}: {repr(v)}")
                break
