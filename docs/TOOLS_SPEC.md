# 工具注册规范（AgentToolRegistry）

> 定义 AI 智能体可调用的所有工具，三端统一。
> 版本: v1.0 | 最后更新: 2026-07-20

---

## 工具分类

### 只读工具（Read-Only）

不消耗 gas，不改变链上状态，不需要 SafetyGate 校验。

| # | 工具名 | 功能 | 安全级别 |
|---|--------|------|---------|
| 1 | `get_wallet_address` | 查询当前钱包地址 | 低 |
| 2 | `get_native_balance` | 查询原生币余额 + USD 价值 | 低 |
| 3 | `get_token_balance` | 查询 ERC20 代币余额 | 低 |
| 4 | `get_token_price` | 查询代币当前价格 | 低 |
| 5 | `get_position` | 查询当前所有持仓 | 低 |
| 6 | `get_market_data` | 拉取 K 线技术指标 | 低 |
| 7 | `get_safety_status` | 查询安全网关状态 | 低 |
| 8 | `call_contract_read` | 任意只读合约调用 | 低 |

### 写入工具（Write）

消耗 gas，改变链上状态，**必须经过 SafetyGate 校验**。

| # | 工具名 | 功能 | 安全级别 |
|---|--------|------|---------|
| 9 | `call_contract_write` | 任意写入合约调用 | 高 |
| 10 | `swap_tokens` | DEX 代币兑换 | 高 |
| 11 | `approve_token` | ERC20 授权 | 中 |
| 12 | `send_native` | 原生币转账 | 高 |

---

## 工具详细定义

### 1. get_wallet_address

```json
{
  "name": "get_wallet_address",
  "description": "查询当前钱包在指定链上的地址。用于 AI 了解自己的身份。",
  "parameters": {
    "type": "object",
    "properties": {
      "chain": {
        "type": "string",
        "description": "链标识，如 ETH/BNB/SOL/TRX"
      }
    },
    "required": ["chain"]
  }
}
```

**返回示例**：
```json
{
  "success": true,
  "output": "{\"chain\":\"BNB\",\"address\":\"0xfF7F...aAFae\"}"
}
```

---

### 2. get_native_balance

```json
{
  "name": "get_native_balance",
  "description": "查询当前钱包在指定链上的原生币余额（如 ETH/BNB/SOL），返回余额和折合 USD 价值。",
  "parameters": {
    "type": "object",
    "properties": {
      "chain": {
        "type": "string",
        "description": "链标识"
      }
    },
    "required": ["chain"]
  }
}
```

**返回示例**：
```json
{
  "success": true,
  "output": "{\"chain\":\"BNB\",\"balance\":0.003376,\"price_usd\":568.55,\"value_usd\":1.91}"
}
```

---

### 3. get_token_balance

```json
{
  "name": "get_token_balance",
  "description": "查询当前钱包持有的 ERC20 代币余额。需要代币合约地址。",
  "parameters": {
    "type": "object",
    "properties": {
      "chain": {
        "type": "string",
        "description": "链标识"
      },
      "token_contract": {
        "type": "string",
        "description": "ERC20 代币合约地址"
      }
    },
    "required": ["chain", "token_contract"]
  }
}
```

**返回示例**：
```json
{
  "success": true,
  "output": "{\"chain\":\"BNB\",\"token_contract\":\"0x92cb...fbad\",\"symbol\":\"R-MAB\",\"decimals\":8,\"balance\":20998.0}"
}
```

---

### 4. get_token_price

```json
{
  "name": "get_token_price",
  "description": "查询代币当前价格（USD）。支持主流币种如 ETH/BNB/SOL/TRX/MATIC/AVAX。",
  "parameters": {
    "type": "object",
    "properties": {
      "symbol": {
        "type": "string",
        "description": "代币符号，如 ETH"
      }
    },
    "required": ["symbol"]
  }
}
```

**返回示例**：
```json
{
  "success": true,
  "output": "{\"symbol\":\"ETH\",\"price_usd\":3200.5}"
}
```

---

### 5. get_position

