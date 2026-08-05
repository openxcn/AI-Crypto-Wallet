#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
I18N processor: extract hardcoded Chinese user-facing strings from Java and XML,
generate translations for zh/en/ja/de, update strings.xml, and produce a replacement map.
Translates one-by-one with retry. Updates translations even for existing keys when
fallback (translation == Chinese source) is detected.
"""
import os
import re
import json
import time
import xml.etree.ElementTree as ET
from collections import OrderedDict
from googletrans import Translator

PROJECT_ROOT = r"c:\Users\Administrator\Documents\trae work\ai_crypto_wallet_android"
LAYOUT_ROOT = os.path.join(PROJECT_ROOT, r"app\src\main\res\layout")
VALUES_DIRS = {
    "zh": os.path.join(PROJECT_ROOT, r"app\src\main\res\values\strings.xml"),
    "en": os.path.join(PROJECT_ROOT, r"app\src\main\res\values-en\strings.xml"),
    "ja": os.path.join(PROJECT_ROOT, r"app\src\main\res\values-ja\strings.xml"),
    "de": os.path.join(PROJECT_ROOT, r"app\src\main\res\values-de\strings.xml"),
}
USER_FACING_JSON = os.path.join(PROJECT_ROOT, "user_facing_texts.json")
OUTPUT_MAP = os.path.join(PROJECT_ROOT, "i18n_replacement_map.json")
TRANSLATION_CACHE = os.path.join(PROJECT_ROOT, "i18n_translation_cache.json")

translator = Translator()

def has_chinese(s):
    return bool(re.search(r'[\u4e00-\u9fff]', s))

def parse_strings_xml(path):
    data = OrderedDict()
    if not os.path.exists(path):
        return data
    tree = ET.parse(path)
    root = tree.getroot()
    for child in root:
        if child.tag == "string" and "name" in child.attrib:
            data[child.attrib["name"]] = child.text or ""
    return data

def write_strings_xml(path, data):
    lines = ['<?xml version="1.0" encoding="utf-8"?>\n', '<resources>\n']
    for name, value in data.items():
        escaped = escape_xml(value)
        lines.append(f'    <string name="{name}">{escaped}</string>\n')
    lines.append('</resources>\n')
    with open(path, 'w', encoding='utf-8') as f:
        f.writelines(lines)

def escape_xml(s):
    s = s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    s = s.replace('"', '&quot;')
    return s

def find_existing_key_by_value(data, value):
    for k, v in data.items():
        if v == value:
            return k
    return None

def classify_prefix(text, is_hint=False, is_button=False):
    t = text.strip()
    if is_hint:
        return "hint_"
    if is_button or len(t) <= 6:
        if any(x in t for x in ['保存', '确定', '确认', '取消', '删除', '复制', '分享', '展开', '收起', '切换', '添加', '创建', '导入', '启动', '停止', '验证', '连接', '签名', '授权', '收藏', '取消收藏', '拒绝', '退出', '关闭', '隐藏']):
            return "btn_"
    if any(x in t for x in ['失败', '错误', '异常', '无效', '无法', '不能', '不存在', '已复制', '已保存', '已删除', '已添加', '已切换', '已禁止', '已启动', '已停止', '已清空', '已导出', '已取消', '已激活', '已置顶', '已隐藏', '已恢复', '已填入', '已选择', '已粘贴', '已收藏', '已显示', '已归档']):
        return "toast_"
    if any(x in t for x in ['请输入', '请填写', '请选择', '请先', '请确认', '请妥善', '请在']):
        return "toast_"
    if '？' in t or '?' in t or '是否' in t or '确定要' in t or ('确认' in t and ('吗' in t or '？' in t)):
        return "msg_"
    if '提示' in t or '说明' in t or '警告' in t:
        return "title_"
    return "label_"

def slugify(text):
    en = re.sub(r'[^\w\s]', ' ', text)
    en = re.sub(r'\s+', '_', en.strip())
    en = en.lower()
    en = re.sub(r'_+', '_', en).strip('_')
    return en or "text"

def translate_one(text, dest, cache, retries=3):
    key = f"{dest}:{text}"
    if key in cache:
        return cache[key]
    for attempt in range(retries):
        try:
            result = translator.translate(text, dest=dest, src='zh-cn').text
            cache[key] = result
            return result
        except Exception as e:
            print(f"  translate {dest} attempt {attempt+1} failed: {e}")
            time.sleep(1.5 ** attempt)
    cache[key] = text
    return text

def scan_xml_layouts():
    results = []
    for dp, dn, fn in os.walk(LAYOUT_ROOT):
        for f in fn:
            if not f.endswith('.xml'):
                continue
            path = os.path.join(dp, f)
            try:
                tree = ET.parse(path)
                root = tree.getroot()
                for elem in root.iter():
                    for attr in ['text', 'hint']:
                        val = elem.attrib.get(f'android:{attr}')
                        if val and has_chinese(val):
                            results.append({
                                'file': f,
                                'attr': f'android:{attr}',
                                'text': val,
                            })
            except Exception as e:
                print(f"parse error {path}: {e}")
    return results

def main():
    cache = {}
    if os.path.exists(TRANSLATION_CACHE):
        try:
            with open(TRANSLATION_CACHE, encoding='utf-8') as f:
                cache = json.load(f)
        except Exception:
            cache = {}

    existing = {lang: parse_strings_xml(path) for lang, path in VALUES_DIRS.items()}
    zh_existing = existing['zh']

    with open(USER_FACING_JSON, encoding='utf-8') as f:
        java_candidates = json.load(f)

    xml_candidates = scan_xml_layouts()

    unique_texts = OrderedDict()

    for item in java_candidates:
        text = item['text']
        if not has_chinese(text):
            continue
        if text not in unique_texts:
            unique_texts[text] = {'sources': [], 'type': 'java'}
        for occ in item.get('occurrences', []):
            unique_texts[text]['sources'].append(f"{occ[0]}:{occ[1]}")

    for item in xml_candidates:
        text = item['text']
        if not has_chinese(text):
            continue
        if text not in unique_texts:
            unique_texts[text] = {'sources': [], 'type': 'xml', 'attr': item['attr']}
        unique_texts[text]['sources'].append(f"{item['file']}")

    texts = list(unique_texts.keys())
    print(f"Found {len(texts)} unique Chinese strings. Translating...")

    en_map, ja_map, de_map = {}, {}, {}
    for i, text in enumerate(texts, 1):
        print(f"  [{i}/{len(texts)}] {text[:40]}...")
        en_map[text] = translate_one(text, 'en', cache)
        ja_map[text] = translate_one(text, 'ja', cache)
        de_map[text] = translate_one(text, 'de', cache)
        time.sleep(0.3)

    with open(TRANSLATION_CACHE, 'w', encoding='utf-8') as f:
        json.dump(cache, f, ensure_ascii=False, indent=2)

    replacement_map = OrderedDict()
    new_strings_by_lang = {lang: OrderedDict() for lang in VALUES_DIRS}
    update_map = OrderedDict()  # key -> {en, ja, de} for existing keys needing translation update

    for text in texts:
        meta = unique_texts[text]
        key = find_existing_key_by_value(zh_existing, text)

        is_hint = meta.get('type') == 'xml' and 'hint' in meta.get('attr', '')
        is_button = meta.get('type') == 'xml' and 'text' in meta.get('attr', '')
        prefix = classify_prefix(text, is_hint, is_button)

        en_text = en_map.get(text, text)
        ja_text = ja_map.get(text, text)
        de_text = de_map.get(text, text)

        if key:
            replacement_map[text] = key
            # Update fallback translations (where translated value equals Chinese source)
            needs_update = False
            for lang, tr in [('en', en_text), ('ja', ja_text), ('de', de_text)]:
                if existing[lang].get(key) == text:
                    needs_update = True
                    break
            if needs_update:
                update_map[key] = {'en': en_text, 'ja': ja_text, 'de': de_text}
            continue

        base_key = f"{prefix}{slugify(en_text)}"
        key = base_key
        counter = 2
        while key in zh_existing or key in new_strings_by_lang['zh']:
            key = f"{base_key}_{counter}"
            counter += 1

        replacement_map[text] = key
        new_strings_by_lang['zh'][key] = text
        new_strings_by_lang['en'][key] = en_text
        new_strings_by_lang['ja'][key] = ja_text
        new_strings_by_lang['de'][key] = de_text

    for lang, path in VALUES_DIRS.items():
        data = existing[lang].copy()
        data.update(new_strings_by_lang[lang])
        for key, translations in update_map.items():
            if lang != 'zh' and key in data:
                data[key] = translations[lang]
        write_strings_xml(path, data)

    with open(OUTPUT_MAP, 'w', encoding='utf-8') as f:
        json.dump(replacement_map, f, ensure_ascii=False, indent=2)

    print(f"Processed {len(texts)} unique Chinese strings.")
    print(f"New strings added: {len(new_strings_by_lang['zh'])}")
    print(f"Existing translations updated: {len(update_map)}")
    print(f"Replacement map saved to {OUTPUT_MAP}")

if __name__ == "__main__":
    main()
