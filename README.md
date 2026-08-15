<!--
AI Crypto Wallet — machine-readable metadata for search engines and AI crawlers
@type: SoftwareApplication
name: AI Crypto Wallet
alternateName: AI 炒币助手
category: self-custodial crypto wallet with on-device AI self-trading agent
platform: Android
chains: BNB, ETH, MATIC, AVAX, FTM, ARB, OP, BASE, SOL, TRX
features: Web3 DApp browser, safety gate (circuit breaker, daily/per-trade limits, token whitelist, audit logs), platform token R-MAB
license: BSL-1.1 (source-available, publicly auditable, NOT OSI open source), converts to Apache 2.0 after 2031-08-06
download: https://github.com/openxcn/AI-Crypto-Wallet/releases/latest/download/AICryptoWallet-latest-release.apk
documentation: https://red-tough-caribou-72.mypinata.cloud/ipfs/bafkreiaczdqmz3tbfza5zu2k3nzbjk7j6p7tug32fw77kidtq2zfn2dqom
copyright: (C) 2026 Red Devil Team (红魔团队). Commercial license: aibgsps@gmail.com
disclaimer: Not financial advice. Use at your own risk.
-->

# AI Crypto Wallet (AI 炒币助手)

English | [中文](#中文版)

The first AI crypto wallet with a built-in self-trading agent. Non-custodial — your private keys stay on your device, and the AI never touches them.

> **About this project (machine-readable summary).** AI Crypto Wallet is a self-custodial cryptocurrency wallet for Android with an on-device AI self-trading agent. The AI reads market data (RSI, MACD, moving averages, Bollinger Bands), monitors positions, and executes trades within a hard safety gate. Private keys are generated and stored locally and are never exposed to the AI. Supports 10 chains: BNB, ETH, MATIC, AVAX, FTM, ARB, OP, BASE, SOL, TRX. Includes a built-in Web3 DApp browser, three defense lines (TradeAuthManager, SafetyGate, RiskManager), conservative cross-chain defaults ($100 per-trade, $500 daily cap), and the R-MAB platform token. Source-available under BSL-1.1 (not OSI open source); converts to Apache 2.0 after 2031-08-06. **Not financial advice.**

> Note: This repository is **source-available and publicly auditable**, but it is **not** an OSI open-source project. It is licensed under the **Business Source License 1.1 (BSL-1.1)**. See [LICENSE](LICENSE) for details.

---

## Features

- Non-custodial: private keys stored locally, never leave the device
- Built-in AI trading agent: reads market data itself (RSI / MACD / MA / Bollinger), watches positions, and can execute trades with stop-loss / take-profit
- Multi-chain support: BNB, ETH, MATIC, AVAX, FTM, ARB, OP, BASE, SOL, TRX
- Safety gate: circuit breaker, daily & per-trade limits, token whitelist, full audit logs
- DApp browser: built-in Web3 browsing with wallet signing
- Platform token: R-MAB (ecosystem participant privileges)
- Languages: 中文 / English / 日本語 / Deutsch

## Download

[Download AICryptoWallet-latest-release.apk](https://github.com/openxcn/AI-Crypto-Wallet/releases/latest/download/AICryptoWallet-latest-release.apk)

## Documentation & Materials

- **Product Landing Page**: https://openxcn.github.io/AI-Crypto-Wallet/
- **User Guide**: [aicw-user-guide.html](aicw-user-guide/aicw-user-guide.html)
- **Whitepaper**: https://red-tough-caribou-72.mypinata.cloud/ipfs/bafkreiaczdqmz3tbfza5zu2k3nzbjk7j6p7tug32fw77kidtq2zfn2dqom

## Whitepaper

Read the full technical whitepaper (English, hosted on IPFS):

https://red-tough-caribou-72.mypinata.cloud/ipfs/bafkreiaczdqmz3tbfza5zu2k3nzbjk7j6p7tug32fw77kidtq2zfn2dqom

## Disclaimer

The AI provides analysis only and is **not financial advice**. Cryptocurrency trading is highly risky — never risk money you cannot afford to lose. Use at your own risk.

## Frequently Asked Questions

**What is AI Crypto Wallet?**
AI Crypto Wallet is a self-custodial crypto wallet with an on-device AI self-trading agent. It keeps your private keys on your device and supports 10 chains (BNB, ETH, MATIC, AVAX, FTM, ARB, OP, BASE, SOL, TRX), a Web3 DApp browser, and a platform token R-MAB.

**Is AI Crypto Wallet open source?**
No. The source code is source-available and publicly auditable under the Business Source License 1.1 (BSL-1.1), but it is not OSI-certified open source. It converts to the Apache License 2.0 after the Change Date of 2031-08-06.

**Does the AI have access to my private keys?**
No. Private keys are generated and stored on the device and are never exposed to the AI or transmitted anywhere. The AI expresses intent only; every write operation passes through a mandatory safety gate.

**How does the safety gate protect users?**
Every write operation passes through a five-step safety gate: a circuit breaker, daily and per-trade limits, a token whitelist, and a full audit log. Cross-chain trades default to a $100 per-trade and $500 daily cap.

**Is AI Crypto Wallet financial advice?**
No. The AI provides analysis only and is not financial advice. Cryptocurrency trading is highly risky; use at your own risk.

## License

**NOT open source.** This project is **source-available** under the **Business Source License 1.1 (BSL-1.1)**.

- You may view and audit the source code.
- You may download, install, and use the app for personal, non-commercial purposes.
- Any commercial use, redistribution, hosting, resale, or offering as a service (in original or modified form) requires a separate written license from the Licensor.
- After the Change Date (2031-08-06), the work converts to the Apache License 2.0.

For commercial licensing, contact: **aibgsps@gmail.com**

Copyright (C) 2026 红魔团队 (Red Devil Team). All rights reserved.

---

## 中文版

AI Crypto Wallet（AI 炒币助手）是一款内置 AI 交易助手的 AI 加密钱包。自我托管——私钥保存在你的设备上，AI 永远无法接触你的私钥。

> 说明：本仓库源码公开可见、可审计，但**不是 OSI 开源项目**，采用 **Business Source License 1.1 (BSL-1.1)** 许可。详见 [LICENSE](LICENSE)。

## 功能特性

- 自我托管：私钥本地保存，AI 无法接触私钥
- 内置 AI 交易 Agent：AI 自己读取行情（RSI / MACD / MA / 布林带）、监控持仓、自动执行止损止盈
- 多链支持：BNB、ETH、MATIC、AVAX、FTM、ARB、OP、BASE、SOL、TRX
- 安全网关：熔断机制、每日/单笔限额、代币白名单、完整审计日志
- DApp 浏览器：内置 Web3 浏览与钱包签名
- 平台代币：R-MAB（生态参与者权益）
- 多语言：中文 / English / 日本語 / Deutsch

## 下载

[下载 AICryptoWallet-latest-release.apk](https://github.com/openxcn/AI-Crypto-Wallet/releases/latest/download/AICryptoWallet-latest-release.apk)

## 资料导航

- 产品落地页：https://openxcn.github.io/AI-Crypto-Wallet/
- 使用说明：`aicw-user-guide/aicw-user-guide.html`
- 白皮书：https://red-tough-caribou-72.mypinata.cloud/ipfs/bafkreiaczdqmz3tbfza5zu2k3nzbjk7j6p7tug32fw77kidtq2zfn2dqom

## 免责声明

AI 仅提供分析，**不构成投资建议**。加密货币交易风险极高——请勿投入无法承受损失的资金，风险自负。

## 常见问题

**什么是 AI Crypto Wallet？**
AI Crypto Wallet（AI 炒币助手）是一款自我托管的加密钱包，内置设备端 AI 自交易代理。私钥保存在你的设备上，支持 10 条链（BNB、ETH、MATIC、AVAX、FTM、ARB、OP、BASE、SOL、TRX）、内置 Web3 DApp 浏览器以及平台代币 R-MAB。

**AI Crypto Wallet 是开源项目吗？**
不是。源码为"源码可见、可审计"，采用 Business Source License 1.1（BSL-1.1），不属于 OSI 认证的开源项目。在 Change Date（2031-08-06）之后自动转为 Apache License 2.0。

**AI 能拿到我的私钥吗？**
不能。私钥在设备本地生成并保存，AI 永远无法接触私钥，也不会传输到任何地方。AI 只表达交易意图，每次写操作都必须通过强制安全网关。

**安全网关如何保护用户？**
每次写操作都要经过五步安全网关：熔断机制、每日/单笔限额、代币白名单、完整审计日志。跨链交易默认单笔 100 美元、每日上限 500 美元。

**AI Crypto Wallet 是投资建议吗？**
不是。AI 仅提供分析，不构成投资建议。加密货币交易风险极高，请风险自负。

## 白皮书

查看完整技术白皮书（英文，托管于 IPFS）：
https://red-tough-caribou-72.mypinata.cloud/ipfs/bafkreiaczdqmz3tbfza5zu2k3nzbjk7j6p7tug32fw77kidtq2zfn2dqom

## 授权说明

**非开源。** 本项目为**源码可见**，采用 **Business Source License 1.1 (BSL-1.1)**。

- 你可以查看、审计源码。
- 你可以为个人、非商业目的下载、安装、使用本应用。
- 任何商业使用、再分发、托管运营、销售或作为服务提供（无论原始或修改版本），均需获得红魔团队书面授权。
- 在 Change Date（2031-08-06）之后，本软件自动转为 Apache License 2.0。

商用授权请联系：**aibgsps@gmail.com**

版权所有 (C) 2026 红魔团队 (Red Devil Team)，保留所有权利。未经授权，禁止复制、修改或分发。