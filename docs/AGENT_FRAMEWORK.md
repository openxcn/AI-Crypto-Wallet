# AI 炒币助手 — 智能体框架设计文档

> 借鉴 Trae IDE 的 Agent 架构，为 AI Crypto Wallet 设计的完整智能体框架。
> 版本: v1.0 | 最后更新: 2026-07-20

---

## 一、框架总览

```
┌─────────────────────────────────────────────────────────┐
│                    用户交互层 (UI)                        │
│  HomeActivity / AIAgentActivity / ChatInterface         │
├─────────────────────────────────────────────────────────┤
│                   智能体核心层 (Core)                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────────┐  │
│  │ Agent    │  │ Agent    │  │ AgentMemory          │  │
│  │ Runtime  │──│ Tool     │  │ (自述文件/记忆)      │  │
│  │ (思考循环)│  │ Registry │  │                      │  │
│  ──────────┘  │ (工具集) │  └──────────────────────  │
│                └──────────┘                             │
├─────────────────────────────────────────────────────────┤
│                   安全与风控层 (Safety)                   │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────────┐  │
│  │ Safety   │  │ Risk     │  │ TradeAuthManager     │  │
│  │ Gate     │  │ Manager  │  │ (启动条件/白名单)    │  │
│  └──────────┘  └──────────┘  └──────────────────────┘  │
─────────────────────────────────────────────────────────┤
│                   交易执行层 (Trading)                    │
│  ┌──────────┐  ──────────┐  ┌──────────────────────┐  │
│  │ Strategy │  │ DexTrader│  │ PositionManager      │  │
│  │ Engine   │  │ (DEX执行)│  │ (持仓管理)           │  │
│  └──────────┘  └──────────┘  └──────────────────────┘  │
├─────────────────────────────────────────────────────────┤
│                   数据与链上层 (Data)                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────────┐  │
│  │ ChainAPI │  │ Multi    │  │ NodeManager          │  │
│  │ (RPC)    │  │ Chain    │  │ (节点切换/重试)      │  │
│  │          │  │ Market   │  │                      │  │
│  └──────────┘  ──────────┘  └──────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

---

## 二、核心设计原则

### 2.1 借鉴 Trae 的设计模式

| Trae 概念 | 本项目对应 | 说明 |
|-----------|-----------|------|
| SKILL.md (技能定义) | AgentToolRegistry (工具注册表) | 声明 AI 能做什么 |
| System Prompt (系统提示) | AgentMemory.toSystemPrompt() | 告诉 AI 它是谁 |
| Memory (记忆系统) | AgentMemory (agent_memory.json) | AI 的自述文件 |
| Tool Use (工具调用) | AgentRuntime 循环 | 思考→行动→观察 |
| Permission (权限控制) | SafetyGate + TradeAuthManager | 安全门控 |
| Lessons Learned (经验) | project_memory.md | 踩坑记录 |

### 2.2 核心原则

1. **证据驱动**：AI 决策必须基于链上数据和技术指标，不凭感觉
2. **安全优先**：所有写入操作必须经过 SafetyGate，熔断时拒绝一切交易
3. **用户可控**：自动交易必须由用户手动开启/关闭，AI 不能自行启动
4. **白名单机制**：非白名单代币必须用户确认，60秒超时自动拒绝
5. **脱敏存储**：API Key 脱敏，钱包地址实时获取不存储
6. **可审计**：所有工具调用记录完整日志，可追溯

---

## 三、智能体核心层

### 3.1 AgentRuntime — 思考循环引擎

```
用户意图/定时触发
       │
       ▼
  ┌─────────────┐
  │ 构建上下文    │ ← AgentMemory (身份/性格/配置)
  │ + 系统提示词  │ ← 市场数据/持仓/安全状态
  ─────────────┘
       │
       ▼
  ┌─────────────┐
  │ 调用 LLM     │ ← OpenAI / Claude 兼容
  └─────────────┘
       │
       ├──→ 有工具调用？ ──Yes──→ 执行工具 ──→ 结果注入上下文 ──→ 回到 LLM
       │                                    (最多 maxRounds=8 轮)
       │
       No
       ▼
  ┌─────────────┐
  │ 返回最终回复  │ ← 分析结论 / 交易决策 / 聊天回答
  └─────────────┘
