# 第3层：上下文压缩规范

> 参考 Trae 的记忆系统（session_memory + topics.md），设计对话上下文的压缩逻辑。
> 版本: v1.0 | 最后更新: 2026-07-20

---

## 设计目标

1. **短期记忆**：保留最近对话原文，保证连贯性
2. **长期记忆**：压缩旧对话为摘要，节省 Token
3. **重要信息提取**：交易决策、盈亏结果、用户偏好 写入记忆文件
4. **Token 预算**：整个提示词不超过 LLM context window 的 70%

---

## 三层记忆结构

```
┌─────────────────────────────────────────┐
│  第1层：内置提示词（固定，~500 tokens）   │ ← SYSTEM_PROMPT.md
├─────────────────────────────────────────┤
│  第2层：用户记忆（动态，~200 tokens）     │ ← agent_memory.json
├─────────────────────────────────────────┤
│  第3层：对话上下文（动态压缩）            │ ← 本文件
│  ┌───────────────────────────────────┐  │
│  │ 近期对话摘要（压缩后的旧对话）      │  │
│  ├───────────────────────────────────┤  │
│  │ 最近 10 轮对话原文                 │  │
│  ├───────────────────────────────────┤  │
│  │ 当前用户消息                       │  │
│  └───────────────────────────────────┘  │
─────────────────────────────────────────┘
```

---

## 对话历史存储

### 存储格式（JSONL）

每个钱包的对话历史存储在 `chat_history.jsonl`：

```jsonl
{"id":1,"role":"user","content":"你好，你是谁","timestamp":1721484000000}
{"id":2,"role":"assistant","content":"我是 AI 交易助手...","timestamp":1721484001000}
{"id":3,"role":"user","content":"帮我看看 ETH 怎么样","timestamp":1721484100000}
{"id":4,"role":"assistant","content":"ETH 当前价格...","timestamp":1721484101000,"tool_calls":["get_market_data"]}
```

### 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| id | int | 对话轮次编号 |
| role | string | user / assistant |
| content | string | 对话内容（已过滤 @SET 指令） |
| timestamp | long | 时间戳 |
| tool_calls | string[] | 可选，AI 调用了哪些工具 |
| compressed | boolean | 可选，是否已被压缩为摘要 |

---

## 压缩触发条件

### 条件1：对话轮次超限

```
if (totalRounds > 10) {
    compressOldestRounds();
}
```

- 保留最近 10 轮对话原文
- 超出部分压缩为摘要

### 条件2：Token 超限

```
if (totalTokens > contextWindow * 0.7) {
    compressUntilFit();
}
```

- 估算 Token 数（中文约 1.5 字/token，英文约 4 字符/token）
- 优先压缩旧对话 → 再压缩工具返回结果 → 最后压缩市场数据

### 条件3：会话结束

```
onSessionEnd() {
    generateSessionSummary();
    saveToTopics();
}
```

- 用户关闭 AI 页面时，生成会话摘要
- 摘要写入 `topics.md` 或记忆文件的 `longTermMemory`

---

## 压缩算法

### 单轮压缩格式

```
[轮次N] 用户: {问题摘要，20字以内} → AI: {回答要点，30字以内}
```

**示例**：
```
[轮次1] 用户: 问 AI 是谁 → AI: 自我介绍，AI 交易助手，沉稳理性
[轮次2] 用户: 问 ETH 行情 → AI: ETH $3200，RSI 55 中性，建议观望
[轮次3] 用户: 要求买入 0.1 ETH → AI: 执行买入，花费$320，已确认
```

### 批量压缩

当多轮对话主题相同时，合并为一条：
```
[轮次1-3] 用户询问 ETH 行情并买入 0.1 ETH → AI 分析后执行，花费$320
```

### 重要信息提取

压缩时提取以下信息写入长期记忆：

| 信息类型 | 提取条件 | 写入位置 |
|---------|---------|---------|
| 交易决策 | AI 执行了 swap/approve/send | `longTermMemory.tradeJournal` |
| 盈亏结果 | 交易确认后的收益/亏损 | `longTermMemory.tradeJournal` |
| 用户偏好 | 用户明确表达喜好/厌恶 | `preferences.avoidTokens/favoriteTokens` |
| 市场观察 | AI 发现重要信号 | `longTermMemory.marketObservations` |

---

## 上下文注入格式

### 聊天模式

```markdown
# 近期对话摘要
[轮次1-5] 用户询问 ETH 行情，AI 分析 RSI/MACD 后建议观望
[轮次6-8] 用户要求买入 0.1 ETH，AI 执行成功，花费$320

# 当前对话
用户: 现在 ETH 怎么样了？
```

### 交易分析模式（定时触发）

```markdown
# 上一轮分析结论
上次分析（5分钟前）：ETH RSI 55 中性，MACD 金叉形成中，建议继续观望

# 当前市场快照
- 价格: $3210 (+0.3%)
- RSI: 56
- MACD: 金叉确认
- 持仓: 0.1 ETH，成本$3200，浮盈$1
- 安全状态: 正常，今日交易 1 笔
```

---

## Token 预算分配

假设 LLM context window = 8000 tokens（Claude/GPT-4o-mini）：

