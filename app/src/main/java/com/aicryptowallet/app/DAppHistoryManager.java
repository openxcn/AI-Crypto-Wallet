/*
 * Copyright (C) 2026 红魔团队 (Red Devil Team)
 *
 * This software is proprietary and confidential.
 * Unauthorized copying, distribution, or modification is strictly prohibited.
 *
 * Licensed to: Authorized Users Only
 * Authorization required: Contact openxcn@github.com
 */
package com.aicryptowallet.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * DApp 浏览记录管理器
 *
 * 记录 DApp 浏览器内访问过的网页，支持单条删除、清空。每条记录含 url / title / 时间戳。
 * 同一 URL 只保留最近一次访问，并按访问时间倒序展示，最多保留 {@link #MAX_RECORDS} 条。
 */
public final class DAppHistoryManager {

    private static final String PREFS = "dapp_history_prefs";
    private static final String KEY_HISTORY = "history_json";
    private static final int MAX_RECORDS = 100;

    /** 单条历史记录 */
    public static class HistoryEntry {
        public String url;
        public String title;
        public long ts;
    }

    private DAppHistoryManager() {}

    /**
     * 记录一次访问。同一 URL 去重（保留最新），最新在前。
     */
    public static void recordVisit(Context ctx, String url, String title) {
        if (ctx == null || url == null || url.isEmpty()) return;
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return;

        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray arr = readArray(prefs);

        // 去掉同 URL 旧记录
        JSONArray filtered = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optString("url", "").equals(url)) continue;
            filtered.put(o);
        }

        // 新记录插到最前
        JSONObject rec = new JSONObject();
        try {
            rec.put("url", url);
            rec.put("title", title == null ? "" : title);
            rec.put("ts", System.currentTimeMillis());
        } catch (Exception ignored) {}
        JSONArray out = new JSONArray();
        out.put(rec);
        for (int i = 0; i < filtered.length() && i < MAX_RECORDS - 1; i++) {
            out.put(filtered.optJSONObject(i));
        }

        prefs.edit().putString(KEY_HISTORY, out.toString()).apply();
    }

    /**
     * 读取全部浏览记录（最新在前）。
     */
    public static List<HistoryEntry> load(Context ctx) {
        List<HistoryEntry> list = new ArrayList<>();
        if (ctx == null) return list;
        JSONArray arr = readArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            HistoryEntry e = new HistoryEntry();
            e.url = o.optString("url", "");
            e.title = o.optString("title", "");
            e.ts = o.optLong("ts", 0);
            if (!e.url.isEmpty()) list.add(e);
        }
        return list;
    }

    /**
     * 删除单条浏览记录。
     */
    public static void delete(Context ctx, String url) {
        if (ctx == null || url == null || url.isEmpty()) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray arr = readArray(prefs);
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optString("url", "").equals(url)) continue;
            out.put(o);
        }
        prefs.edit().putString(KEY_HISTORY, out.toString()).apply();
    }

    /** 清空全部浏览记录 */
    public static void clear(Context ctx) {
        if (ctx == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_HISTORY).apply();
    }

    private static JSONArray readArray(SharedPreferences prefs) {
        String json = prefs.getString(KEY_HISTORY, "");
        if (json.isEmpty()) return new JSONArray();
        try {
            return new JSONArray(json);
        } catch (Exception e) {
            return new JSONArray();
        }
    }
}