```

**关键参数**：
- `maxRounds = 8`：防止无限循环（足够分析+1-2笔交易）
- `connectTimeout = 10s`，`readTimeout = 60s`：LLM 调用可能较慢
- 支持 OpenAI function calling 和 Claude tool use 两种协议

### 3.2 AgentToolRegistry — 工具集

**只读工具**（不消耗 gas，不需要 SafetyGate）：

| 工具名 | 功能 | 输入 | 输出 |
|--------|------|------|------|
| `get_wallet_address` | 查询钱包地址 | chain | address |
| `get_native_balance` | 原生币余额 | chain | balance + USD价值 |
| `get_token_balance` | ERC20余额 | chain, contract | balance |
| `get_token_price` | 代币价格 | symbol | price_usd |
| `get_position` | 当前持仓 | - | 持仓列表+盈亏 |
| `get_market_data` | K线技术指标 | chain, cycle | RSI/MACD/MA/BB |
| `get_safety_status` | 安全网关状态 | - | 熔断/限额/错误率 |
| `call_contract_read` | 任意只读合约调用 | chain, contract, function | hex结果 |

**写入工具**（消耗 gas，必须经过 SafetyGate）：

| 工具名 | 功能 | 安全级别 |
|--------|------|---------|
| `call_contract_write` | 任意写入合约调用 | 高（需审计描述） |
| `swap_tokens` | DEX 代币兑换 | 高（白名单+限额） |
| `approve_token` | ERC20 授权 | 中 |
| `send_native` | 原生币转账 | 高（需确认地址） |

### 3.3 AgentMemory — 智能体记忆（自述文件）

**存储位置**：`Android/data/com.aicryptowallet.app/files/agent_workspace/agent_memory.json`

**记忆字段**：

```json
{
  "aiName": "AI 交易助手",
  "ownerName": "主人",
  "personality": "沉稳理性，分析数据后给出建议",
  "checkIntervalMinutes": 5,
  "maxDailyLoss": 50.0,
  "newsReportIntervalHours": 24,
  "tradingChain": "BNB",
  "apiKeyMasked": "sk-****abcd",
  "customNotes": "",
  "createdAt": 1721484000000,
  "updatedAt": 1721484000000
}
```

**自修改机制**：AI 通过 `@SET 字段名=新值` 指令修改自身配置
- 支持的字段：aiName, ownerName, personality, checkIntervalMinutes, maxDailyLoss, newsReportIntervalHours, tradingChain, customNotes
- 范围校验：checkIntervalMinutes(1-60), maxDailyLoss(0-10000), newsReportIntervalHours(1-168)
- 钱包地址不存储，实时从 WalletManager 获取

---

## 四、安全与风控层

### 4.1 SafetyGate — 安全网关

```
写入工具调用
     │
     ▼
┌──────────────────┐
│ 1. 熔断检查       │ ← 连续亏损/高错误率 → 熔断 N 分钟
├──────────────────┤
│ 2. 每日限额检查   │ ← 超过 maxDailyLoss → 拒绝
├──────────────────┤
│ 3. 单笔限额检查   │ ← 超过 maxTradeAmount → 拒绝
──────────────────┤
│ 4. 白名单检查     │ ← 非白名单代币 → 弹窗确认
├──────────────────┤
│ 5. 操作描述审计   │ ← 必须清晰说明意图
└──────────────────┘
     │
     ├── 通过 → 执行交易 → onTradeSuccess()
     │
     └── 拒绝 → 返回错误原因 → AI 调整策略
```

**熔断规则**：
- 连续亏损 3 笔 → 熔断 30 分钟
- 日错误率 > 50% → 熔断 60 分钟
- 熔断期间所有写入工具拒绝（保护性平仓除外）

### 4.2 TradeAuthManager — 启动条件

AI 交易启动需满足以下任一条件：
- 主流币资产 ≥ $200
- 持有 R-MAB ≥ 20000 个

检查在后台线程执行（`checkInBackground`），避免主线程网络请求阻塞。

### 4.3 RiskManager — 风控管理

- 仓位大小限制
- 止损/止盈自动触发
- 滑点保护（默认 3%）
- 每日交易笔数上限

---

## 五、交易执行层

### 5.1 StrategyEngine — 策略引擎

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│ RSIStrategy │     │ MACDStrategy│     │ MAStrategy  │
│ (超买超卖)   │     │ (趋势确认)   │     │ (均线交叉)   │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                   │
       └───────────────────┼───────────────────┘
                           ▼
                    ┌─────────────┐
                    │ AIAnalyzer  │
                    │ (综合决策)   │
                    └──────┬──────┘
                           ▼
                    ┌─────────────┐
                    │ TradingSignal│
                    │ BUY/SELL/HOLD│
                    └─────────────┘
```

