#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import xml.etree.ElementTree as ET
from pathlib import Path
import re

PROJECT = Path(r"c:\Users\Administrator\Documents\trae work\ai_crypto_wallet_android")
for lang in ["values", "values-en", "values-ja", "values-de"]:
    path = PROJECT / "app/src/main/res" / lang / "strings.xml"
    if not path.exists():
        continue
    root = ET.parse(path).getroot()
    for s in root.findall("string"):
        name = s.get("name", "")
        val = "".join(s.itertext())
        # Check for literal backslash-u that AAPT might interpret as unicode escape
        if re.search(r"\\u[0-9a-fA-F]?[0-9a-fA-F]?[0-9a-fA-F]?[0-9a-fA-F]?", val):
            # Valid unicode escape is \\u followed by exactly 4 hex
            if not re.search(r"\\u[0-9a-fA-F]{4}", val):
                print(f"[{lang}] {name}: invalid unicode escape: {repr(val[:200])}")
        # Check for control chars except tab/newline
        for c in val:
            o = ord(c)
            if o < 32 and c not in "\t\n\r":
                print(f"[{lang}] {name}: control char {o}: {repr(val[:200])}")
                break
        # Check for unmatched surrogate
        for c in val:
            o = ord(c)
            if 0xD800 <= o <= 0xDFFF:
                print(f"[{lang}] {name}: surrogate {hex(o)}: {repr(val[:200])}")
                break
