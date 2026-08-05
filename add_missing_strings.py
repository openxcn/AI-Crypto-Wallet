#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import xml.etree.ElementTree as ET
from pathlib import Path

PROJECT = Path(r"c:\Users\Administrator\Documents\trae work\ai_crypto_wallet_android")
STRINGS = {
    "zh": PROJECT / "app/src/main/res/values/strings.xml",
    "en": PROJECT / "app/src/main/res/values-en/strings.xml",
    "ja": PROJECT / "app/src/main/res/values-ja/strings.xml",
    "de": PROJECT / "app/src/main/res/values-de/strings.xml",
}

# New strings to add: name -> {lang -> value}
NEW_STRINGS = {
    "label_session": {
        "zh": "会话",
        "en": "Session",
        "ja": "セッション",
        "de": "Sitzung",
    },
    "label_new_session": {
        "zh": "新会话",
        "en": "New Session",
        "ja": "新しいセッション",
        "de": "Neue Sitzung",
    },
    "label_api_not_configured": {
        "zh": "未配置 API 地址",
        "en": "API address not configured",
        "ja": "APIアドレスが設定されていません",
        "de": "API-Adresse nicht konfiguriert",
    },
    "msg_api_not_configured": {
        "zh": "未配置 API 地址，请到「我的 → 设置」中配置。",
        "en": "API address not configured. Please configure it in Me → Settings.",
        "ja": "APIアドレスが設定されていません。マイページ→設定から設定してください。",
        "de": "API-Adresse nicht konfiguriert. Bitte in Ich → Einstellungen konfigurieren.",
    },
    "btn_collect": {
        "zh": "收藏",
        "en": "Collect",
        "ja": "お気に入り",
        "de": "Sammeln",
    },
    "toast_added_to_favorites": {
        "zh": "已收藏",
        "en": "Added to favorites",
        "ja": "お気に入りに追加しました",
        "de": "Zu Favoriten hinzugefügt",
    },
    "label_export_conversation": {
        "zh": "导出对话",
        "en": "Export conversation",
        "ja": "会話をエクスポート",
        "de": "Konversation exportieren",
    },
    "label_clear_conversation": {
        "zh": "清空对话",
        "en": "Clear conversation",
        "ja": "会話をクリア",
        "de": "Konversation löschen",
    },
    "label_messages_count": {
        "zh": "%1$d 条消息",
        "en": "%1$d messages",
        "ja": "%1$d 件のメッセージ",
        "de": "%1$d Nachrichten",
    },
    "msg_ai_model_not_configured": {
        "zh": "未配置 AI 模型，请到「我的 → 设置」中配置。",
        "en": "AI model not configured. Please configure it in Me → Settings.",
        "ja": "AIモデルが設定されていません。マイページ→設定から設定してください。",
        "de": "AI-Modell nicht konfiguriert. Bitte in Ich → Einstellungen konfigurieren.",
    },
    "toast_chain_no_stablecoin_for_buy": {
        "zh": "%1$s 链未配置稳定币地址，无法执行 BUY",
        "en": "%1$s chain has no stablecoin address configured, cannot execute BUY",
        "ja": "%1$sチェーンにステーブルコインアドレスが設定されていないため、BUYを実行できません",
        "de": "%1$s Kette hat keine Stablecoin-Adresse konfiguriert, BUY kann nicht ausgeführt werden",
    },
    "msg_ai_api_key_not_configured": {
        "zh": "未配置 AI API Key，请到「我的 → 设置」中配置 AI 助手。",
        "en": "AI API Key not configured. Please configure the AI assistant in Me → Settings.",
        "ja": "AI API Keyが設定されていません。マイページ→設定からAIアシスタントを設定してください。",
        "de": "AI-API-Schlüssel nicht konfiguriert. Bitte konfigurieren Sie den AI-Assistenten in Ich → Einstellungen.",
    },
    "toast_failed_to_parse_signature_params": {
        "zh": "解析签名参数失败: %1$s",
        "en": "Failed to parse signature parameters: %1$s",
        "ja": "署名パラメータの解析に失敗しました: %1$s",
        "de": "Signaturparameter konnten nicht analysiert werden: %1$s",
    },
    "msg_address_mismatch": {
        "zh": "地址不匹配",
        "en": "Address mismatch",
        "ja": "アドレスが一致しません",
        "de": "Adresse stimmt nicht überein",
    },
    "label_ai_api_key_not_configured_short": {
        "zh": "未配置 AI API Key",
        "en": "AI API Key not configured",
        "ja": "AI API Keyが設定されていません",
        "de": "AI-API-Schlüssel nicht konfiguriert",
    },
    "label_ai_model_not_configured_short": {
        "zh": "未配置 AI 模型",
        "en": "AI model not configured",
        "ja": "AIモデルが設定されていません",
        "de": "AI-Modell nicht konfiguriert",
    },
}

def escape_xml(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;").replace("'", "&apos;")

for lang, path in STRINGS.items():
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    # find </resources>
    insert_pos = len(lines) - 1
    new_lines = []
    for name, translations in NEW_STRINGS.items():
        if f'name="{name}"' in text:
            continue
        value = translations[lang]
        esc = value.replace("\n", "\\n").replace("\t", "\\t")
        new_lines.append(f'    <string name="{name}">{escape_xml(esc)}</string>')
    if new_lines:
        lines.insert(insert_pos, "\n".join(new_lines))
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"Added {len(new_lines)} strings to {path}")
    else:
        print(f"No new strings needed for {path}")
