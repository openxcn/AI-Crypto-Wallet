package com.aicryptowallet.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 持仓状态管理器 - Agent Runtime 持仓层核心组件
 *
 * 产品定位：AI 中长线炒币助手
 *
 * 职责：
 * 1. 维护当前持仓快照（token, amount, avgCost, openTime, stopLoss, takeProfit）
 * 2. swap 成功后自动更新持仓
 * 3. 提供"是否触发止盈止损"的判断（供 PositionMonitor 调用）
 * 4. 持久化到 SharedPreferences（JSON 格式）
 *
 * 这是把"配置字段"变成"执行逻辑"的关键 —— 之前 RiskManager 的 stopLossPercent/takeProfitPercent
 * 是死配置，现在 PositionManager 维护每笔持仓的独立止损价/止盈价，支持精细化风控
 */
public class PositionManager {
    private static final String PREFS_NAME = "position_state";
    private static final String KEY_POSITIONS = "positions_json";

    /**
     * 单个持仓快照
     */
    public static class Position {
        public String chain;          // 持仓所在链
        public String tokenSymbol;    // 代币符号（如 ETH）
        public String tokenContract;  // 代币合约地址（原生币为空）
        public double amount;         // 持仓数量
        public double avgCost;        // 平均成本（USD）
        public long openTime;         // 开仓时间戳
        public double stopLossPrice;  // 止损价（USD），0 表示未设置
        public double takeProfitPrice;// 止盈价（USD），0 表示未设置
        public String openTxHash;     // 开仓交易哈希

        public Position() {}

        public Position(String chain, String tokenSymbol, String tokenContract,
                        double amount, double avgCost, long openTime,
                        double stopLossPrice, double takeProfitPrice, String openTxHash) {
            this.chain = chain;
            this.tokenSymbol = tokenSymbol;
            this.tokenContract = tokenContract;
            this.amount = amount;
            this.avgCost = avgCost;
            this.openTime = openTime;
            this.stopLossPrice = stopLossPrice;
            this.takeProfitPrice = takeProfitPrice;
            this.openTxHash = openTxHash;
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("chain", chain);
            o.put("tokenSymbol", tokenSymbol);
            o.put("tokenContract", tokenContract == null ? "" : tokenContract);
            o.put("amount", amount);
            o.put("avgCost", avgCost);
            o.put("openTime", openTime);
            o.put("stopLossPrice", stopLossPrice);
            o.put("takeProfitPrice", takeProfitPrice);
            o.put("openTxHash", openTxHash == null ? "" : openTxHash);
            return o;
        }

        public static Position fromJson(JSONObject o) {
            Position p = new Position();
            p.chain = o.optString("chain", "");
            p.tokenSymbol = o.optString("tokenSymbol", "");
            p.tokenContract = o.optString("tokenContract", "");
            p.amount = o.optDouble("amount", 0);
            p.avgCost = o.optDouble("avgCost", 0);
            p.openTime = o.optLong("openTime", 0);
            p.stopLossPrice = o.optDouble("stopLossPrice", 0);
            p.takeProfitPrice = o.optDouble("takeProfitPrice", 0);
            p.openTxHash = o.optString("openTxHash", "");
            return p;
        }
    }

    /**
     * 触发原因（用于止盈止损判断结果）
     */
    public enum TriggerReason {
        NONE,               // 未触发
        STOP_LOSS,          // 触发止损
        TAKE_PROFIT,        // 触发止盈
        TIME_STOP,          // 触发时间止损（持仓过久）
    }

    /**
     * 止盈止损检查结果
     */
    public static class CheckResult {
        public TriggerReason reason;
        public Position position;
        public double currentPrice;
        public double pnlPercent;

        public CheckResult(TriggerReason reason, Position position,
                            double currentPrice, double pnlPercent) {
            this.reason = reason;
            this.position = position;
            this.currentPrice = currentPrice;
            this.pnlPercent = pnlPercent;
        }

        public boolean shouldClose() {
            return reason == TriggerReason.STOP_LOSS || reason == TriggerReason.TAKE_PROFIT;
        }
    }

    /**
     * 加载所有持仓
     */
    public static List<Position> loadPositions(Context ctx) {
        List<Position> positions = new ArrayList<>();
        try {
            String json = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_POSITIONS, "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                positions.add(Position.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            // 解析失败返回空列表，不崩溃
        }
        return positions;
    }