| 部分 | Token 预算 | 占比 |
|------|-----------|------|
| 第1层：内置提示词 | 500 | 6% |
| 第2层：用户记忆 | 200 | 3% |
| 第3层：对话摘要 | 300 | 4% |
| 第3层：最近 10 轮对话 | 1500 | 19% |
| 第3层：工具返回结果 | 1000 | 13% |
| 第3层：市场数据 | 500 | 6% |
| **预留给用户消息+AI回复** | **4000** | **50%** |
| **总计** | **8000** | **100%** |

> 实际使用中，70% 上限 = 5600 tokens 用于输入，留 2400 tokens 给 AI 回复。

---

## 压缩实现（伪代码）

```java
public class ContextCompressor {
    
    private static final int MAX_RECENT_ROUNDS = 10;
    private static final double CONTEXT_BUDGET_RATIO = 0.7;
    
    /**
     * 构建完整的对话上下文
     */
    public String buildContext(List<ChatTurn> history, int contextWindow) {
        StringBuilder sb = new StringBuilder();
        
        // 1. 压缩旧对话为摘要
        if (history.size() > MAX_RECENT_ROUNDS) {
            List<ChatTurn> oldTurns = history.subList(0, history.size() - MAX_RECENT_ROUNDS);
            String summary = compressTurns(oldTurns);
            sb.append("# 近期对话摘要\n").append(summary).append("\n\n");
        }
        
        // 2. 保留最近 10 轮原文
        List<ChatTurn> recentTurns = history.subList(
            Math.max(0, history.size() - MAX_RECENT_ROUNDS), 
            history.size()
        );
        sb.append("# 当前对话\n");
        for (ChatTurn turn : recentTurns) {
            String role = turn.role.equals("user") ? "用户" : "AI";
            sb.append(role).append(": ").append(turn.content).append("\n");
        }
        
        // 3. 检查 Token 预算
        int estimatedTokens = estimateTokens(sb.toString());
        int maxTokens = (int)(contextWindow * CONTEXT_BUDGET_RATIO);
        
        if (estimatedTokens > maxTokens) {
            // 进一步压缩
            return aggressiveCompress(sb, maxTokens);
        }
        
        return sb.toString();
    }
    
    /**
     * 压缩多轮对话为摘要
     */
    private String compressTurns(List<ChatTurn> turns) {
        StringBuilder sb = new StringBuilder();
        int startId = turns.get(0).id;
        int endId = turns.get(turns.size() - 1).id;
        
        // 按主题分组
        List<String> topics = groupByTopic(turns);
        
        for (String topic : topics) {
            sb.append("[轮次").append(startId).append("-").append(endId).append("] ")
              .append(topic).append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * 按主题分组对话
     */
    private List<String> groupByTopic(List<ChatTurn> turns) {
        // 简单实现：每 3 轮合并为一条
        List<String> topics = new ArrayList<>();
        for (int i = 0; i < turns.size(); i += 3) {
            int end = Math.min(i + 3, turns.size());
            List<ChatTurn> group = turns.subList(i, end);
            String summary = summarizeGroup(group);
            topics.add(summary);
        }
        return topics;
    }
    
    /**
     * 估算 Token 数
     */
    private int estimateTokens(String text) {
        // 粗略估算：中文 1.5 字/token，英文 4 字符/token
        int chineseChars = 0;
        int otherChars = 0;
        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        return chineseChars / 2 + otherChars / 4;
    }
}
```

---

## 会话摘要生成

### 触发时机

- 用户关闭 AI 页面
- 对话超过 50 轮
- 定时任务（每小时）

### 摘要格式

```markdown
# 会话摘要 - 2026-07-20 22:00

## 对话概况
- 总轮次: 25
- 时长: 45 分钟
- 主题: ETH 行情分析 + 买入决策

## 关键决策
1. [22:05] 用户询问 ETH 行情 → AI 分析 RSI/MACD 后建议观望
2. [22:15] ETH 跌破$3150 → AI 建议加仓
3. [22:20] 用户买入 0.1 ETH → 成功，花费$315

## 盈亏结果
- 买入 0.1 ETH @ $3150
- 当前浮盈: +$5 (+0.16%)

## 用户偏好
- 喜欢稳健操作
- 关注 ETH 和 BNB
- 不推荐 MEME 币
```

### 存储位置

```
agent_workspace/{wallet}/
── agent_memory.json
├── chat_history.jsonl
├── topics/
│   ├── 20260720.md          # 每日话题摘要
│   └── 20260721.md
└── trade_journal.jsonl
```

---

## 与 Trae 记忆系统的对比

| Trae 概念 | 我们的对应 | 说明 |
|----------|-----------|------|
| `project_memory.md` | `agent_memory.json` | 项目/钱包级规则 |
| `user_profile.md` | `agent_memory.json.identity` | 用户/AI 画像 |
| `session_memory_*.jsonl` | `chat_history.jsonl` | 会话级细粒度记忆 |
| `topics.md` | `topics/{date}.md` | 每日话题摘要 |
| 上下文压缩 | `ContextCompressor` | 10轮原文 + 旧对话摘要 |

---

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| v1.0 | 2026-07-20 | 初始版本，参考 Trae 记忆系统 |
