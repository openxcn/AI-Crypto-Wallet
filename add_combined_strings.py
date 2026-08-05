#!/usr/bin/env python3
import pathlib, re

PROJECT = pathlib.Path('app/src/main/res')
strings = {
    'values': {
        'msg_cross_chain_risk_normal': '本次将通过【%1$s】完成 %2$s，预估金额 $%3$s。跨链兑换存在桥接风险、滑点和到账延迟，请确认已了解风险。',
        'msg_dapp_connection_prompt': 'DApp 请求连接你的钱包\\n\\n来源：%1$s\\n钱包：%2$s\\n\\n确认后，此DApp将保持连接状态，直到切换钱包。',
        'msg_walletconnect_transaction_details': 'DApp 请求发送交易\\n\\n目标：%1$s...\\n数据：%2$s...',
        'msg_dapp_whitelist_prompt': '是否将以下 DApp 加入 AI 自动操作白名单？\\n\\n域名：%1$s\\n\\n加入后，AI 将可以：\\n- 自动点击页面元素\\n- 自动填写输入框\\n- 执行页面内 JavaScript\\n- 在设定额度内自动确认交易\\n\\n【重要】请确保你信任该 DApp，并设置合理的额度上限。',
        'msg_token_blacklisted_send': '%1$s 已被 AI 标记为高风险代币，已禁止所有交易和授权操作。\\n\\n如需继续操作，请先在代币详情页将该代币加入白名单。\\n\\n⚠️ 加入白名单属于高风险操作，将被记录到风险日志。',
        'msg_token_block_confirm': 'AI 将禁止 %1$s 的所有交易和授权操作。\\n\\n合约地址：%2$s\\n\\n禁止后，该代币的转账、授权等操作将被拦截。',
        'msg_token_whitelist_confirm': '您正在将高风险代币 %1$s 加入白名单。\\n\\n这意味着该代币将绕过 AI 安全检测，可以正常进行交易和授权。\\n\\n⚠️ 该操作存在资金流失风险！\\n⚠️ 该操作将被记录到风险日志中。\\n\\n确定要继续吗？',
        'msg_token_discovery_result': '发现完成：共扫描 %1$s 个代币合约\\n其中 %2$s 个有余额（已自动添加到资产列表）\\n\\n%3$s\\n提示：返回资产页面下拉刷新即可看到新代币',
        'text_no_tokens_with_balance': '（未发现任何有余额的代币）',
    },
    'values-en': {
        'msg_cross_chain_risk_normal': 'This transaction will be completed via [%1$s] for %2$s, estimated amount $%3$s. Cross-chain swaps involve bridge risk, slippage and arrival delays. Please confirm you understand the risks.',
        'msg_dapp_connection_prompt': 'DApp requests to connect to your wallet.\\n\\nSource: %1$s\\nWallet: %2$s\\n\\nAfter confirmation, this DApp will stay connected until you switch wallets.',
        'msg_walletconnect_transaction_details': 'DApp requests to send a transaction.\\n\\nTarget: %1$s...\\nData: %2$s...',
        'msg_dapp_whitelist_prompt': 'Would you like to add this DApp to the AI auto-operation whitelist?\\n\\nDomain: %1$s\\n\\nAfter adding, AI will be able to:\\n- Auto-click page elements\\n- Auto-fill input fields\\n- Execute in-page JavaScript\\n- Auto-confirm transactions within the set limit\\n\\n【Important】Please make sure you trust this DApp and set a reasonable limit.',
        'msg_token_blacklisted_send': '%1$s has been flagged as a high-risk token by AI; all transactions and approvals are blocked.\\n\\nTo continue, please whitelist this token on the token details page first.\\n\\n⚠️ Whitelisting is a high-risk operation and will be recorded in the risk log.',
        'msg_token_block_confirm': 'AI will block all transactions and approvals for %1$s.\\n\\nContract address: %2$s\\n\\nAfter blocking, transfers and approvals for this token will be intercepted.',
        'msg_token_whitelist_confirm': 'You are adding the high-risk token %1$s to the whitelist.\\n\\nThis means the token will bypass AI security checks and can be traded and approved normally.\\n\\n⚠️ This operation carries a risk of fund loss!\\n⚠️ This operation will be recorded in the risk log.\\n\\nDo you want to continue?',
        'msg_token_discovery_result': 'Scan complete: %1$s token contracts scanned\\n%2$s have balances (automatically added to asset list)\\n\\n%3$s\\nTip: Pull down to refresh on the assets page to see new tokens.',
        'text_no_tokens_with_balance': '(No tokens with balance found)',
    },
    'values-ja': {
        'msg_cross_chain_risk_normal': 'この取引は【%1$s】で%2$sを完了し、推定金額 $%3$sです。クロスチェーン兌換にはブリッジリスク、スリッページ、到着遅延があります。リスクを理解した上で確認してください。',
        'msg_dapp_connection_prompt': 'DAppがウォレットへの接続を要求しています。\\n\\n来源：%1$s\\nウォレット：%2$s\\n\\n確認後、このDAppはウォレットを切り替えるまで接続されたままになります。',
        'msg_walletconnect_transaction_details': 'DAppがトランザクション送信を要求しています。\\n\\n対象：%1$s...\\nデータ：%2$s...',
        'msg_dapp_whitelist_prompt': '以下のDAppをAI自動操作ホワイトリストに追加しますか？\\n\\nドメイン：%1$s\\n\\n追加後、AIは以下が可能になります：\\n- ページ要素の自動クリック\\n- 入力欄の自動入力\\n- ページ内JavaScriptの実行\\n- 設定額内でのトランザクション自動確認\\n\\n【重要】このDAppを信頼し、適切な上限を設定してください。',
        'msg_token_blacklisted_send': '%1$sはAIによって高リスクトークンと判定され、すべての取引と承認が禁止されています。\\n\\n続行するには、トークン詳細ページでこのトークンをホワイトリストに追加してください。\\n\\n⚠️ ホワイトリスト追加は高リスク操作であり、リスクログに記録されます。',
        'msg_token_block_confirm': 'AIは%1$sのすべての取引と承認を禁止します。\\n\\nコントラクトアドレス：%2$s\\n\\n禁止後、このトークンの送金や承認は傍受されます。',
        'msg_token_whitelist_confirm': '高リスクトークン %1$s をホワイトリストに追加しようとしています。\\n\\nこれにより、トークンはAIのセキュリティチェックをバイパスし、正常に取引や承認が行えるようになります。\\n\\n⚠️ 資金喪失のリスクがあります！\\n⚠️ この操作はリスクログに記録されます。\\n\\n続行しますか？',
        'msg_token_discovery_result': 'スキャン完了：%1$s 個のトークンコントラクトをスキャン\\n%2$s 個に残高あり（資産リストに自動追加）\\n\\n%3$s\\nヒント：資産ページをプルダウンして更新すると、新しいトークンが表示されます。',
        'text_no_tokens_with_balance': '（残高のあるトークンは見つかりませんでした）',
    },
    'values-de': {
        'msg_cross_chain_risk_normal': 'Diese Transaktion wird über [%1$s] für %2$s abgewickelt, geschätzter Betrag $%3$s. Cross-Chain-Swaps beinhalten Bridge-Risiko, Slippage und Ankunftsverzögerungen. Bitte bestätigen Sie, dass Sie die Risiken verstehen.',
        'msg_dapp_connection_prompt': 'Die DApp möchte sich mit Ihrer Wallet verbinden.\\n\\nQuelle: %1$s\\nWallet: %2$s\\n\\nNach der Bestätigung bleibt diese DApp verbunden, bis Sie die Wallet wechseln.',
        'msg_walletconnect_transaction_details': 'Die DApp möchte eine Transaktion senden.\\n\\nZiel: %1$s...\\nDaten: %2$s...',
        'msg_dapp_whitelist_prompt': 'Möchten Sie diese DApp zur AI-Auto-Operation-Whitelist hinzufügen?\\n\\nDomain: %1$s\\n\\nNach dem Hinzufügen kann die AI:\\n- Seitenelemente automatisch klicken\\n- Eingabefelder automatisch ausfüllen\\n- In-Page-JavaScript ausführen\\n- Transaktionen innerhalb des Limits automatisch bestätigen\\n\\n【Wichtig】Bitte stellen Sie sicher, dass Sie dieser DApp vertrauen, und setzen Sie ein angemessenes Limit.',
        'msg_token_blacklisted_send': '%1$s wurde von der AI als Hochrisiko-Token eingestuft; alle Transaktionen und Genehmigungen sind blockiert.\\n\\nUm fortzufahren, fügen Sie den Token bitte zuerst auf der Token-Detailseite zur Whitelist hinzu.\\n\\n⚠️ Das Hinzufügen zur Whitelist ist ein Hochrisiko-Vorgang und wird im Risikolog protokolliert.',
        'msg_token_block_confirm': 'Die AI wird alle Transaktionen und Genehmigungen für %1$s blockieren.\\n\\nVertragsadresse: %2$s\\n\\nNach dem Blockieren werden Überweisungen und Genehmigungen für diesen Token abgefangen.',
        'msg_token_whitelist_confirm': 'Sie fügen den Hochrisiko-Token %1$s der Whitelist hinzu.\\n\\nDas bedeutet, dass der Token die AI-Sicherheitsprüfung umgeht und normal gehandelt/genehmigt werden kann.\\n\\n⚠️ Dieser Vorgang birgt ein Verlustrisiko!\\n⚠️ Dieser Vorgang wird im Risikolog protokolliert.\\n\\nMöchten Sie fortfahren?',
        'msg_token_discovery_result': 'Scan abgeschlossen: %1$s Token-Verträge gescannt\\n%2$s haben Guthaben (automatisch zur Asset-Liste hinzugefügt)\\n\\n%3$s\\nTipp: Ziehen Sie auf der Asset-Seite nach unten, um neue Token zu sehen.',
        'text_no_tokens_with_balance': '(Keine Token mit Guthaben gefunden)',
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