**分析周期**：
- 主周期：用户配置（默认 1h）
- 辅助周期：4h（趋势确认）、15m（入场时机）

### 5.2 DexTrader — DEX 执行器

- 原生币 ↔ USDT 兑换
- USDT ↔ 代币兑换
- 代币 ↔ 代币兑换
- 自动处理 approve
- 滑点保护 3%

### 5.3 PositionManager — 持仓管理

- 记录每笔持仓（代币、数量、成本价、止损价、止盈价）
- 实时计算未实现盈亏
- 止损/止盈自动触发卖出
- SharedPreferences 持久化

---

## 六、数据与链上层

### 6.1 ChainAPI — RPC 接口

- 多链支持：BNB/ETH/MATIC/AVAX/FTM/ARB/OP/BASE/SOL/TRX
- 节点管理：主节点 + 备用节点自动切换
- 批量查询优化：`getAllTokenBalances` 一次查询所有代币
- 重试机制：连接超时 3s 快速失败，自动回退备用节点

### 6.2 NodeManager — 节点管理

- 节点健康检查
- 延迟监控
- 自动切换最优节点
- BSC 使用 AVE 自建代理节点（延迟 ~200ms）

### 6.3 MultiChainMarketData — 市场数据

- K 线数据获取（15m/1h/4h/1d）
- 技术指标计算（RSI/MACD/MA/布林带）
- 价格缓存（减少重复请求）

---

## 七、系统提示词模板

AI 每次被调用时，系统提示词由以下部分组成：

```
【你的身份与记忆】          ← AgentMemory.toSystemPrompt()
- 名字、主人、性格、配置...

【当前市场状态】            ← 实时注入
- 当前链、价格、持仓、技术指标...

【安全规则】                ← 固定规则
- 所有写入操作必须经过 SafetyGate
- 非白名单代币必须用户确认
- 熔断期间拒绝交易
- 不暴露私钥/助记词

【工具使用指南】            ← AgentToolRegistry 自动生成
- 每个工具的名称、描述、参数...

【你可以修改自己的设置】    ← AgentMemory 自修改说明
- @SET 指令格式...
```

---

## 八、文件结构

```
com.aicryptowallet.app/
├── 智能体核心层
│   ├── AIAgentActivity.java        # AI 交易界面（详情/聊天切换）
│   ├── AgentRuntime.java           # 思考循环引擎
│   ├── AgentToolRegistry.java      # 工具注册表（12个工具）
│   ├── AgentMemory.java            # 智能体记忆（自述文件）
│   └── AIAnalyzer.java             # AI 分析器（LLM调用封装）
│
├── 安全与风控层
│   ├── SafetyGate.java             # 安全网关（熔断/限额/白名单）
│   ├── RiskManager.java            # 风控管理（仓位/止损/滑点）
│   └── TradeAuthManager.java       # 启动条件（资产检查）
│
├── 交易执行层
│   ├── StrategyEngine.java         # 策略引擎（多策略综合）
│   ├── RSIStrategy.java            # RSI 策略
│   ├── MACDStrategy.java           # MACD 策略
│   ├── MAStrategy.java             # 均线策略
│   ├── DexTrader.java              # DEX 执行器
│   ├── PositionManager.java        # 持仓管理
│   ├── PositionMonitor.java        # 持仓监控（止损止盈）
│   ├── TradingSignal.java          # 交易信号（BUY/SELL/HOLD）
│   ├── TradingStrategy.java        # 策略基类
│   ├── TradeRecord.java            # 交易记录
│   ├── TradeFeeManager.java        # 手续费管理
│   └── TradingCycleConfig.java     # 分析周期配置
│
├── 数据与链上层
│   ├── ChainAPI.java               # RPC 接口（多链）
│   ├── MultiChainMarketData.java   # 多链市场数据
│   ├── MarketData.java             # 市场数据模型
│   ├── TechnicalIndicators.java    # 技术指标计算
│   ├── NodeManager.java            # 节点管理
│   ├── ContractCaller.java         # 合约调用器
│   ├── SmartTokenUtils.java        # 智能代币工具
│   ├── TokenAutoDiscovery.java     # 代币自动发现
│   └── WalletManager.java          # 钱包管理
│
└── 应用基础
    ├── HomeActivity.java           # 主页（资产/行情/交易/发现/我的）
    ├── MainActivity.java           # 启动页
    ├── SetupActivity.java          # 钱包创建/导入
    ├── AppConfig.java              # 全局配置
    ├── Logger.java                 # 日志系统
    ── CryptoWalletApplication.java # Application
```

