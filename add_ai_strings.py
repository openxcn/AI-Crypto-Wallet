#!/usr/bin/env python3
import pathlib, re

PROJECT = pathlib.Path('app/src/main/res')
strings = {
    'values': {
        'msg_ai_trading_instructions': '对话功能随时可用，无需启用自动交易\\n自动买卖、调用 DeFi 需要手动启用\\n启用条件：主流币资产 ≥ $200，或持有 R-MAB ≥ 20000 个\\n原因：数字货币涨跌幅太大，需要用户手动确认开启\\n\\n当前自动交易状态：%1$s',
        'str_enabled': '已启用 ✅',
        'str_disabled': '未启用 ⛔',
        'str_enable_auto_trade': '启用自动交易',
        'str_disable_auto_trade': '关闭自动交易',
    },
    'values-en': {
        'msg_ai_trading_instructions': 'Chat is always available without enabling auto-trading.\\nAuto buy/sell and DeFi calls require manual activation.\\nActivation condition: mainstream assets ≥ $200, or hold ≥ 20000 R-MAB.\\nReason: Crypto volatility is high, manual confirmation is required.\\n\\nCurrent auto-trading status: %1$s',
        'str_enabled': 'Enabled ✅',
        'str_disabled': 'Disabled ⛔',
        'str_enable_auto_trade': 'Enable Auto Trading',
        'str_disable_auto_trade': 'Disable Auto Trading',
    },
    'values-ja': {
        'msg_ai_trading_instructions': 'チャット機能は自動取引を有効にしなくてもいつでも利用できます。\\n自動売買とDeFi呼び出しは手動で有効化が必要です。\\n有効化条件：主流資産 ≥ $200、またはR-MAB ≥ 20000個を保有\\n理由：暗号資産の変動が大きいため、手動での確認が必要です。\\n\\n現在の自動取引状態：%1$s',
        'str_enabled': '有効 ✅',
        'str_disabled': '無効 ⛔',
        'str_enable_auto_trade': '自動取引を有効にする',
        'str_disable_auto_trade': '自動取引を無効にする',
    },
    'values-de': {
        'msg_ai_trading_instructions': 'Der Chat ist jederzeit verfügbar, ohne automatischen Handel zu aktivieren.\\nAutomatischer Kauf/Verkauf und DeFi-Aufrufe müssen manuell aktiviert werden.\\nAktivierungsbedingung: Mainstream-Vermögen ≥ $200 oder ≥ 20000 R-MAB halten.\\nGrund: Krypto ist sehr volatil, manuelle Bestätigung erforderlich.\\n\\nAktueller Status des automatischen Handels: %1$s',
        'str_enabled': 'Aktiviert ✅',
        'str_disabled': 'Deaktiviert ⛔',
        'str_enable_auto_trade': 'Automatischen Handel aktivieren',
        'str_disable_auto_trade': 'Automatischen Handel deaktivieren',
    },
}

for lang, items in strings.items():
    path = PROJECT / lang / 'strings.xml'
    text = path.read_text(encoding='utf-8')
    additions = '\n'.join(f'    <string name="{k}">{v}</string>' for k, v in items.items())
    if all(f'name="{k}"' in text for k in items):
        print(f'{lang}: already present, skip')
        continue
    text = re.sub(r'\n</resources>', '\n' + additions + '\n</resources>', text, count=1)
    path.write_text(text, encoding='utf-8')
    print(f'{lang}: added {len(items)} strings')
