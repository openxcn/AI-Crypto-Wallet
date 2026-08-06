package com.aicryptowallet.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * DApp / 链游白名单管理器
 *
 * 用户可将信任的 DApp 域名加入白名单，并授权 AI 在限定额度内自动操作。
 * 白名单信息持久化到 SharedPreferences，包含：
 * - 域名
 * - 允许的操作（click / input / evaluate / transaction）
 * - 每日/单笔额度限制
 * - 添加时间
 * - 用户确认的风险提示
 */
public class DAppWhitelistManager {

    private static final String PREFS_NAME = "dapp_whitelist_v1";
    private static final String KEY_ENTRIES = "entries";

    private static final String OP_CLICK = "click";
    private static final String OP_INPUT = "input";
    private static final String OP_EVALUATE = "evaluate";
    private static final String OP_TRANSACTION = "transaction";

    /** 单个白名单条目 */
    public static class Entry {
        public String domain;
        public boolean allowClick;
        public boolean allowInput;
        public boolean allowEvaluate;
        public boolean allowTransaction;
        public BigDecimal dailyCapUsd;
        public BigDecimal perTxCapUsd;
        public long addedAt;
        public String riskConfirmed;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("domain", domain);
                o.put("allowClick", allowClick);
                o.put("allowInput", allowInput);
                o.put("allowEvaluate", allowEvaluate);
                o.put("allowTransaction", allowTransaction);
                o.put("dailyCapUsd", dailyCapUsd != null ? dailyCapUsd.toPlainString() : "0");
                o.put("perTxCapUsd", perTxCapUsd != null ? perTxCapUsd.toPlainString() : "0");
                o.put("addedAt", addedAt);
                o.put("riskConfirmed", riskConfirmed);
            } catch (Exception ignored) {}
            return o;
        }

        public static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            try {
                e.domain = o.optString("domain", "");
                e.allowClick = o.optBoolean("allowClick", true);
                e.allowInput = o.optBoolean("allowInput", true);
                e.allowEvaluate = o.optBoolean("allowEvaluate", true);
                e.allowTransaction = o.optBoolean("allowTransaction", false);
                e.dailyCapUsd = new BigDecimal(o.optString("dailyCapUsd", "0"));
                e.perTxCapUsd = new BigDecimal(o.optString("perTxCapUsd", "0"));
                e.addedAt = o.optLong("addedAt", 0);
                e.riskConfirmed = o.optString("riskConfirmed", "");
            } catch (Exception ignored) {}
            return e;
        }
    }

    private final SharedPreferences prefs;
    private final Map<String, Entry> cache;

    public DAppWhitelistManager(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.cache = new HashMap<>();
        loadFromPrefs();
    }

    private void loadFromPrefs() {
        cache.clear();
        try {
            String data = prefs.getString(KEY_ENTRIES, "[]");
            JSONArray arr = new JSONArray(data);
            for (int i = 0; i < arr.length(); i++) {
                Entry e = Entry.fromJson(arr.getJSONObject(i));
                if (e.domain != null && !e.domain.isEmpty()) {
                    cache.put(normalizeDomain(e.domain), e);
                }
            }
        } catch (Exception e) {
            Logger.error(null, "DApp白名单", "加载失败: " + e.getMessage(), e);
        }
    }

    private void saveToPrefs() {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : cache.values()) {
                arr.put(e.toJson());
            }
            prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply();
        } catch (Exception e) {
            Logger.error(null, "DApp白名单", "保存失败: " + e.getMessage(), e);
        }
    }

    /** 标准化域名：小写、去协议、去路径 */
    public static String normalizeDomain(String url) {
        if (url == null) return "";
        String d = url.toLowerCase().trim();
        if (d.startsWith("https://")) d = d.substring(8);
        else if (d.startsWith("http://")) d = d.substring(7);
        int slash = d.indexOf('/');
        if (slash > 0) d = d.substring(0, slash);
        int colon = d.indexOf(':');
        if (colon > 0) d = d.substring(0, colon);
        return d;
    }

    /** 判断域名是否在白名单 */
    public boolean isWhitelisted(String urlOrDomain) {
        return cache.containsKey(normalizeDomain(urlOrDomain));
    }

    /** 获取白名单条目 */
    public Entry getEntry(String urlOrDomain) {
        return cache.get(normalizeDomain(urlOrDomain));
    }

    /** 检查是否允许某项操作 */
    public boolean isOperationAllowed(String urlOrDomain, String operation) {
        Entry e = getEntry(urlOrDomain);
        if (e == null) return false;
        switch (operation) {
            case OP_CLICK: return e.allowClick;
            case OP_INPUT: return e.allowInput;
            case OP_EVALUATE: return e.allowEvaluate;
            case OP_TRANSACTION: return e.allowTransaction;
            default: return false;
        }
    }

    /**
     * 从 SharedPreferences 重新加载白名单缓存。
     * AI 工具（request_dapp_whitelist）会创建新的 manager 实例写入 prefs，
     * 而 DApp 浏览器的 manager 实例缓存是旧数据，必须 reload 才能读到新加入的域名。
     */
    public void reload() {
        loadFromPrefs();
    }

    /** 添加/更新白名单条目 */
    public void putEntry(Entry entry) {
        if (entry == null || entry.domain == null || entry.domain.isEmpty()) return;
        entry.domain = normalizeDomain(entry.domain);
        cache.put(entry.domain, entry);
        saveToPrefs();
    }

    /** 移除白名单 */
    public void remove(String urlOrDomain) {
        cache.remove(normalizeDomain(urlOrDomain));
        saveToPrefs();
    }

    /** 获取所有白名单条目 */
    public JSONArray getAllEntriesJson() {
        JSONArray arr = new JSONArray();
        for (Entry e : cache.values()) {
            arr.put(e.toJson());
        }
        return arr;
    }

    /** 检查单笔交易是否超出白名单额度 */
    public boolean checkTransactionAllowed(String urlOrDomain, double amountUsd) {
        Entry e = getEntry(urlOrDomain);
        if (e == null || !e.allowTransaction) return false;
        if (e.perTxCapUsd != null && e.perTxCapUsd.compareTo(BigDecimal.ZERO) > 0) {
            if (new BigDecimal(String.valueOf(amountUsd)).compareTo(e.perTxCapUsd) > 0) {
                return false;
            }
        }
        return true;
    }
}
