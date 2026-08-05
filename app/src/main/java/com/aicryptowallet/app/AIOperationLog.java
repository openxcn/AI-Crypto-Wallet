package com.aicryptowallet.app;

import org.json.JSONObject;
import java.util.UUID;

/**
 * AI 操作日志数据模型
 * 覆盖 AI 工具调用、分析任务、自动交易等所有 AI 行为
 */
public class AIOperationLog {

    public String id;
    public long timestamp;
    public String type;        // "trade" / "tool" / "analysis" / "notify"
    public String toolName;    // 工具名称，type=tool 时使用
    public String params;      // 调用参数摘要（JSON 或纯文本）
    public String result;      // 执行结果摘要
    public String status;      // "success" / "failed" / "pending"
    public String chain;       // 相关链
    public String description; // 简短描述
    public String txHash;      // 交易哈希，type=trade 时使用

    // trade 专用字段
    public String side;        // "BUY" / "SELL"
    public String pair;        // 交易对
    public double amount;
    public double price;
    public double pnl;

    public AIOperationLog() {
        this.id = UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("timestamp", timestamp);
            obj.put("type", type != null ? type : "");
            obj.put("toolName", toolName != null ? toolName : "");
            obj.put("params", params != null ? params : "");
            obj.put("result", result != null ? result : "");
            obj.put("status", status != null ? status : "");
            obj.put("chain", chain != null ? chain : "");
            obj.put("description", description != null ? description : "");
            obj.put("txHash", txHash != null ? txHash : "");
            obj.put("side", side != null ? side : "");
            obj.put("pair", pair != null ? pair : "");
            obj.put("amount", amount);
            obj.put("price", price);
            obj.put("pnl", pnl);
        } catch (Exception ignored) {}
        return obj;
    }

    public static AIOperationLog fromJson(JSONObject obj) {
        AIOperationLog log = new AIOperationLog();
        try {
            log.id = obj.optString("id", UUID.randomUUID().toString());
            log.timestamp = obj.optLong("timestamp", System.currentTimeMillis());
            log.type = obj.optString("type", "");
            log.toolName = obj.optString("toolName", "");
            log.params = obj.optString("params", "");
            log.result = obj.optString("result", "");
            log.status = obj.optString("status", "");
            log.chain = obj.optString("chain", "");
            log.description = obj.optString("description", "");
            log.txHash = obj.optString("txHash", "");
            log.side = obj.optString("side", "");
            log.pair = obj.optString("pair", "");
            log.amount = obj.optDouble("amount", 0);
            log.price = obj.optDouble("price", 0);
            log.pnl = obj.optDouble("pnl", 0);
        } catch (Exception ignored) {}
        return log;
    }

    public static AIOperationLog fromTradeRecord(TradeRecord r) {
        AIOperationLog log = new AIOperationLog();
        log.type = "trade";
        log.timestamp = r.timestamp;
        log.status = r.status != null ? r.status.toLowerCase() : "pending";
        log.chain = r.chain;
        log.txHash = r.txHash;
        log.side = r.side;
        log.pair = r.pair;
        log.amount = r.amount;
        log.price = r.price;
        log.pnl = r.pnl;
        log.description = (r.side != null ? r.side : "") + " " + (r.pair != null ? r.pair : "");
        return log;
    }
}
