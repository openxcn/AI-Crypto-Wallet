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
 * 数据暂存区 — 多钱包缓存，实现"先显示缓存，后台刷新"
 *
 * 每个钱包独立存储在 data_cache_<addr>.xml，切换钱包时秒开对应缓存。
 *
 * 存储位置：SharedPreferences per wallet
 */
public class DataCache {

    private static final String INDEX_PREFS = "data_cache_index";
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
    private static final long CACHE_VALID_MS = 5 * 60 * 1000;

    private final Context ctx;
    private String currentWalletAddr = "";
    private SharedPreferences currentPrefs;

    public DataCache(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private String prefsName(String address) {
        if (address == null || address.isEmpty()) return "data_cache_default";
        String safe = address.replaceAll("[^a-zA-Z0-9]", "");
        if (safe.length() > 16) safe = safe.substring(0, 16);
        return "data_cache_" + safe;
    }

    private SharedPreferences prefs(String address) {
        String name = prefsName(address);
        return ctx.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private SharedPreferences indexPrefs() {
        return ctx.getSharedPreferences(INDEX_PREFS, Context.MODE_PRIVATE);
    }

    /** 切换当前钱包（不读文件，仅设指针，O(1)） */
    public void setCurrentWallet(String address) {
        if (address == null) address = "";
        if (!address.equals(currentWalletAddr)) {
            currentWalletAddr = address;
            currentPrefs = null;
        }
    }

    private SharedPreferences curPrefs() {
        if (currentPrefs == null) {
            currentPrefs = prefs(currentWalletAddr);
        }
        return currentPrefs;
    }

    // ============================================================
    // 缓存有效性检查
    // ============================================================

    public boolean hasValidCache(String currentAddress) {
        if (currentAddress == null || currentAddress.isEmpty()) return false;
        SharedPreferences p = prefs(currentAddress);
        String cachedAddr = p.getString(KEY_ADDRESS, "");
        return currentAddress.equals(cachedAddr) && p.contains(KEY_TIMESTAMP);
    }

    public boolean isExpired() {
        long ts = curPrefs().getLong(KEY_TIMESTAMP, 0);
        return ts == 0 || (System.currentTimeMillis() - ts) > CACHE_VALID_MS;
    }

    public long getTimestamp() {
        return curPrefs().getLong(KEY_TIMESTAMP, 0);
    }

    // ============================================================
    // 资产数据读写
    // ============================================================

    public void saveAssets(String address, String chain,
                           double totalValue, double nativeBalance, double nativeValue,
                           List<String[]> tokens, Map<String, Double> prices) {
        SharedPreferences p = prefs(address);
        SharedPreferences.Editor ed = p.edit();
        ed.putString(KEY_ADDRESS, address);
        ed.putString(KEY_CHAIN, chain);
        ed.putLong(KEY_TIMESTAMP, System.currentTimeMillis());
        putDouble(ed, KEY_TOTAL_VALUE, totalValue);
        putDouble(ed, KEY_NATIVE_BALANCE, nativeBalance);
        putDouble(ed, KEY_NATIVE_VALUE, nativeValue);

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

        // 更新索引：记录最近活跃的钱包
        indexPrefs().edit().putString("last_wallet", address).apply();
    }

    public double getCachedTotalValue() {
        return getDouble(curPrefs(), KEY_TOTAL_VALUE, 0);
    }

    public double getCachedNativeBalance() {
        return getDouble(curPrefs(), KEY_NATIVE_BALANCE, 0);
    }

    public double getCachedNativeValue() {
        return getDouble(curPrefs(), KEY_NATIVE_VALUE, 0);
    }

    public List<String[]> getCachedTokens() {
        List<String[]> result = new ArrayList<>();
        String json = curPrefs().getString(KEY_TOKENS, "");
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

    // ============================================================
    // 已知代币合约（只增不减）— 用于资产变动检测的稳定基线
    // 避免因 RPC 临时掉线导致已持有代币被反复误报为"新增"
    // ============================================================
    private static final String KEY_KNOWN_CONTRACTS = "known_contracts";

    /** 记录某个钱包所有曾见过的代币合约地址（只增不减） */
    public void addKnownContracts(String address, java.util.Set<String> contracts) {
        if (contracts == null || contracts.isEmpty()) return;
        SharedPreferences p = prefs(address);
        java.util.Set<String> known = new java.util.HashSet<>(getKnownContracts(address));
        boolean changed = false;
        for (String c : contracts) {
            if (c != null && !c.isEmpty() && known.add(c.toLowerCase())) changed = true;
        }
        if (!changed) return;
        p.edit().putStringSet(KEY_KNOWN_CONTRACTS, new java.util.HashSet<>(known)).apply();
    }

    /** 获取某钱包所有已知代币合约地址（小写） */
    public java.util.Set<String> getKnownContracts(String address) {
        SharedPreferences p = prefs(address);
        java.util.Set<String> s = p.getStringSet(KEY_KNOWN_CONTRACTS, null);
        if (s == null) return new java.util.HashSet<>();
        java.util.Set<String> lower = new java.util.HashSet<>();
        for (String c : s) if (c != null) lower.add(c.toLowerCase());
        return lower;
    }

    // ============================================================
    // 资产变动通知去重（Home 前后台 与 AgentForegroundService 共用）
    // 用"去抖 + 已通知合约 + 原生币相对阈值"确保：恒定余额不重复报、
    // 已通知代币不重复报、前后台/刷新不叠加通知。
    // ============================================================
    private static final String KEY_NOTIFY_TS = "asset_notify_ts";
    private static final String KEY_NOTIFIED_NATIVE = "asset_notified_native";
    private static final String KEY_NOTIFIED_CONTRACTS = "asset_notified_contracts";
    /** 同一钱包两次资产变动通知的最小间隔 */
    public static final long ASSET_NOTIFY_MIN_INTERVAL_MS = 180000L;

    /** 检测结果：应由调用方决定是否发送通知 */
    public static class AssetChangeResult {
        public boolean shouldNotify;
        /** 本次发现的"真正新增代币"（从未通知过且余额>0），逗号拼接给用户看 */
        public String newTokens = "";
        /** 原生币是否明显增加（相对上次通知值，带阈值） */
        public boolean nativeIncreased;
        /** 原生币名称（用于拼接提示文案） */
        public String nativeName = "";
        /** 原生币当前余额（格式化后） */
        public String nativeBalanceText = "";
    }

    /**
     * 检测并去重资产变动。传入当前钱包地址、当前代币列表（含原生币条目）与原生币余额。
     * 返回对象；调用方仅在 shouldNotify=true 时发送通知。
     * 内部会更新"已通知"状态与去抖时间戳，因此每次调用都应执行（无论是否通知）。
     */
    public AssetChangeResult detectAssetChange(String address, java.util.List<String[]> tokens, double nativeBalance) {
        AssetChangeResult r = new AssetChangeResult();
        if (address == null || address.isEmpty()) return r;
        long now = System.currentTimeMillis();

        // 去抖：3 分钟内不重复通知，避免刷新/切换钱包/前后台同时触发
        if (now - getAssetNotifyTs(address) < ASSET_NOTIFY_MIN_INTERVAL_MS) {
            return r;
        }

        java.util.Set<String> known = getKnownContracts(address);
        java.util.Set<String> notified = getNotifiedContracts(address);
        java.util.Set<String> current = new java.util.HashSet<>();

        // 1) 原生币条目（用于展示名称与余额，以及判断是否新增）
        for (String[] t : tokens) {
            boolean isNative = t.length > 5 && "true".equals(t[5]);
            if (isNative) {
                r.nativeName = t.length > 1 ? t[1] : "";
                r.nativeBalanceText = t.length > 2 ? t[2] : "";
                break;
            }
        }

        // 2) 真正的新增代币：合约从未在"已通知"且从未在"已知基线"中，且余额>0
        StringBuilder newTokens = new StringBuilder();
        for (String[] t : tokens) {
            if (t.length > 4 && !t[4].isEmpty()) {
                String c = t[4].toLowerCase();
                current.add(c);
                if (!notified.contains(c) && !known.contains(c)) {
                    double bal = 0;
                    try { bal = Double.parseDouble(t[2]); } catch (Exception ignore) {}
                    if (bal > 1e-9) {
                        if (newTokens.length() > 0) newTokens.append("、");
                        newTokens.append(t[0]);
                    }
                }
            }
        }
        r.newTokens = newTokens.toString();

        // 3) 原生币：相对上次通知值明显增加才报（阈值=max(0.00005, 0.5%））
        double lastNotifiedNative = getAssetNotifiedNative(address);
        if (lastNotifiedNative >= 0) {
            double threshold = Math.max(0.00005, Math.abs(lastNotifiedNative) * 0.005);
            r.nativeIncreased = nativeBalance > lastNotifiedNative + threshold;
        }

        // 4) 汇总判断
        r.shouldNotify = r.newTokens.length() > 0 || r.nativeIncreased;
        if (!r.shouldNotify) return r;

        // 5) 更新去重状态（仅在真正要通知时）
        setAssetNotifyTs(address, now);
        addNotifiedContracts(address, current);
        if (r.nativeIncreased) setAssetNotifiedNative(address, nativeBalance);
        addKnownContracts(address, current);
        return r;
    }

    public long getAssetNotifyTs(String address) { return prefs(address).getLong(KEY_NOTIFY_TS, 0); }
    public void setAssetNotifyTs(String address, long ts) { prefs(address).edit().putLong(KEY_NOTIFY_TS, ts).apply(); }

    public double getAssetNotifiedNative(String address) {
        return getDouble(prefs(address), KEY_NOTIFIED_NATIVE, -1);
    }
    public void setAssetNotifiedNative(String address, double v) {
        putDouble(prefs(address).edit(), KEY_NOTIFIED_NATIVE, v);
        prefs(address).edit().putLong(KEY_NOTIFIED_NATIVE, Double.doubleToRawLongBits(v)).apply();
    }

    public java.util.Set<String> getNotifiedContracts(String address) {
        SharedPreferences p = prefs(address);
        java.util.Set<String> s = p.getStringSet(KEY_NOTIFIED_CONTRACTS, null);
        if (s == null) return new java.util.HashSet<>();
        java.util.Set<String> lower = new java.util.HashSet<>();
        for (String c : s) if (c != null) lower.add(c.toLowerCase());
        return lower;
    }

    public void addNotifiedContracts(String address, java.util.Set<String> contracts) {
        if (contracts == null || contracts.isEmpty()) return;
        SharedPreferences p = prefs(address);
        java.util.Set<String> not = new java.util.HashSet<>(getNotifiedContracts(address));
        boolean changed = false;
        for (String c : contracts) {
            if (c != null && !c.isEmpty() && not.add(c.toLowerCase())) changed = true;
        }
        if (!changed) return;
        p.edit().putStringSet(KEY_NOTIFIED_CONTRACTS, new java.util.HashSet<>(not)).apply();
    }

    public Map<String, Double> getCachedPrices() {
        Map<String, Double> result = new HashMap<>();
        String json = curPrefs().getString(KEY_PRICES, "");
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

    public String getCachedAddress() {
        return curPrefs().getString(KEY_ADDRESS, "");
    }

    // ============================================================
    // AI 状态缓存
    // ============================================================

    public void saveAIStatus(String status, double pnl, String winRate,
                             int trades, String chain) {
        SharedPreferences.Editor ed = curPrefs().edit();
        ed.putString(KEY_AI_STATUS, status);
        putDouble(ed, KEY_AI_PNL, pnl);
        ed.putString(KEY_AI_WINRATE, winRate);
        ed.putInt(KEY_AI_TRADES, trades);
        ed.putString(KEY_AI_CHAIN, chain);
        ed.apply();
    }

    public String getCachedAIStatus() {
        return curPrefs().getString(KEY_AI_STATUS, "");
    }

    public double getCachedAIPnL() {
        return getDouble(curPrefs(), KEY_AI_PNL, 0);
    }

    public String getCachedAIWinRate() {
        return curPrefs().getString(KEY_AI_WINRATE, "--");
    }

    public int getCachedAITrades() {
        return curPrefs().getInt(KEY_AI_TRADES, 0);
    }

    public String getCachedAIChain() {
        return curPrefs().getString(KEY_AI_CHAIN, "");
    }

    // ============================================================
    // 所有钱包总资产缓存（全局唯一，不按钱包隔离）
    // ============================================================

    public void saveAllWalletsTotal(double total) {
        SharedPreferences.Editor ed = indexPrefs().edit();
        putDouble(ed, KEY_ALL_WALLETS_TOTAL, total);
        ed.putLong(KEY_ALL_WALLETS_TIMESTAMP, System.currentTimeMillis());
        ed.apply();
    }

    public double getCachedAllWalletsTotal() {
        return getDouble(indexPrefs(), KEY_ALL_WALLETS_TOTAL, 0);
    }

    public boolean isAllWalletsTotalExpired() {
        long ts = indexPrefs().getLong(KEY_ALL_WALLETS_TIMESTAMP, 0);
        return ts == 0 || (System.currentTimeMillis() - ts) > CACHE_VALID_MS;
    }

    // ============================================================
    // 行情数据缓存
    // ============================================================

    public void saveMarketData(String marketJson) {
        SharedPreferences.Editor ed = indexPrefs().edit();
        ed.putString(KEY_MARKET, marketJson);
        ed.putLong("market_timestamp", System.currentTimeMillis());
        ed.apply();
    }

    public String getCachedMarketData() {
        return indexPrefs().getString(KEY_MARKET, "");
    }

    public boolean hasMarketCache() {
        return indexPrefs().contains(KEY_MARKET);
    }

    public boolean isMarketExpired() {
        long ts = indexPrefs().getLong("market_timestamp", 0);
        return ts == 0 || (System.currentTimeMillis() - ts) > CACHE_VALID_MS;
    }

    // ============================================================
    // 缓存清理
    // ============================================================

    public void clearCache(String address) {
        prefs(address).edit().clear().apply();
    }

    public long getCacheAgeSeconds() {
        long ts = curPrefs().getLong(KEY_TIMESTAMP, 0);
        if (ts == 0) return -1;
        return (System.currentTimeMillis() - ts) / 1000;
    }

    // ============================================================
    // 已发现代币持久化
    // ============================================================

    private static final String KEY_DISCOVERED_TOKENS = "discovered_tokens";

    public void saveDiscoveredTokens(java.util.List<String[]> tokens) {
        try {
            JSONArray arr = new JSONArray();
            for (String[] t : tokens) {
                JSONArray row = new JSONArray();
                row.put(t[0] != null ? t[0] : "");
                row.put(t[1] != null ? t[1] : "");
                row.put(t[2] != null ? t[2] : "18");
                arr.put(row);
            }
            indexPrefs().edit().putString(KEY_DISCOVERED_TOKENS, arr.toString()).apply();
        } catch (Exception e) {
            Logger.error(null, "DataCache", "保存已发现代币失败", e);
        }
    }

    public java.util.List<String[]> getDiscoveredTokens() {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        String json = indexPrefs().getString(KEY_DISCOVERED_TOKENS, "");
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
    // 辅助方法
    // ============================================================

    private void putDouble(SharedPreferences.Editor ed, String key, double value) {
        ed.putLong(key, Double.doubleToRawLongBits(value));
    }

    private double getDouble(SharedPreferences p, String key, double defaultValue) {
        if (!p.contains(key)) return defaultValue;
        return Double.longBitsToDouble(p.getLong(key, Double.doubleToRawLongBits(defaultValue)));
    }

    // ============================================================
    // 每日快照
    // ============================================================

    private static final String KEY_SNAPSHOT_DATE = "snapshot_date";
    private static final String KEY_SNAPSHOT_VALUE = "snapshot_value";

    public void saveDailySnapshotIfNeeded(double totalValue) {
        String today = getTodayKey();
        String lastDate = indexPrefs().getString(KEY_SNAPSHOT_DATE, "");
        if (!today.equals(lastDate)) {
            indexPrefs().edit()
                .putString(KEY_SNAPSHOT_DATE, today)
                .putLong(KEY_SNAPSHOT_VALUE, Double.doubleToRawLongBits(totalValue))
                .apply();
        }
    }

    public double getLastSnapshotValue() {
        String lastDate = indexPrefs().getString(KEY_SNAPSHOT_DATE, "");
        if (lastDate.isEmpty()) return 0;
        return Double.longBitsToDouble(indexPrefs().getLong(KEY_SNAPSHOT_VALUE, 0));
    }

    public String getSnapshotDate() {
        return indexPrefs().getString(KEY_SNAPSHOT_DATE, "");
    }

    private String getTodayKey() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
        return sdf.format(new java.util.Date());
    }
}
