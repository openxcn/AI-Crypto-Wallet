package com.aicryptowallet.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 风险管理器
 * 
 * 管理：
 * 1. AI 高风险黑名单（AI 禁止交易的代币）
 * 2. 用户白名单（用户强制放行的高风险代币）
 * 3. 风险操作日志（记录用户强行操作高风险代币的行为）
 * 4. 风险分析结果缓存
 * 5. 每日亏损限额（AI 交易风控）
 */
public class RiskManager {

    private static final String PREF_NAME = "risk_manager";
    private static final String KEY_BLACKLIST = "blacklist_";
    private static final String KEY_WHITELIST = "whitelist_";
    private static final String KEY_RISK_LOG = "risk_log_";
    private static final String KEY_RISK_SCORE = "risk_score_";
    private static final String KEY_DAILY_LOSS_LIMIT = "daily_loss_limit";

    private final Context ctx;

    // ===== 实例构造（供 SafetyGate 等使用） =====

    public RiskManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /**
     * 获取每日亏损限额
     */
    public double getDailyLossLimit() {
        SharedPreferences prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getFloat(KEY_DAILY_LOSS_LIMIT, 100f);
    }

    /**
     * 设置每日亏损限额
     */
    public void setDailyLossLimit(double limit) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putFloat(KEY_DAILY_LOSS_LIMIT, (float) limit).apply();
    }

    // ===== 风险操作日志条目 =====

    public static class RiskLogEntry {
        public String timestamp;
        public String contract;
        public String symbol;
        public String action;  // "WHITELIST" / "SEND" / "APPROVE" / "SWAP"
        public String note;

        public RiskLogEntry(String timestamp, String contract, String symbol, String action, String note) {
            this.timestamp = timestamp;
            this.contract = contract;
            this.symbol = symbol;
            this.action = action;
            this.note = note;
        }

        @Override
        public String toString() {
            return "[" + timestamp + "] " + action + " | " + symbol + " (" + contract.substring(0, 10) + "...) | " + note;
        }
    }

    // ===== 黑名单管理 =====

    /**
     * 检查代币是否在 AI 黑名单中（禁止交易）
     */
    public static boolean isBlacklisted(Context ctx, String chain, String contractAddress) {
        if (contractAddress == null || contractAddress.isEmpty()) return false;
        // 如果在白名单中，即使黑名单也放行
        if (isWhitelisted(ctx, chain, contractAddress)) return false;
        Set<String> blacklist = getStringSet(ctx, KEY_BLACKLIST + chain);
        return blacklist.contains(contractAddress.toLowerCase());
    }

    /**
     * 将代币加入 AI 黑名单
     */
    public static void addToBlacklist(Context ctx, String chain, String contractAddress) {
        // R-MAB 平台币永远不允许加入黑名单
        if (TokenRiskAnalyzer.RMAB_CONTRACT.equalsIgnoreCase(contractAddress)) {
            Logger.warning(null, "风险管理", "拒绝将 R-MAB 平台币加入黑名单");
            return;
        }
        Set<String> blacklist = getStringSet(ctx, KEY_BLACKLIST + chain);
        blacklist.add(contractAddress.toLowerCase());
        saveStringSet(ctx, KEY_BLACKLIST + chain, blacklist);
        Logger.action(ctx, "风险管理", "AI 已将代币加入黑名单", contractAddress);
    }

    /**
     * 从黑名单移除
     */
    public static void removeFromBlacklist(Context ctx, String chain, String contractAddress) {
        Set<String> blacklist = getStringSet(ctx, KEY_BLACKLIST + chain);
        blacklist.remove(contractAddress.toLowerCase());
        saveStringSet(ctx, KEY_BLACKLIST + chain, blacklist);
    }

    // ===== 白名单管理 =====

    /**
     * 检查代币是否在用户白名单中
     */
    public static boolean isWhitelisted(Context ctx, String chain, String contractAddress) {
        if (contractAddress == null || contractAddress.isEmpty()) return false;
        Set<String> whitelist = getStringSet(ctx, KEY_WHITELIST + chain);
        return whitelist.contains(contractAddress.toLowerCase());
    }

    /**
     * 用户强制将高风险代币加入白名单（记录风险操作）
     */
    public static void addToWhitelist(Context ctx, String chain, String contractAddress, String symbol) {
        // R-MAB 平台币永久豁免：无需加入白名单，也不记录为风险操作
        if (TokenRiskAnalyzer.RMAB_CONTRACT.equalsIgnoreCase(contractAddress)) {
            Logger.warning(null, "风险管理", "R-MAB 平台币永久豁免，跳过加入白名单及风险操作记录");
            return;
        }
        Set<String> whitelist = getStringSet(ctx, KEY_WHITELIST + chain);
        whitelist.add(contractAddress.toLowerCase());
        saveStringSet(ctx, KEY_WHITELIST + chain, whitelist);
        // 从黑名单移除（白名单优先级更高）
        removeFromBlacklist(ctx, chain, contractAddress);
        // 记录风险操作
        addRiskLog(ctx, chain, contractAddress, symbol, "WHITELIST", "用户强制将高风险代币加入白名单");
        Logger.action(ctx, "风险管理", "用户将高风险代币加入白名单", symbol + " " + contractAddress);
    }

    // ===== 风险操作日志 =====

    /**
     * 记录风险操作日志
     */
    public static void addRiskLog(Context ctx, String chain, String contractAddress, String symbol, String action, String note) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        RiskLogEntry entry = new RiskLogEntry(timestamp, contractAddress, symbol, action, note);

        // 保存到 SharedPreferences（JSON 数组格式）
        SharedPreferences prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String key = KEY_RISK_LOG + chain;
        String existing = prefs.getString(key, "[]");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(existing);
            org.json.JSONObject obj = new org.json.JSONObject();
            obj.put("time", entry.timestamp);
            obj.put("contract", entry.contract);
            obj.put("symbol", entry.symbol);
            obj.put("action", entry.action);
            obj.put("note", entry.note);
            arr.put(obj);
            // 最多保留 1000 条
            while (arr.length() > 1000) {
                arr.remove(0);
            }
            prefs.edit().putString(key, arr.toString()).apply();
        } catch (Exception e) {
            Logger.warning(null, "风险管理", "保存风险日志失败: " + e.getMessage());
        }
    }

    /**
     * 读取所有风险操作日志
     */
    public static List<RiskLogEntry> getRiskLogs(Context ctx, String chain) {
        List<RiskLogEntry> logs = new ArrayList<>();
        SharedPreferences prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String key = KEY_RISK_LOG + chain;
        String existing = prefs.getString(key, "[]");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(existing);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                logs.add(new RiskLogEntry(
                    obj.optString("time", ""),
                    obj.optString("contract", ""),
                    obj.optString("symbol", ""),
                    obj.optString("action", ""),
                    obj.optString("note", "")
                ));
            }
        } catch (Exception e) {}
        return logs;
    }

    /**
     * 导出风险日志为文本（用于资产流失追溯）
     */
    public static String exportRiskLog(Context ctx, String chain) {
        List<RiskLogEntry> logs = getRiskLogs(ctx, chain);
        StringBuilder sb = new StringBuilder();
        sb.append("══════════════════════════════\n");
        sb.append("  风险操作记录\n");
        sb.append("  链: ").append(Logger.getChainChineseName(chain)).append("\n");
        sb.append("  导出时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())).append("\n");
        sb.append("══════════════════════════════\n\n");
        if (logs.isEmpty()) {
            sb.append("暂无风险操作记录。\n");
        } else {
            for (RiskLogEntry entry : logs) {
                sb.append(entry.toString()).append("\n");
            }
        }
        sb.append("\n共 ").append(logs.size()).append(" 条风险操作记录。\n");
        sb.append("\n⚠️ 以上为高风险操作记录，若发生资产流失，请参考以上记录排查原因。\n");
        return sb.toString();
    }

    // ===== 风险分析结果缓存 =====

    /**
     * 保存风险分析结果缓存
     */
    public static void saveRiskScore(Context ctx, String chain, String contractAddress, int stars, String report) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit()
            .putInt(KEY_RISK_SCORE + chain + "_" + contractAddress.toLowerCase() + "_stars", stars)
            .putString(KEY_RISK_SCORE + chain + "_" + contractAddress.toLowerCase() + "_report", report)
            .apply();
    }

    /**
     * 读取缓存的风险评分
     */
    public static int getCachedRiskStars(Context ctx, String chain, String contractAddress) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_RISK_SCORE + chain + "_" + contractAddress.toLowerCase() + "_stars", -1);
    }

    /**
     * 读取缓存的风险报告
     */
    public static String getCachedRiskReport(Context ctx, String chain, String contractAddress) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_RISK_SCORE + chain + "_" + contractAddress.toLowerCase() + "_report", null);
    }

    // ===== 工具方法 =====

    private static Set<String> getStringSet(Context ctx, String key) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return new HashSet<>(prefs.getStringSet(key, new HashSet<>()));
    }

    private static void saveStringSet(Context ctx, String key, Set<String> set) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putStringSet(key, new HashSet<>(set)).apply();
    }
}