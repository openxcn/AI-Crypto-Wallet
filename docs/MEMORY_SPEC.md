# 第2层：记忆系统规范（AgentMemory）

> 每个钱包独立一份记忆文件，不共享不同步。
> 支持导出分享到第三方工具（微信/QQ/Telegram/飞书等）。
> 版本: v1.0 | 最后更新: 2026-07-20

---

## 文件位置

```
Android:  Android/data/com.aicryptowallet.app/files/agent_workspace/agent_memory.json
iOS:      Documents/agent_workspace/agent_memory.json
Windows:  %APPDATA%/AICryptoWallet/agent_workspace/agent_memory.json
```

每个钱包地址对应一个独立的工作区目录：
```
agent_workspace/
── {wallet_address_short}/
│   ├── agent_memory.json       # 主记忆文件
│   ├── chat_history.jsonl      # 对话历史（可选，用于上下文压缩）
│   └── trade_journal.jsonl     # 交易日志（可选，用于长期记忆）
```

> `wallet_address_short` = 钱包地址前6位 + 后4位，如 `0xfF7F_aAFae`

---

## 记忆文件结构

```json
{
  "version": "1.0",
  "walletAddress": "0xfF7F9639cFb8945Ce220759635937073b74aAFae",

  "identity": {
    "aiName": "AI 交易助手",
    "ownerName": "主人",
    "personality": "沉稳理性，分析数据后给出建议，不冲动"
  },

  "trading": {
    "tradingChain": "BNB",
    "checkIntervalMinutes": 5,
    "maxDailyLoss": 50.0,
    "newsReportIntervalHours": 24,
    "preferredCycle": "1h",
    "riskLevel": "medium"
  },

  "preferences": {
    "avoidTokens": [],
    "favoriteTokens": [],
    "language": "zh-CN",
    "detailLevel": "concise"
  },

  "longTermMemory": {
    "tradeJournal": [],
    "marketObservations": [],
    "userLessons": []
  },

  "meta": {
    "apiKeyMasked": "sk-****abcd",
    "customNotes": "",
    "createdAt": 1721484000000,
    "updatedAt": 1721484000000,
    "exportCount": 0,
    "importSource": null
  }
}
```

---

## 字段说明

### identity（身份）

| 字段 | 类型 | 默认值 | 说明 | AI可修改 |
|------|------|--------|------|---------|
| aiName | string | "AI 交易助手" | AI 的名字 | 是 |
| ownerName | string | "主人" | 用户的称呼 | 是 |
| personality | string | "沉稳理性..." | AI 的性格描述 | 是 |

### trading（交易配置）

| 字段 | 类型 | 默认值 | 范围 | AI可修改 |
|------|------|--------|------|---------|
| tradingChain | string | "BNB" | 支持的链标识 | 是 |
| checkIntervalMinutes | int | 5 | 1-60 | 是 |
| maxDailyLoss | double | 50.0 | 0-10000 | 是 |
| newsReportIntervalHours | int | 24 | 1-168 | 是 |
| preferredCycle | string | "1h" | 15m/1h/4h/1d | 是 |
| riskLevel | string | "medium" | conservative/medium/aggressive | 是 |

### preferences（偏好）

| 字段 | 类型 | 默认值 | 说明 | AI可修改 |
|------|------|--------|------|---------|
| avoidTokens | string[] | [] | AI 应避免推荐的代币 | 是 |
| favoriteTokens | string[] | [] | 用户偏好的代币 | 是 |
| language | string | "zh-CN" | 回答语言 | 是 |
| detailLevel | string | "concise" | concise/normal/detailed | 是 |

### longTermMemory（长期记忆）

| 字段 | 类型 | 说明 |
|------|------|------|
| tradeJournal | object[] | 重要交易记录（决策理由+结果） |
| marketObservations | string[] | AI 对市场的观察和判断 |
| userLessons | string[] | 从与用户交互中学到的偏好 |

**tradeJournal 条目格式**：
```json
{
  "timestamp": 1721484000000,
  "action": "BUY",
  "token": "ETH",
  "amount": 0.5,
  "price": 3200,
  "reason": "RSI 35 超卖区，MACD 金叉形成",
  "result": "PROFIT",
  "pnl": 45.0,
  "closedAt": 1721570400000
}
```

### meta（元数据）

| 字段 | 类型 | 说明 |
|------|------|------|
| apiKeyMasked | string | API Key 脱敏（前4后4） |
| customNotes | string | 用户/AI 的自定义备注 |
| createdAt | long | 记忆文件创建时间戳 |
| updatedAt | long | 最后更新时间戳 |
| exportCount | int | 被导出分享的次数 |
| importSource | string | 如果是导入的，记录来源（可选） |

