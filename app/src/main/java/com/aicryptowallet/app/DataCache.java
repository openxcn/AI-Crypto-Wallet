package com.aicryptowallet.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据暂存区 — 缓存区块链数据，实现"先显示缓存，后台刷新"
 *
 * 每次打开 App 先读取暂存区快速显示，后台获取最新数据后再更新。
 * 缓存按钱包地址隔离，切换钱包后自动失效。
 *
 * 存储位置：SharedPreferences "data_cache.xml"
 */
public class DataCache {

    private static final String PREFS = "data_cache";
    private static final String KEY_ADDRESS = "wallet_address";
    private static final String KEY_CHAIN = "chain";
    private static final String KEY_TIMESTAMP = "timestamp";
    private static final String KEY_TOTAL_VALUE = "total_value";
    private static final String KEY_NATIVE_BALANCE = "native_balance";
    private static final String KEY_NATIVE_VALUE = "native_value";
    private static final String KEY_TOKENS = "tokens";
    private static final String KEY_PRICES = "prices";
    private static final String KEY_AI_STATUS = "ai_status";
    private static final String KEY_AI_PNL = "ai_pnl";
    private static final String KEY_AI_WINRATE = "ai_winrate";
    private static final String KEY_AI_TRADES = "ai_trades";
    private static final String KEY_AI_CHAIN = "ai_chain";
    private static final String KEY_MARKET = "market";
    private static final String KEY_ALL_WALLETS_TOTAL = "all_wallets_total";
    private static final String KEY_ALL_WALLETS_TIMESTAMP = "all_wallets_timestamp";

    // 缓存有效期：5 分钟，超过则标记为过期（但仍显示，只是标注"数据较旧"）
    private static final long CACHE_VALID_MS = 5 * 60 * 1000;

    private final SharedPreferences prefs;

