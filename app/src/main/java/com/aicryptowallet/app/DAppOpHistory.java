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
import java.util.ArrayList;
import java.util.List;

/**
 * DApp 操作记录器
 *
 * DApp 浏览器内发生的每次关键操作（连接钱包、发起交易、签名、只读 RPC 等）统一
 * 并入主日志文件 app_logs.txt（模块名固定为「DApp操作」），实现单一记录来源。
 * 「操作记录」弹窗从主日志中按模块过滤并展示 DApp 操作，支持一键清空（仅删除
 * DApp操作模块的记录，不影响其他日志）。
 */
public final class DAppOpHistory {

    /** 写入主日志使用的模块名（与 Logger.action 的 module 参数一致） */
    public static final String LOG_MODULE = "DApp操作";

    // 操作类型标签
    public static final String OP_CONNECT = "连接钱包";
    public static final String OP_TRANSACTION = "交易";
    public static final String OP_SIGN = "签名消息";
    public static final String OP_SIGN_TYPED = "结构化签名";
    public static final String OP_RPC = "只读RPC";

    private DAppOpHistory() {}

    /**
     * 记录一条 DApp 操作（并入主日志）。
     *
     * @param ctx     上下文
     * @param origin  DApp 域名（如 uniswap.org）
     * @param opType  操作类型（OP_CONNECT 等）
     * @param detail  操作详情
     * @param success 是否成功
     */
    public static void record(Context ctx, String origin, String opType, String detail, boolean success) {
        String domain = (origin == null || origin.isEmpty()) ? "未知DApp" : origin;
        String desc = (detail == null || detail.isEmpty()) ? "" : detail;
        String result = success ? "成功" : "失败";
        // 统一并入主日志，模块名固定为 DApp操作，便于弹窗过滤与统一检索
        Logger.action(ctx, LOG_MODULE, opType + " - " + domain, desc + " - " + result);
    }

    /**
     * 读取 DApp 操作记录（最新在前）。从主日志中按模块"DApp操作"过滤。
     */
    public static List<String> load(Context ctx) {
        List<String> records = new ArrayList<>();
        if (ctx == null) return records;
        List<String> allLogs = Logger.loadLogs(ctx);
        if (allLogs == null) return records;
        for (String line : allLogs) {
            if (line != null && line.contains(" | " + LOG_MODULE + " | ")) {
                records.add(line);
            }
        }
        // Logger.loadLogs 按文件顺序（旧到新），这里翻转为最新在前
        java.util.Collections.reverse(records);
        return records;
    }

    /** 清空 DApp 操作记录（仅删除主日志中 DApp操作 模块的记录） */
    public static void clear(Context ctx) {
        Logger.removeModuleRecords(ctx, LOG_MODULE);
    }
}