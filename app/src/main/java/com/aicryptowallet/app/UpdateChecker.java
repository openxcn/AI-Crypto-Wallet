/*
 * Copyright (C) 2026 红魔团队 (Red Devil Team)
 *
 * This software is proprietary and confidential.
 * Unauthorized copying, distribution, or modification is strictly prohibited.
 *
 * Licensed to: Authorized Users Only
 * Authorization required: Contact aibgsps@gmail.com
 */
package com.aicryptowallet.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 应用自更新检测器。
 *
 * 作用：
 * - 每次 App 启动时通过 GitHub 公开的 /releases/latest API 获取仓库最新版本号，
 *   与本地已安装版本号对比。若云端更高，则提示用户升级。
 * - 提醒形式：前台升级对话框 + 后台通知；同一天内对同一版本只提示一次（当日去重），
 *   避免重复打扰。
 * - 点击通知/对话框按钮跳转官方下载页（GitHub 动态 latest 下载链接）。
 *
 * 设计说明：
 * - 完全依赖 GitHub 免费空间，零成本；无任何自建服务器。
 * - 未联网、接口异常或版本相同均静默跳过，不影响任何现有功能。
 * - 切换 debug/release 后本机已装版本不同，仅当云端版本真实更高时才提示，避免误报。
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateCheck";

    /** 最新版本探测接口（GitHub 公开 API） */
    private static final String RELEASES_LATEST_API =
        "https://api.github.com/repos/openxcn/AI-Crypto-Wallet/releases/latest";

    /** 官方下载地址（GitHub 动态 latest 链接，始终指向最新发布包） */
    private static final String DOWNLOAD_URL =
        "https://github.com/openxcn/AI-Crypto-Wallet/releases/latest/download/AICryptoWallet-latest-release.apk";

    private static final String PREFS = "update_check";
    private static final String KEY_LAST_PROMPT_VERSION = "last_prompt_version";
    private static final String KEY_LAST_PROMPT_DATE = "last_prompt_date"; // yyyyMMdd
    private static final String KEY_PENDING_VERSION = "pending_update_version";
    private static final String KEY_DIALOG_DATE = "dialog_date"; // yyyyMMdd

    private static final String CHANNEL_ID_UPDATE = "app_update";

    private static final int NOTIF_ID_UPDATE = 3001;

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(8000, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(8000, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build();

    private static final AtomicBoolean checking = new AtomicBoolean(false);

    private static final ExecutorService executor =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "update-checker");
            t.setDaemon(true);
            return t;
        });

    private UpdateChecker() {
    }

    /**
     * 启动时触发一次异步更新检测。
     * 仅当线上版本高于本地已安装版本、且当日未对该版本提示过时才提醒。
     */
    public static void checkOnLaunch(final Context context) {
        final Context app = context != null ? context.getApplicationContext() : null;
        if (app == null || !checking.compareAndSet(false, true)) return;

        executor.submit(() -> {
            try {
                doCheck(app);
            } finally {
                checking.set(false);
            }
        });
    }

    private static void doCheck(Context app) {
        try {
            if (!RELEASES_LATEST_API.startsWith("https://api.github.com/")) return;

            Request req = new Request.Builder().url(RELEASES_LATEST_API)
                .header("User-Agent", "AICryptoWallet").build();
            String body;
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return;
                body = resp.body().string();
            }
            if (body == null || body.isEmpty()) return;

            JSONObject root = new JSONObject(body);
            String tag = root.optString("tag_name", "").trim();
            // 例如 "v3.0.13"
            String remoteVersion = stripLeadingV(tag);
            if (remoteVersion.isEmpty()) return;

            String localVersion = getLocalVersionName(app);
            if (localVersion == null || localVersion.isEmpty()) return;

            if (!isNewer(remoteVersion, localVersion)) return; // 无新版本

            // 当日对同一版本去重
            SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String today = new java.text.SimpleDateFormat("yyyyMMdd",
                java.util.Locale.US).format(new java.util.Date());
            if (remoteVersion.equals(prefs.getString(KEY_LAST_PROMPT_VERSION, ""))
                    && today.equals(prefs.getString(KEY_LAST_PROMPT_DATE, ""))) {
                return;
            }
            prefs.edit()
                .putString(KEY_LAST_PROMPT_VERSION, remoteVersion)
                .putString(KEY_LAST_PROMPT_DATE, today)
                .putString(KEY_PENDING_VERSION, remoteVersion)
                .apply();

            Logger.success(app, TAG, "检测到新版本: " + localVersion + " -> " + remoteVersion);

            // 后台通知提醒（App 在前台/后台均可收到）
            postUpdateNotification(app, remoteVersion);
        } catch (Exception e) {
            Logger.warning(app, TAG, "更新检测失败（保持现状）: " + e.getMessage());
        }
    }

    /**
     * 前台升级对话框：供主页面（如 HomeActivity.onResume）调用。
     * 若有待提示的新版本且当日未弹过对话框，则弹出升级提示。
     */
    public static void maybeShowDialog(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String pending = prefs.getString(KEY_PENDING_VERSION, "");
            if (pending.isEmpty()) return;

            String today = new java.text.SimpleDateFormat("yyyyMMdd",
                java.util.Locale.US).format(new java.util.Date());
            if (today.equals(prefs.getString(KEY_DIALOG_DATE, ""))) return; // 当日已弹过

            String localVersion = getLocalVersionName(activity);
            if (localVersion == null || !isNewer(pending, localVersion)) {
                // 本地已是最新，清理残留
                prefs.edit().remove(KEY_PENDING_VERSION).apply();
                return;
            }

            prefs.edit().putString(KEY_DIALOG_DATE, today).apply();
            showUpdateDialog(activity, localVersion, pending);
        } catch (Exception e) {
            Logger.warning(activity, TAG, "升级对话框展示失败: " + e.getMessage());
        }
    }

    private static void showUpdateDialog(Activity activity, String localVersion, String remoteVersion) {
        if (activity == null || activity.isFinishing()) return;

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 4));

        TextView tv = new TextView(activity);
        tv.setTextColor(0xFF1F2937);
        tv.setTextSize(14);
        tv.setLineSpacing(dp(activity, 3), 1.0f);
        tv.setText("当前版本 " + localVersion + "\n发现新版本 " + remoteVersion + "\n\n"
            + "升级可获取最新功能、安全修复与 AI 能力优化，建议及时更新。");
        layout.addView(tv, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(activity)
            .setTitle("发现新版本")
            .setView(layout)
            .setNegativeButton("稍后再说", null)
            .setPositiveButton("立即升级", (d, w) ->
                openDownloadPage(activity, DOWNLOAD_URL))
            .show();
    }

    private static void postUpdateNotification(Context ctx, String remoteVersion) {
        try {
            createChannel(ctx);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            String title = "发现新版本 v" + remoteVersion;
            String content = "已为你准备好最新版，点击前往下载（免费）.";

            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL));
            PendingIntent pi = PendingIntent.getActivity(ctx, NOTIF_ID_UPDATE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID_UPDATE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content
                    + "\n\n注意：升级需安装新安装包，请先备份并保管好助记词。"))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            nm.notify(NOTIF_ID_UPDATE, b.build());
            Logger.info(ctx, TAG, "已推送更新通知");
        } catch (Exception e) {
            Logger.error(ctx, TAG, "更新通知推送失败: " + e.getMessage(), e);
        }
    }

    private static void createChannel(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID_UPDATE, "应用更新", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("检测到新版本时提醒升级");
            nm.createNotificationChannel(ch);
        }
    }

    private static void openDownloadPage(Context ctx, String url) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(ctx, "无法打开下载页，请前往官网获取最新版", Toast.LENGTH_LONG).show();
        }
    }

    /** 比较远端版本是否严格高于本地版本（支持 x.y.z 三段） */
    private static boolean isNewer(String remote, String local) {
        int[] r = parseVersion(remote);
        int[] l = parseVersion(local);
        if (r == null || l == null) return false;
        for (int i = 0; i < 3; i++) {
            if (r[i] > l[i]) return true;
            if (r[i] < l[i]) return false;
        }
        return false;
    }

    private static int[] parseVersion(String v) {
        if (v == null) return null;
        String[] parts = v.split("\\.");
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (Exception e) {
                return null;
            }
        }
        return out;
    }

    private static String stripLeadingV(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.startsWith("v")) s = s.substring(1);
        return s;
    }

    @SuppressLint("ObsoleteSdkInt")
    private static String getLocalVersionName(Context ctx) {
        try {
            // 优先用 BuildConfig（debug/release 均准确反映 version.properties）
            return BuildConfig.VERSION_NAME;
        } catch (Throwable t) {
            try {
                android.content.pm.PackageManager pm = ctx.getPackageManager();
                android.content.pm.PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), 0);
                return pi.versionName;
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static int dp(Context ctx, int dpv) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dpv,
            ctx.getResources().getDisplayMetrics());
    }
}