```json
{
  "name": "get_position",
  "description": "查询当前所有持仓状态，包含代币、数量、平均成本、止损止盈价、未实现盈亏。",
  "parameters": {
    "type": "object",
    "properties": {},
    "required": []
  }
}
```

**返回示例**：
```json
{
  "success": true,
  "output": "{\"positions\":[{\"token\":\"ETH\",\"amount\":0.1,\"avgCost\":3200,\"stopLoss\":3000,\"takeProfit\":3500,\"unrealizedPnl\":1.0}]}"
}
```

---

### 6. get_market_data

```json
{
  "name": "get_market_data",
  "description": "拉取指定链指定周期的 K 线技术指标（RSI/MACD/MA/布林带）。用于中长线分析。",
  "parameters": {
    "type": "object",
    "properties": {
      "chain": {
        "type": "string",
        "description": "链标识"
      },
      "cycle": {
        "type": "string",
        "description": "K 线周期：15m/1h/4h/1d",
        "default": "1h"
      },
      "limit": {
        "type": "integer",
        "description": "K 线数量，默认 100",
        "default": 100
      }
    },
    "required": ["chain", "cycle"]
  }
}
```

**返回示例**：
```json
{
  "success": true,
  "output": "{\"chain\":\"BNB\",\"cycle\":\"1h\",\"current_price\":568.55,\"rsi\":50.72,\"macd\":0.15,\"macd_signal\":0.12,\"sma20\":565.3,\"sma50\":570.1,\"bb_lower\":555.0,\"bb_upper\":580.0,\"recent_closes\":[567,568,569,568,568.55]}"
}
```

---

### 7. get_safety_status

```json
{
  "name": "get_safety_status",
  "description": "查询安全网关状态，包括是否熔断、剩余熔断时间、今日交易笔数/金额、错误率、连续亏损次数。AI 在执行写入操作前应先查询此状态。",
  "parameters": {
    "type": "object",
    "properties": {},
    "required": []
  }
}
```

**返回示例**：
```json
{
  "success": true,
  "output": "{\"status\":\"OK\",\"detail\":\"今日交易 2 笔，金额 $150，错误率 0%，连续亏损 0 次\"}"
}
```

---

### 8. call_contract_read

```json
{
  "name": "call_contract_read",
  "description": "只读调用任意合约函数（eth_call，不消耗 gas）。用于查询链上状态如 totalSupply/allowance/reserves/paused 等。",
  "parameters": {
    "type": "object",
    "properties": {
      "chain": {
        "type": "string",
        "description": "链标识"
      },
      "contract": {
        "type": "string",
        "description": "合约地址"
      },
      "function": {
        "type": "string",
        "description": "函数签名，如 balanceOf(address)"
      },
      "params": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "type": {
              "type": "string",
              "description": "参数类型：address/uint256/string/bool/bytes"
            },
            "value": {
              "type": "string",
              "description": "参数值"
            }
          }
        }
      }
    },
    "required": ["chain", "contract", "function"]
  }
}
```

**返回示例**：
```json
{
  "success": true,
  "output": "{\"chain\":\"BNB\",\"contract\":\"0x...\",\"function\":\"balanceOf\",\"result_hex\":\"0x0000...0001\",\"note\":\"结果为 hex 编码，AI 可按 ABI 解读\"}"
}
```

---

### 9. call_contract_write

```json
{
  "name": "call_contract_write",
  "description": "写入调用任意合约函数（广播交易，消耗 gas）。必须经过 SafetyGate 校验。操作描述用于审计日志。",
  "parameters": {
    "type": "object",
    "properties": {
      "chain": { "type": "string", "description": "链标识" },
      "contract": { "type": "string", "description": "合约地址" },
      "function": { "type": "string", "description": "函数签名" },
      "params": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "type": { "type": "string" },
            "value": { "type": "string" }
          }
        }
      },
      "value_wei": {
        "type": "string",
        "description": "附带原生币数量（wei 字符串），可选"
      },
      "operation_desc": {
        "type": "string",
        "description": "操作描述（用于审计，必须清晰说明意图）"
      }
    },
    "required": ["chain", "contract", "function", "operation_desc"]
  }
}
```

