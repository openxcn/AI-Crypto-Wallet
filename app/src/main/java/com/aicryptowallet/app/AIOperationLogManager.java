package com.aicryptowallet.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * AI 操作日志管理器
 * 持久化记录所有 AI 工具调用、分析任务、自动交易等行为
 */
public class AIOperationLogManager {

    private static final String PREFS_NAME = "ai_operation_logs";
    private static final String KEY_LOGS = "logs";
    private static final int MAX_LOGS = 500;

    public static void logToolOperation(Context ctx, String toolName, String params,
                                         String result, String status, String description) {
        AIOperationLog log = new AIOperationLog();
        log.type = "tool";
        log.toolName = toolName;
        log.params = truncate(params, 800);
        log.result = truncate(result, 800);
        log.status = status != null ? status.toLowerCase() : "pending";
        log.description = description != null ? description : ("调用 " + toolName);
        append(ctx, log);
    }

    public static void logAnalysis(Context ctx, String chain, String description, String result, String status) {
        AIOperationLog log = new AIOperationLog();
        log.type = "analysis";
        log.chain = chain;
        log.description = description;
        log.result = truncate(result, 1000);
        log.status = status != null ? status.toLowerCase() : "success";
        append(ctx, log);
    }

    public static void logTrade(Context ctx, TradeRecord record) {
        append(ctx, AIOperationLog.fromTradeRecord(record));
    }

    public static void logNotify(Context ctx, String title, String content, String status) {
        AIOperationLog log = new AIOperationLog();
        log.type = "notify";
        log.description = title;
        log.result = truncate(content, 800);
        log.status = status != null ? status.toLowerCase() : "success";
        append(ctx, log);
    }

    public static List<AIOperationLog> loadAll(Context ctx) {
        List<AIOperationLog> logs = new ArrayList<>();
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String data = prefs.getString(KEY_LOGS, "");
            if (data.isEmpty()) return logs;
            JSONArray array = new JSONArray(data);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj != null) {
                    logs.add(AIOperationLog.fromJson(obj));
                }
            }
        } catch (Exception e) {
            Logger.error(ctx, "AI操作日志", "加载失败: " + e.getMessage(), e);
        }
        // 按时间倒序
        Collections.sort(logs, new Comparator<AIOperationLog>() {
            @Override
            public int compare(AIOperationLog o1, AIOperationLog o2) {
                return Long.compare(o2.timestamp, o1.timestamp);
            }
        });
        return logs;
    }

    public static List<AIOperationLog> loadSuccessTrades(Context ctx) {
        List<AIOperationLog> all = loadAll(ctx);
        List<AIOperationLog> filtered = new ArrayList<>();
        for (AIOperationLog log : all) {
            if ("trade".equals(log.type) && "success".equals(log.status)) {
                filtered.add(log);
            }
        }
        return filtered;
    }

    public static void clear(Context ctx) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_LOGS).apply();
    }

    private static void append(Context ctx, AIOperationLog log) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String data = prefs.getString(KEY_LOGS, "");
            JSONArray array;
            if (data.isEmpty()) {
                array = new JSONArray();
            } else {
                array = new JSONArray(data);
            }

            // 新日志插入头部
            JSONArray newArray = new JSONArray();
            newArray.put(log.toJson());
            int count = 1;
            for (int i = 0; i < array.length() && count < MAX_LOGS; i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj != null) {
                    newArray.put(obj);
                    count++;
                }
            }

            prefs.edit().putString(KEY_LOGS, newArray.toString()).apply();
        } catch (Exception e) {
            Logger.error(ctx, "AI操作日志", "追加失败: " + e.getMessage(), e);
        }
    }

    private static void saveAll(Context ctx, List<AIOperationLog> logs) {
        try {
            JSONArray array = new JSONArray();
            for (AIOperationLog log : logs) {
                array.put(log.toJson());
            }
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LOGS, array.toString()).apply();
        } catch (Exception e) {
            Logger.error(ctx, "AI操作日志", "保存失败: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