---

## 九、关键流程

### 9.1 AI 自动交易流程

```
定时触发（每 N 分钟）
       │
       ▼
  TradeAuthManager.checkAsync()     ← 检查启动条件
       │
       ├── 不满足 → 提示用户加仓
       │
       ▼ 满足
  SafetyGate.isCircuitBroken()      ← 检查熔断
       │
       ├── 已熔断 → 跳过本轮，等待解除
       │
       ▼ 未熔断
  AgentRuntime.run()                ← 启动思考循环
       │
       ├── LLM 分析市场数据
       ├── 调用 get_market_data 获取指标
       ├── 调用 get_position 查看持仓
       ├── 调用 get_safety_status 确认安全
       ├── 决策：BUY / SELL / HOLD
       │
       ├── BUY → swap_tokens → SafetyGate校验 → 执行
       ├── SELL → swap_tokens → 执行
       └── HOLD → 记录分析结论
       │
       ▼
  更新 UI（信号/盈亏/交易笔数）
       │
       ▼
  保存状态（isRunning/autoTradeEnabled）
```

### 9.2 用户对话流程

```
用户输入消息
       │
       ▼
  appendChatMessage("user", msg)
       │
       ▼
  显示"思考中..."
       │
       ▼
  后台线程调用 LLM
  (系统提示词注入 AgentMemory + 市场状态)
       │
       ▼
  解析回复中的 @SET 指令
       │
       ├── 有 @SET → applySetCommand() → 保存到记忆文件
       │
       ▼
  过滤 @SET 指令，显示自然语言回复
       │
       ▼
  appendChatMessage("assistant", reply)
```

---

## 十、扩展方向

### 10.1 短期（v2.5）
- [ ] 新闻/利好消息自动汇报（LLM 分析币圈新闻）
- [ ] 多链同时监控（当前只监控主链）
- [ ] 交易记录导出（CSV/JSON）
- [ ] AI 学习用户偏好（根据历史交易调整策略权重）

### 10.2 中期（v3.0）
- [ ] 多 AI 模型切换（用户可选择不同 LLM）
- [ ] 策略回测（用历史数据验证策略效果）
- [ ] 社交信号（监控大户钱包动向）
- [ ] 跨链套利（发现价差自动套利）

### 10.3 长期（v4.0）
- [ ] 多钱包管理（同时监控多个钱包）
- [ ] DeFi 收益优化（自动寻找最优收益池）
- [ ] DAO 治理参与（自动投票）
- [ ] AI 策略市场（用户分享/订阅策略）

---

## 十一、踩坑记录（Lessons Learned）

> 从实际开发中总结的经验，避免重复踩坑。

1. **主线程网络请求**：Android 主线程做 OkHttp 请求容易被系统限制/超时，必须用后台线程 + Handler 回调
2. **批量查询 vs 单查**：`getAllTokenBalances` 批量查询稳定，`getERC20Balance` 单查在某些节点失败
3. **Cloudflare 反爬**：BscScan 的 Cloudflare Challenge 页面无法通过 WebView/OkHttp 绕过
4. **GFW 限制**：国内网络环境所有外国 token discovery API 被墙
5. **节点切换**：BSC 节点需用 AVE 自建代理（延迟 200ms），原 Defibit 节点延迟 835ms
6. **Activity 生命周期**：网络请求在 onCreate() 中触发会与初始化冲突，需移至 onResume() 延迟 500ms
7. **WebView 注入时机**：必须在 onPageStarted 时注入 window.ethereum，设置 isMetaMask: true
8. **中文引号问题**：Java 字符串中不能用中文引号 `""`，会导致编译错误
9. **API URL 补全**：聊天接口需自动补全 `/chat/completions` 路径
10. **版本号规则**：每次代码改动必须升级 versionCode + versionName

---

*本文档是 AI Crypto Wallet 智能体框架的设计蓝图，指导后续开发方向。*