**安全要求**：
- 必须经过 SafetyGate 校验
- `operation_desc` 必须清晰说明操作意图
- AI 调用前应先调用 `get_safety_status` 确认未熔断

---

### 10. swap_tokens

```json
{
  "name": "swap_tokens",
  "description": "通过 DEX 路由兑换代币。支持原生币↔USDT、USDT↔代币。会自动处理 approve。必须经过 SafetyGate 校验。",
  "parameters": {
    "type": "object",
    "properties": {
      "chain": { "type": "string", "description": "链标识" },
      "from_token": {
        "type": "string",
        "description": "付出代币合约地址，原生币用 'NATIVE'"
      },
      "to_token": {
        "type": "string",
        "description": "得到代币合约地址，原生币用 'NATIVE'"
      },
      "amount": {
        "type": "number",
        "description": "付出数量（人类可读单位，如 0.1 ETH）"
      },
      "operation_desc": {
        "type": "string",
        "description": "操作描述（用于审计）"
      }
    },
    "required": ["chain", "from_token", "to_token", "amount", "operation_desc"]
  }
}
```

**安全要求**：
- 必须经过 SafetyGate 校验
- 非白名单代币需用户确认
- 滑点保护默认 3%

---

### 11. approve_token

```json
{
  "name": "approve_token",
  "description": "ERC20 授权操作。允许 spender 从本钱包转移指定数量的代币。必须经过 SafetyGate 校验。",
  "parameters": {
    "type": "object",
    "properties": {
      "chain": { "type": "string", "description": "链标识" },
      "token_contract": { "type": "string", "description": "ERC20 代币合约地址" },
      "spender": { "type": "string", "description": "被授权地址" },
      "amount": {
        "type": "string",
        "description": "授权数量（最小单位字符串），无限授权用 'max'"
      },
      "operation_desc": { "type": "string", "description": "操作描述（用于审计）" }
    },
    "required": ["chain", "token_contract", "spender", "amount", "operation_desc"]
  }
}
```

---

### 12. send_native

```json
{
  "name": "send_native",
  "description": "转账原生币到指定地址。必须经过 SafetyGate 校验。请谨慎使用，确认地址正确。",
  "parameters": {
    "type": "object",
    "properties": {
      "chain": { "type": "string", "description": "链标识" },
      "to_address": { "type": "string", "description": "收款地址" },
      "amount": { "type": "number", "description": "转账数量（人类可读单位）" },
      "operation_desc": { "type": "string", "description": "操作描述（用于审计）" }
    },
    "required": ["chain", "to_address", "amount", "operation_desc"]
  }
}
```

**安全要求**：
- 必须经过 SafetyGate 校验
- AI 应提醒用户确认地址正确性

---

## 工具调用流程

```
AI 决定调用工具
       │
       ▼
  AgentToolRegistry.execute()
       │
       ├── 只读工具 → 直接执行 → 返回结果
       │
       └── 写入工具 → SafetyGate.check()
                        │
                        ├── 通过 → 执行交易 → onTradeSuccess() → 返回结果
                        │
                        └── 拒绝 → 返回错误原因 → AI 调整策略
```

---

## 工具返回格式

所有工具统一返回 `ToolResult`：

```json
{
  "success": true,
  "output": "..."
}
```

或失败时：

```json
{
  "success": false,
  "error": "错误描述"
}
```

---

## 扩展工具

未来可添加的工具：

| 工具名 | 功能 | 优先级 |
|--------|------|--------|
| `get_news` | 获取币圈新闻 | 中 |
| `get_whale_activity` | 监控大户钱包动向 | 低 |
| `get_defi_yield` | 查询 DeFi 收益率 | 低 |
| `set_stop_loss` | 设置止损价 | 中 |
| `set_take_profit` | 设置止盈价 | 中 |
| `export_trade_history` | 导出交易记录 | 低 |

---

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| v1.0 | 2026-07-20 | 初始版本，12 个工具 |
