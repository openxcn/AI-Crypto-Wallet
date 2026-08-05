#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Fix broken Android string escapes introduced by translation (e.g. '\ n' -> '\n')."""
from pathlib import Path

PROJECT = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
VALUES_DIRS = [
    PROJECT / "app/src/main/res/values",
    PROJECT / "app/src/main/res/values-en",
    PROJECT / "app/src/main/res/values-ja",
    PROJECT / "app/src/main/res/values-de",
]

def fix_value(val):
    # Fix backslash-warning_sign-space-n (e.g. \⚠ nWarning -> \n⚠ Warning)
    val = val.replace('\\⚠ n', '\\n⚠ ')
    # Fix backslash-space-n -> backslash-n
    val = val.replace('\\ n', '\\n')
    return val

for d in VALUES_DIRS:
    f = d / "strings.xml"
    if not f.exists():
        continue
    content = f.read_text(encoding='utf-8')
    new_content = content
    # Find all string tags and fix their values
    import re
    def repl(m):
        name = m.group(1)
        val = m.group(2)
        fixed = fix_value(val)
        if fixed != val:
            return f'<string name="{name}">{fixed}</string>'
        return m.group(0)
    new_content = re.sub(r'<string name="([^"]+)">(.*?)</string>', repl, new_content, flags=re.S)
    if new_content != content:
        f.write_text(new_content, encoding='utf-8')
        print(f"Fixed broken escapes in {f.relative_to(PROJECT)}")
print("Done.")