    public DataCache(Context ctx) {
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ============================================================
    // 缓存有效性检查
    // ============================================================

    /** 缓存是否存在且钱包地址匹配 */
    public boolean hasValidCache(String currentAddress) {
        String cachedAddr = prefs.getString(KEY_ADDRESS, "");
        return currentAddress != null
            && currentAddress.equals(cachedAddr)
            && prefs.contains(KEY_TIMESTAMP);
    }

    /** 缓存是否过期（超过 5 分钟） */
    public boolean isExpired() {
        long ts = prefs.getLong(KEY_TIMESTAMP, 0);
        return ts == 0 || (System.currentTimeMillis() - ts) > CACHE_VALID_MS;
    }

    /** 获取缓存时间戳 */
    public long getTimestamp() {
        return prefs.getLong(KEY_TIMESTAMP, 0);
    }

    // ============================================================
    // 资产数据读写
    // ============================================================

    /** 保存资产数据到暂存区 */
    public void saveAssets(String address, String chain,
                           double totalValue, double nativeBalance, double nativeValue,
                           List<String[]> tokens, Map<String, Double> prices) {
        SharedPreferences.Editor ed = prefs.edit();
        ed.putString(KEY_ADDRESS, address);
        ed.putString(KEY_CHAIN, chain);
        ed.putLong(KEY_TIMESTAMP, System.currentTimeMillis());
        putDouble(ed, KEY_TOTAL_VALUE, totalValue);
        putDouble(ed, KEY_NATIVE_BALANCE, nativeBalance);
        putDouble(ed, KEY_NATIVE_VALUE, nativeValue);

        // 序列化代币列表
        try {
            JSONArray arr = new JSONArray();
            for (String[] t : tokens) {
                JSONArray row = new JSONArray();
                for (String s : t) row.put(s != null ? s : "");
                arr.put(row);
            }
            ed.putString(KEY_TOKENS, arr.toString());
        } catch (Exception e) {
            Logger.error(null, "DataCache", "序列化代币失败", e);
        }

        // 序列化价格表
        try {
            JSONObject priceObj = new JSONObject();
            for (Map.Entry<String, Double> entry : prices.entrySet()) {
                priceObj.put(entry.getKey(), entry.getValue());
            }
            ed.putString(KEY_PRICES, priceObj.toString());
        } catch (Exception e) {
            Logger.error(null, "DataCache", "序列化价格失败", e);
        }

        ed.apply();
    }

    /** 从暂存区读取总资产 */
    public double getCachedTotalValue() {
        return getDouble(KEY_TOTAL_VALUE, 0);
    }

    /** 从暂存区读取原生币余额 */
    public double getCachedNativeBalance() {
        return getDouble(KEY_NATIVE_BALANCE, 0);
    }

    /** 从暂存区读取原生币 USD 价值 */
    public double getCachedNativeValue() {
        return getDouble(KEY_NATIVE_VALUE, 0);
    }

    /** 从暂存区读取代币列表 */
    public List<String[]> getCachedTokens() {
        List<String[]> result = new ArrayList<>();
        String json = prefs.getString(KEY_TOKENS, "");
        if (json.isEmpty()) return result;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONArray row = arr.getJSONArray(i);
                String[] token = new String[row.length()];
                for (int j = 0; j < row.length(); j++) {
                    token[j] = row.optString(j, "");
                }
                result.add(token);
            }
        } catch (Exception e) {
            Logger.error(null, "DataCache", "反序列化代币失败", e);
        }
        return result;
    }

    /** 从暂存区读取价格表 */
    public Map<String, Double> getCachedPrices() {
        Map<String, Double> result = new HashMap<>();
        String json = prefs.getString(KEY_PRICES, "");
        if (json.isEmpty()) return result;
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray names = obj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.getString(i);
                    result.put(key, obj.getDouble(key));
                }
            }
        } catch (Exception e) {
            Logger.error(null, "DataCache", "反序列化价格失败", e);
        }
        return result;
    }

    // ============================================================
    // AI 状态缓存
    // ============================================================

    /** 保存 AI 状态到暂存区 */
    public void saveAIStatus(String status, double pnl, String winRate,
                             int trades, String chain) {
        SharedPreferences.Editor ed = prefs.edit();
        ed.putString(KEY_AI_STATUS, status);
        putDouble(ed, KEY_AI_PNL, pnl);
        ed.putString(KEY_AI_WINRATE, winRate);
        ed.putInt(KEY_AI_TRADES, trades);
        ed.putString(KEY_AI_CHAIN, chain);
        ed.apply();
    }

    /** 读取缓存的 AI 状态文本 */
    public String getCachedAIStatus() {
        return prefs.getString(KEY_AI_STATUS, "");
    }

    /** 读取缓存的 AI 盈亏 */
    public double getCachedAIPnL() {
        return getDouble(KEY_AI_PNL, 0);
    }

    /** 读取缓存的 AI 胜率 */
    public String getCachedAIWinRate() {
        return prefs.getString(KEY_AI_WINRATE, "--");
    }

    /** 读取缓存的 AI 交易笔数 */
    public int getCachedAITrades() {
        return prefs.getInt(KEY_AI_TRADES, 0);
    }

    /** 读取缓存的 AI 交易链 */
    public String getCachedAIChain() {
        return prefs.getString(KEY_AI_CHAIN, "");
    }

    // ============================================================
    // 所有钱包总资产缓存
    // ============================================================

    /** 保存所有钱包总资产到暂存区 */
    public void saveAllWalletsTotal(double total) {
        SharedPreferences.Editor ed = prefs.edit();
        putDouble(ed, KEY_ALL_WALLETS_TOTAL, total);
        ed.putLong(KEY_ALL_WALLETS_TIMESTAMP, System.currentTimeMillis());
        ed.apply();
    }

    /** 从暂存区读取所有钱包总资产 */
    public double getCachedAllWalletsTotal() {
        return getDouble(KEY_ALL_WALLETS_TOTAL, 0);
    }

    /** 所有钱包总资产缓存是否过期 */
    public boolean isAllWalletsTotalExpired() {
        long ts = prefs.getLong(KEY_ALL_WALLETS_TIMESTAMP, 0);
        return ts == 0 || (System.currentTimeMillis() - ts) > CACHE_VALID_MS;
    }

    // ============================================================
    // 行情数据缓存
    // ============================================================

    /** 保存行情列表到暂存区
     * @param marketJson 行情数据 JSON 字符串（由调用方序列化）
     */
    public void saveMarketData(String marketJson) {
        SharedPreferences.Editor ed = prefs.edit();
        ed.putString(KEY_MARKET, marketJson);
        ed.putLong("market_timestamp", System.currentTimeMillis());
        ed.apply();
    }

    /** 读取缓存的行情数据 JSON */
    public String getCachedMarketData() {
        return prefs.getString(KEY_MARKET, "");
    }

    /** 行情缓存是否存在 */
    public boolean hasMarketCache() {
        return prefs.contains(KEY_MARKET);
    }

    /** 行情缓存是否过期 */
    public boolean isMarketExpired() {
        long ts = prefs.getLong("market_timestamp", 0);
        return ts == 0 || (System.currentTimeMillis() - ts) > CACHE_VALID_MS;
    }

    // ============================================================
    // 缓存清理
    // ============================================================

    /** 切换钱包时清除旧缓存 */
    public void clearCache() {
        prefs.edit().clear().apply();
    }

    /** 获取缓存年龄（秒），用于 UI 显示"数据更新于 X 秒前" */
    public long getCacheAgeSeconds() {
        long ts = prefs.getLong(KEY_TIMESTAMP, 0);
        if (ts == 0) return -1;
        return (System.currentTimeMillis() - ts) / 1000;
    }

    // ============================================================
    // 已发现代币持久化（Transfer 扫描发现的非热门代币，如 GOUT）
    // 避免每次启动都重新扫描 500,000 区块
    // ============================================================

    private static final String KEY_DISCOVERED_TOKENS = "discovered_tokens";

    /** 保存 Transfer 扫描发现的代币合约（symbol|contract|decimals 格式） */
    public void saveDiscoveredTokens(java.util.List<String[]> tokens) {
        try {
            JSONArray arr = new JSONArray();
            for (String[] t : tokens) {
                JSONArray row = new JSONArray();
                row.put(t[0] != null ? t[0] : ""); // symbol
                row.put(t[1] != null ? t[1] : ""); // contract
                row.put(t[2] != null ? t[2] : "18"); // decimals
                arr.put(row);
            }
            prefs.edit().putString(KEY_DISCOVERED_TOKENS, arr.toString()).apply();
        } catch (Exception e) {
            Logger.error(null, "DataCache", "保存已发现代币失败", e);
        }
    }

    /** 读取已持久化的代币列表，返回 [symbol, contract, decimals] 数组 */
    public java.util.List<String[]> getDiscoveredTokens() {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        String json = prefs.getString(KEY_DISCOVERED_TOKENS, "");
        if (json.isEmpty()) return result;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONArray row = arr.getJSONArray(i);
                String[] token = new String[3];
                token[0] = row.optString(0, "");
                token[1] = row.optString(1, "");
                token[2] = row.optString(2, "18");
                if (!token[1].isEmpty()) result.add(token);
            }
        } catch (Exception e) {
            Logger.error(null, "DataCache", "读取已发现代币失败", e);
        }
        return result;
    }

    // ============================================================
    // 辅助方法：SharedPreferences 不支持 double，用 long 存储
    // ============================================================

    private void putDouble(SharedPreferences.Editor ed, String key, double value) {
        ed.putLong(key, Double.doubleToRawLongBits(value));
    }

    private double getDouble(String key, double defaultValue) {
        if (!prefs.contains(key)) return defaultValue;
        return Double.longBitsToDouble(prefs.getLong(key, Double.doubleToRawLongBits(defaultValue)));
    }

    // ============================================================
    // 每日快照 (用于计算今日盈亏)
    // ============================================================

    private static final String KEY_SNAPSHOT_DATE = "snapshot_date";
    private static final String KEY_SNAPSHOT_VALUE = "snapshot_value";

    /** 保存每日快照（仅在当天首次加载时保存） */
    public void saveDailySnapshotIfNeeded(double totalValue) {
        String today = getTodayKey();
        String lastDate = prefs.getString(KEY_SNAPSHOT_DATE, "");
        if (!today.equals(lastDate)) {
            // 新的一天，保存快照
            prefs.edit()
                .putString(KEY_SNAPSHOT_DATE, today)
                .putLong(KEY_SNAPSHOT_VALUE, Double.doubleToRawLongBits(totalValue))
                .apply();
        }
    }

    /** 获取上一次快照值（用于计算盈亏） */
    public double getLastSnapshotValue() {
        String lastDate = prefs.getString(KEY_SNAPSHOT_DATE, "");
        if (lastDate.isEmpty()) return 0;
        return Double.longBitsToDouble(prefs.getLong(KEY_SNAPSHOT_VALUE, 0));
    }

    /** 获取快照日期 */
    public String getSnapshotDate() {
        return prefs.getString(KEY_SNAPSHOT_DATE, "");
    }

    private String getTodayKey() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
        return sdf.format(new java.util.Date());
    }
}
