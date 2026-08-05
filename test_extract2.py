#!/usr/bin/env python3
import re, sys
sys.path.insert(0, 'c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android')
from fix_i18n import extract_java_occurrences
from pathlib import Path
p = Path('c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android/app/src/main/java/com/aicryptowallet/app/AddressBookActivity.java')
content = p.read_text(encoding='utf-8')
occs = extract_java_occurrences(content)
print('occs', len(occs))
for occ in occs:
    print(occ)