---

## 脱敏规则

| 数据类型 | 存储方式 | 示例 |
|---------|---------|------|
| 钱包地址 | **完整存储** | `0xfF7F9639cFb8945Ce220759635937073b74aAFae` |
| API Key | 脱敏（前4后4） | `sk-****abcd` |
| 私钥 | **不存储** | AI 永远看不到私钥 |
| 助记词 | **不存储** | AI 永远看不到助记词 |

> 钱包地址完整存储是因为 AI 需要知道当前操作的是哪个钱包。
> 私钥和助记词由 WalletManager 本地管理，AI 通过工具调用发起交易，签名在本地完成。

---

## AI 自修改机制

### @SET 指令格式

AI 在回复中声明修改意图：
```
@SET 字段名=新值
```

### 支持的字段

```
identity.aiName
identity.ownerName
identity.personality
trading.tradingChain
trading.checkIntervalMinutes
trading.maxDailyLoss
trading.newsReportIntervalHours
trading.preferredCycle
trading.riskLevel
preferences.avoidTokens
preferences.favoriteTokens
preferences.language
preferences.detailLevel
meta.customNotes
```

### 范围校验

| 字段 | 最小值 | 最大值 | 超出处理 |
|------|--------|--------|---------|
| checkIntervalMinutes | 1 | 60 | 截断到范围内 |
| maxDailyLoss | 0 | 10000 | 截断到范围内 |
| newsReportIntervalHours | 1 | 168 | 截断到范围内 |

### 解析逻辑

```java
// 从 AI 回复中提取 @SET 指令
Pattern pattern = Pattern.compile("@SET\\s+([\\w.]+)\\s*=\\s*(.+)");
Matcher matcher = pattern.matcher(aiReply);

while (matcher.find()) {
    String field = matcher.group(1);
    String value = matcher.group(2).trim();
    applySetCommand(field, value);
}

// 从显示文本中过滤掉 @SET 指令
String displayText = aiReply.replaceAll("@SET\\s+[\\w.]+\\s*=\\s*.+", "").trim();
```

### 安全限制

- AI 不能修改 `meta.apiKeyMasked`（只有用户能在设置中修改）
- AI 不能修改 `walletAddress`（由系统自动设置）
- AI 不能修改 `version`（由代码控制）
- AI 不能修改 `longTermMemory` 直接写入（通过对话自动积累）

---

## 导出与分享

### 导出格式

纯 JSON 文件，可直接通过系统分享菜单发送：
- 微信 / QQ / Telegram / 飞书 / 邮件 / 蓝牙 / 文件管理器

### 导出内容

```json
{
  "exportFormat": "agent_memory",
  "version": "1.0",
  "exportedAt": 1721484000000,
  "data": { ... agent_memory.json 的完整内容 ... }
}
```

### 导入流程

1. 用户从第三方工具收到记忆文件
2. 打开 App → 我的 → 导入记忆
3. 选择 JSON 文件
4. 系统校验：
   - 文件格式是否正确
   - 字段值是否在合法范围内
   - walletAddress 是否与当前钱包匹配（不匹配则提示）
5. 确认后覆盖当前记忆文件

### 导入安全

- 导入前显示预览（AI 名字、性格、配置等）
- 用户必须手动确认才能覆盖
- 导入前自动备份当前记忆文件
- 不导入 `meta.apiKeyMasked`（每个钱包的 API Key 独立配置）

---

## 长期记忆积累规则

### 交易记录自动写入

每次 AI 执行交易后，自动写入 tradeJournal：
```
触发条件: AgentRuntime 执行了 swap_tokens 工具
写入内容: 代币、方向、数量、价格、决策理由、时间
更新时机: 交易确认后（onTradeSuccess）
```

### 市场观察自动写入

AI 在分析中发现重要市场信号时：
```
触发条件: AI 在回复中提到"重要信号"或"值得注意"
写入内容: 观察摘要
上限: 最多保留 20 条，超出删除最早的
```

### 用户偏好学习

从对话中提取用户偏好：
```
示例:
  用户说 "别推荐MEME币" → avoidTokens 加入 "DOGE","SHIB"
  用户说 "我喜欢稳健的" → riskLevel 改为 "conservative"
  用户说 "多关注ETH" → favoriteTokens 加入 "ETH"
```

---

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| v1.0 | 2026-07-20 | 初始版本，支持导出/导入/自修改 |
