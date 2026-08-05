#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Resolve str_wallet_management duplicate by renaming the 'Manage Wallet' entry to str_manage_wallet."""
from pathlib import Path

PROJECT = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
VALUES_DIRS = [
    PROJECT / "app/src/main/res/values",
    PROJECT / "app/src/main/res/values-en",
    PROJECT / "app/src/main/res/values-ja",
    PROJECT / "app/src/main/res/values-de",
]
HOME_LAYOUT = PROJECT / "app/src/main/res/layout/activity_home.xml"

for d in VALUES_DIRS:
    f = d / "strings.xml"
    content = f.read_text(encoding='utf-8')
    new_content = content.replace('<string name="str_wallet_management">', '<string name="str_manage_wallet">', 1)
    if new_content != content:
        f.write_text(new_content, encoding='utf-8')
        print(f"Renamed first str_wallet_management -> str_manage_wallet in {f.relative_to(PROJECT)}")

if HOME_LAYOUT.exists():
    content = HOME_LAYOUT.read_text(encoding='utf-8')
    new_content = content.replace('@string/str_wallet_management', '@string/str_manage_wallet', 1)
    if new_content != content:
        HOME_LAYOUT.write_text(new_content, encoding='utf-8')
        print(f"Updated {HOME_LAYOUT.relative_to(PROJECT)} reference")
print("Done.")