    /**
     * 保存所有持仓
     */
    public static void savePositions(Context ctx, List<Position> positions) {
        try {
            JSONArray arr = new JSONArray();
            for (Position p : positions) {
                arr.put(p.toJson());
            }
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_POSITIONS, arr.toString()).apply();
        } catch (Exception e) {
            // 保存失败不崩溃
        }
    }

    /**
     * 开仓/加仓后更新持仓
     * @param chain 链
     * @param tokenSymbol 代币符号
     * @param tokenContract 合约地址（原生币为空）
     * @param buyAmount 买入数量
     * @param buyPrice 买入价格（USD）
     * @param stopLossPercent 止损百分比（如 0.05 表示 5%）
     * @param takeProfitPercent 止盈百分比（如 0.15 表示 15%）
     * @param txHash 交易哈希
     */
    public static void onBuy(Context ctx, String chain, String tokenSymbol, String tokenContract,
                              double buyAmount, double buyPrice,
                              double stopLossPercent, double takeProfitPercent,
                              String txHash) {
        List<Position> positions = loadPositions(ctx);

        // 查找是否已有同代币持仓（加仓场景）
        Position existing = null;
        for (Position p : positions) {
            if (p.chain.equals(chain) && p.tokenSymbol.equals(tokenSymbol)) {
                existing = p;
                break;
            }
        }

        if (existing != null) {
            // 加仓：重新计算平均成本
            double totalValue = existing.amount * existing.avgCost + buyAmount * buyPrice;
            double totalAmount = existing.amount + buyAmount;
            existing.avgCost = totalValue / totalAmount;
            existing.amount = totalAmount;
            // 止损止盈价按新的平均成本重新计算
            existing.stopLossPrice = existing.avgCost * (1 - stopLossPercent);
            existing.takeProfitPrice = existing.avgCost * (1 + takeProfitPercent);
        } else {
            // 新开仓
            Position p = new Position(
                chain, tokenSymbol, tokenContract,
                buyAmount, buyPrice, System.currentTimeMillis(),
                buyPrice * (1 - stopLossPercent),
                buyPrice * (1 + takeProfitPercent),
                txHash
            );
            positions.add(p);
        }

        savePositions(ctx, positions);
        Logger.info(ctx, "持仓管理", "买入更新: " + tokenSymbol + " +" + buyAmount
            + " @ $" + buyPrice + " 止损=$" + String.format("%.2f",
                (existing != null ? existing.stopLossPrice : buyPrice * (1 - stopLossPercent)))
            + " 止盈=$" + String.format("%.2f",
                (existing != null ? existing.takeProfitPrice : buyPrice * (1 + takeProfitPercent))));
    }

    /**
     * 平仓后清除持仓
     */
    public static void onSell(Context ctx, String chain, String tokenSymbol,
                               double sellAmount, double sellPrice) {
        List<Position> positions = loadPositions(ctx);
        Iterator<Position> it = positions.iterator();
        while (it.hasNext()) {
            Position p = it.next();
            if (p.chain.equals(chain) && p.tokenSymbol.equals(tokenSymbol)) {
                if (sellAmount >= p.amount - 0.0000001) {
                    // 全部平仓
                    double pnl = (sellPrice - p.avgCost) * p.amount;
                    Logger.info(ctx, "持仓管理", "平仓: " + tokenSymbol
                        + " 数量=" + p.amount + " 成本=$" + p.avgCost
                        + " 卖出=$" + sellPrice + " 盈亏=$" + String.format("%.2f", pnl));
                    it.remove();
                } else {
                    // 部分平仓
                    p.amount -= sellAmount;
                    Logger.info(ctx, "持仓管理", "部分平仓: " + tokenSymbol
                        + " 剩余=" + p.amount + " @ $" + p.avgCost);
                }
                break;
            }
        }
        savePositions(ctx, positions);
    }

    /**
     * 检查指定持仓是否触发止盈止损
     * @param position 持仓
     * @param currentPrice 当前价格（USD）
     * @return 检查结果
     */
    public static CheckResult checkPosition(Position position, double currentPrice) {
        if (currentPrice <= 0 || position.amount <= 0) {
            return new CheckResult(TriggerReason.NONE, position, currentPrice, 0);
        }

        double pnlPercent = (currentPrice - position.avgCost) / position.avgCost;

        // 止损检查
        if (position.stopLossPrice > 0 && currentPrice <= position.stopLossPrice) {
            return new CheckResult(TriggerReason.STOP_LOSS, position, currentPrice, pnlPercent);
        }

        // 止盈检查
        if (position.takeProfitPrice > 0 && currentPrice >= position.takeProfitPrice) {
            return new CheckResult(TriggerReason.TAKE_PROFIT, position, currentPrice, pnlPercent);
        }

        return new CheckResult(TriggerReason.NONE, position, currentPrice, pnlPercent);
    }

    /**
     * 获取指定链上所有持仓的检查结果（供 PositionMonitor 批量调用）
     */
    public static List<CheckResult> checkAllPositions(Context ctx, String chain,
                                                       java.util.Map<String, Double> prices) {
        List<CheckResult> results = new ArrayList<>();
        List<Position> positions = loadPositions(ctx);
        for (Position p : positions) {
            if (!p.chain.equals(chain)) continue;
            double price = prices.getOrDefault(p.tokenSymbol, 0.0);
            results.add(checkPosition(p, price));
        }
        return results;
    }

    /**
     * 获取指定链上的总持仓价值（USD）
     */
    public static double getTotalPositionValue(Context ctx, String chain,
                                                 java.util.Map<String, Double> prices) {
        double total = 0;
        for (Position p : loadPositions(ctx)) {
            if (!p.chain.equals(chain)) continue;
            double price = prices.getOrDefault(p.tokenSymbol, 0.0);
            total += p.amount * price;
        }
        return total;
    }

    /**
     * 获取持仓摘要（供 AI 决策注入上下文）
     */
    public static String getPositionSummary(Context ctx) {
        List<Position> positions = loadPositions(ctx);
        if (positions.isEmpty()) return "当前无持仓";
        StringBuilder sb = new StringBuilder("当前持仓:\n");
        for (Position p : positions) {
            sb.append("- ").append(p.tokenSymbol).append(" (").append(p.chain).append(")")
              .append(" 数量=").append(String.format("%.6f", p.amount))
              .append(" 成本=$").append(String.format("%.2f", p.avgCost))
              .append(" 止损=$").append(String.format("%.2f", p.stopLossPrice))
              .append(" 止盈=$").append(String.format("%.2f", p.takeProfitPrice))
              .append("\n");
        }
        return sb.toString().trim();
    }
}
