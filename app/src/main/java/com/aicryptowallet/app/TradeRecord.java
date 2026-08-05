package com.aicryptowallet.app;

import java.util.ArrayList;
import java.util.List;

/**
 * 交易记录数据模型
 */
public class TradeRecord {
    public long timestamp;
    public String chain;
    public String pair;          // e.g. "ETH/USDT"
    public String side;          // "BUY" or "SELL"
    public double amount;
    public double price;
    public double value;         // USD value
    public String txHash;
    public String strategy;      // which strategy triggered
    public double pnl;           // profit/loss in USD
    public String status;        // "SUCCESS", "FAILED", "PENDING"
    public String signalType;    // "STRONG_BUY", "BUY", "SELL", "STRONG_SELL"

    public TradeRecord() {}

    public TradeRecord(long timestamp, String chain, String pair, String side,
                       double amount, double price, double value, String txHash,
                       String strategy, double pnl, String status, String signalType) {
        this.timestamp = timestamp;
        this.chain = chain;
        this.pair = pair;
        this.side = side;
        this.amount = amount;
        this.price = price;
        this.value = value;
        this.txHash = txHash;
        this.strategy = strategy;
        this.pnl = pnl;
        this.status = status;
        this.signalType = signalType;
    }

    public String toCsvLine() {
        // 修复：strategy 等 AI 信号理由字段可能含半角逗号（如"RSI 超买, MACD 死叉"）
        // 之前直接拼接会导致 split(",") 把一行拆成 13+ 段，后续字段全部错位
        // 对所有字符串字段做转义：半角逗号 → 全角逗号（显示无差异，但不会破坏 CSV 结构）
        return timestamp + "," + escapeCsv(chain) + "," + escapeCsv(pair) + "," + escapeCsv(side) + ","
            + amount + "," + price + "," + value + "," + escapeCsv(txHash) + ","
            + escapeCsv(strategy) + "," + pnl + "," + escapeCsv(status) + "," + escapeCsv(signalType);
    }

    private static String escapeCsv(String s) {
        if (s == null) return "";
        return s.replace(',', '\uFF0C');
    }

    public static TradeRecord fromCsvLine(String line) {
        try {
            String[] parts = line.split(",", -1);
            if (parts.length < 12) return null;
            TradeRecord r = new TradeRecord();
            r.timestamp = Long.parseLong(parts[0]);
            r.chain = parts[1];
            r.pair = parts[2];
            r.side = parts[3];
            r.amount = Double.parseDouble(parts[4]);
            r.price = Double.parseDouble(parts[5]);
            r.value = Double.parseDouble(parts[6]);
            r.txHash = parts[7];
            r.strategy = parts[8];
            r.pnl = Double.parseDouble(parts[9]);
            r.status = parts[10];
            r.signalType = parts[11];
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    public static List<TradeRecord> loadAll(android.content.Context ctx) {
        List<TradeRecord> records = new ArrayList<>();
        try {
            android.content.SharedPreferences prefs = ctx.getSharedPreferences("trade_records", android.content.Context.MODE_PRIVATE);
            String data = prefs.getString("records", "");
            if (data.isEmpty()) return records;
            String[] lines = data.split("\n");
            for (String line : lines) {
                TradeRecord r = fromCsvLine(line);
                if (r != null) records.add(r);
            }
        } catch (Exception e) {}
        return records;
    }

    public static void saveAll(android.content.Context ctx, List<TradeRecord> records) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(records.get(i).toCsvLine());
        }
        android.content.SharedPreferences prefs = ctx.getSharedPreferences("trade_records", android.content.Context.MODE_PRIVATE);
        prefs.edit().putString("records", sb.toString()).apply();
    }

    public static void append(android.content.Context ctx, TradeRecord record) {
        try {
            android.content.SharedPreferences prefs = ctx.getSharedPreferences("trade_records", android.content.Context.MODE_PRIVATE);
            String data = prefs.getString("records", "");
            String newLine = record.toCsvLine();
            StringBuilder sb = new StringBuilder();
            sb.append(newLine);
            if (!data.isEmpty()) {
                String[] lines = data.split("\n", 501);
                int count = 1;
                for (String line : lines) {
                    if (count >= 500) break;
                    if (line == null || line.isEmpty()) continue;
                    sb.append("\n").append(line);
                    count++;
                }
            }
            prefs.edit().putString("records", sb.toString()).apply();
        } catch (Exception e) {}
        // 同时写入 AI 操作日志，方便在 AI 操作记录页统一查看
        try {
            AIOperationLogManager.logTrade(ctx, record);
        } catch (Exception ignored) {}
    }
}
