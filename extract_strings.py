#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Extract user-facing Chinese strings from Java and XML source files."""
import json
import os
import re
from pathlib import Path
from collections import defaultdict

PROJECT = Path("c:/Users/Administrator/Documents/trae work/ai_crypto_wallet_android")
JAVA_DIR = PROJECT / "app/src/main/java/com/aicryptowallet/app"
LAYOUT_DIR = PROJECT / "app/src/main/res/layout"
OUT_JSON = PROJECT / "extracted_strings.json"

CHINESE_RE = re.compile(r'[\u4e00-\u9fff]')
STRING_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')

# Methods/contexts that indicate user-facing text
USER_FACING_PATTERNS = [
    r'\.setText\s*\(',
    r'\.setTitle\s*\(',
    r'\.setMessage\s*\(',
    r'\.setHint\s*\(',
    r'\.setError\s*\(',
    r'\.setPositiveButton\s*\(',
    r'\.setNegativeButton\s*\(',
    r'\.setNeutralButton\s*\(',
    r'Toast\.makeText\s*\(',
    r'\.getMenu\s*\(\s*\)\.add\s*\(',
    r'popup\.getMenu\s*\(\s*\)\.add\s*\(',
    r'\.setContentTitle\s*\(',
    r'\.setContentText\s*\(',
    r'\.setSubText\s*\(',
    r'\.setTicker\s*\(',
    r'\.setDialogTitle\s*\(',
    r'\.setSummary\s*\(',
    r'\.setAction\s*\(',
    r'\.createChooser\s*\(',
    r'\.setLabel\s*\(',
    r'\.setPlaceholderText\s*\(',
    r'\.setPrompt\s*\(',
    r'Snackbar\.make\s*\(',
]

SKIP_PATTERNS = [
    r'^\s*//',
    r'^\s*\*',
    r'^\s*/\*',
    r'\bLog\.[vdiew]\s*\(',
    r'\bLogger\.[a-zA-Z]+\s*\(',
    r'new\s+SimpleDateFormat\s*\(',
    r'\.format\s*\(',
    r'new\s+File\s*\(',
    r'\.putString\s*\(',
    r'\.getString\s*\(',
    r'\.putInt\s*\(',
    r'\.getInt\s*\(',
    r'\.putBoolean\s*\(',
    r'\.getBoolean\s*\(',
    r'optString\s*\(',
    r'getString\s*\(',
    r'\.getJSONArray\s*\(',
    r'\.put\s*\(',
    r'\.remove\s*\(',
    r'toSystemPrompt',
    r'systemPrompt',
    r'\.print\s*\(',
    r'\.println\s*\(',
    r'Log\.getStackTraceString',
    r'\.tag\s*\(',
    r'\.d\s*\(',
    r'\.e\s*\(',
    r'\.i\s*\(',
    r'\.v\s*\(',
    r'\.w\s*\(',
]

XML_USER_ATTRS = {
    'android:text', 'android:hint', 'android:title',
    'app:title', 'android:contentDescription',
    'android:dialogTitle', 'android:summary',
    'android:subtitle', 'android:textOn', 'android:textOff',
    'android:tooltipText',
}
XML_SKIP_ATTRS = {'tools:text', 'tools:hint', 'tools:title'}

def is_user_facing_context(line):
    for p in USER_FACING_PATTERNS:
        if re.search(p, line):
            return True
    return False

def should_skip_line(line):
    for p in SKIP_PATTERNS:
        if re.search(p, line):
            return True
    return False

def extract_java_strings(text):
    results = []
    lines = text.splitlines()
    for i, raw_line in enumerate(lines, 1):
        if not CHINESE_RE.search(raw_line):
            continue
        line = raw_line.strip()
        if should_skip_line(line):
            continue
        if not is_user_facing_context(raw_line):
            continue
        for m in STRING_RE.finditer(raw_line):
            s = m.group(1)
            s = s.replace('\\"', '"').replace('\\n', '\n').replace('\\t', '\t')
            if CHINESE_RE.search(s):
                results.append({
                    'line': i,
                    'text': s,
                    'raw': raw_line.strip(),
                    'type': 'java_literal'
                })
    return results

def extract_xml_strings(text, filename):
    results = []
    lines = text.splitlines()
    for i, raw_line in enumerate(lines, 1):
        if not CHINESE_RE.search(raw_line):
            continue
        # Simple attribute extraction
        for attr in XML_USER_ATTRS:
            pattern = rf'{attr}\s*=\s*"([^"]*)"'
            for m in re.finditer(pattern, raw_line):
                s = m.group(1)
                if s.startswith('@') or s.startswith('?'):  # already resource or theme attr
                    continue
                if CHINESE_RE.search(s):
                    results.append({
                        'line': i,
                        'text': s,
                        'attr': attr,
                        'raw': raw_line.strip(),
                        'type': 'xml_attr'
                    })
    return results

def suggest_name(text, context=''):
    # Very simple name suggestion based on text
    s = text.strip()
    s = re.sub(r'[^\u4e00-\u9fff\w]', '_', s)
    s = re.sub(r'_+', '_', s)
    s = s.strip('_')
    # Take first few chinese chars and transliterate roughly by pinyin? Hard.
    # Just use context hints
    if 'setText' in context:
        prefix = 'text'
    elif 'setTitle' in context or 'setTitle' in text:
        prefix = 'title'
    elif 'setMessage' in context:
        prefix = 'msg'
    elif 'Toast' in context:
        prefix = 'toast'
    elif 'setPositiveButton' in context or 'setNegativeButton' in context:
        prefix = 'btn'
    elif 'setHint' in context:
        prefix = 'hint'
    elif 'xml_attr' in context:
        prefix = 'label'
    else:
        prefix = 'str'
    # Use first 4 chinese chars or 10 ascii chars
    chars = re.findall(r'[\u4e00-\u9fff]', s)
    suffix = ''.join(chars[:6]) if chars else s[:12]
    return f"{prefix}_{suffix}".lower()

def main():
    all_occurrences = []
    for f in sorted(JAVA_DIR.rglob("*.java")):
        text = f.read_text(encoding="utf-8")
        for occ in extract_java_strings(text):
            occ['file'] = str(f.relative_to(PROJECT))
            all_occurrences.append(occ)
    for f in sorted(LAYOUT_DIR.rglob("*.xml")):
        text = f.read_text(encoding="utf-8")
        for occ in extract_xml_strings(text, f.name):
            occ['file'] = str(f.relative_to(PROJECT))
            all_occurrences.append(occ)

    # Group by text
    by_text = defaultdict(list)
    for occ in all_occurrences:
        by_text[occ['text']].append(occ)

    entries = []
    for text, occs in sorted(by_text.items(), key=lambda x: x[0]):
        # Determine if dynamic (contains format-like concat in raw lines)
        dynamic = any('" +' in o['raw'] or '+ "' in o['raw'] or '%' in text for o in occs)
        context = occs[0]['raw']
        name = suggest_name(text, context)
        entries.append({
            'name': name,
            'zh': text,
            'dynamic': dynamic,
            'count': len(occs),
            'sample_file': occs[0]['file'],
            'sample_line': occs[0]['line'],
            'sample_context': context,
        })

    data = {
        'total_occurrences': len(all_occurrences),
        'unique_strings': len(entries),
        'strings': entries,
    }
    OUT_JSON.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Extracted {len(all_occurrences)} occurrences, {len(entries)} unique strings")
    print(f"Saved to {OUT_JSON}")

if __name__ == "__main__":
    main()
