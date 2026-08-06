package com.aicryptowallet.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.TaskStackBuilder;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;

/**
 * AI 消息统一通知管理器
 *
 * 负责把 AI 的所有消息/事件以系统通知形式呈现：
 * - 聊天回复
 * - 工具操作记录
 * - 权限申请（ask_user）
 * - 定时任务（分析、新闻）
 * - 重大信号
 *
 * 当 AIAgentActivity 在前台时，聊天回复类通知默认不弹出，避免打扰；
 * 但操作、权限、定时任务等关键事件仍会通知。
 */
public class AINotificationHelper {

    // 通知渠道 ID
    public static final String CHANNEL_ID_PERSIST = "ai_agent_persist";
    public static final String CHANNEL_ID_CHAT = "ai_agent_chat";
    public static final String CHANNEL_ID_OPERATION = "ai_agent_operation";
    public static final String CHANNEL_ID_PERMISSION = "ai_agent_permission";
    public static final String CHANNEL_ID_SCHEDULED = "ai_agent_scheduled";
    public static final String CHANNEL_ID_ALERT = "ai_agent_alert";

    // 基础通知 ID
    public static final int NOTIFICATION_ID_PERSIST = 1001;
    private static final int BASE_CHAT_ID = 2000;
    private static final int BASE_OPERATION_ID = 3000;
    private static final int BASE_PERMISSION_ID = 4000;
    private static final int BASE_SCHEDULED_ID = 5000;
    private static final int BASE_ALERT_ID = 6000;
    private static final int BASE_ASSET_ID = 7000;

    private static int chatCounter = 0;
    private static int operationCounter = 0;
    private static int permissionCounter = 0;
    private static int scheduledCounter = 0;
    private static int alertCounter = 0;

    /**
     * 创建所有 AI 通知渠道。应用启动时调用一次即可。
     */
    public static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel persist = new NotificationChannel(
            CHANNEL_ID_PERSIST, "AI 智能体后台运行", NotificationManager.IMPORTANCE_LOW);
        persist.setDescription("保持 AI 智能体在后台运行");

        NotificationChannel chat = new NotificationChannel(
            CHANNEL_ID_CHAT, "AI 聊天消息", NotificationManager.IMPORTANCE_DEFAULT);
        chat.setDescription("AI 助手在聊天中的回复");

        NotificationChannel operation = new NotificationChannel(
            CHANNEL_ID_OPERATION, "AI 操作记录", NotificationManager.IMPORTANCE_DEFAULT);
        operation.setDescription("AI 执行的工具操作和结果");

        NotificationChannel permission = new NotificationChannel(
            CHANNEL_ID_PERMISSION, "AI 权限申请", NotificationManager.IMPORTANCE_HIGH);
        permission.setDescription("AI 在执行敏感操作前向用户申请确认");

        NotificationChannel scheduled = new NotificationChannel(
            CHANNEL_ID_SCHEDULED, "AI 定时任务", NotificationManager.IMPORTANCE_DEFAULT);
        scheduled.setDescription("AI 定时分析、新闻汇总等任务");

        NotificationChannel alert = new NotificationChannel(
            CHANNEL_ID_ALERT, "AI 重大信号", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("STRONG_BUY/STRONG_SELL 等需要立即关注的信号");

        nm.createNotificationChannels(java.util.Arrays.asList(
            persist, chat, operation, permission, scheduled, alert));
    }

    /**
     * AI 聊天回复通知。Activity 在前台时不弹通知。
     */
    public static void notifyChatReply(Context ctx, String title, String content) {
        if (isChatActivityForeground()) return;
        postNotification(ctx, CHANNEL_ID_CHAT, BASE_CHAT_ID + (++chatCounter % 100),
            title, content, NotificationCompat.PRIORITY_DEFAULT, false);
    }

    /**
     * AI 操作/工具调用通知。
     */
    public static void notifyOperation(Context ctx, String title, String content) {
        postNotification(ctx, CHANNEL_ID_OPERATION, BASE_OPERATION_ID + (++operationCounter % 100),
            title, content, NotificationCompat.PRIORITY_DEFAULT, false);
    }

    /**
     * AI 权限申请通知（高优先级）。
     */
    public static void notifyPermissionRequest(Context ctx, String title, String content) {
        postNotification(ctx, CHANNEL_ID_PERMISSION, BASE_PERMISSION_ID + (++permissionCounter % 100),
            title, content, NotificationCompat.PRIORITY_HIGH, true);
    }

    /**
     * AI 定时任务通知。
     */
    public static void notifyScheduledTask(Context ctx, String title, String content) {
        postNotification(ctx, CHANNEL_ID_SCHEDULED, BASE_SCHEDULED_ID + (++scheduledCounter % 100),
            title, content, NotificationCompat.PRIORITY_DEFAULT, false);
    }

    /**
     * AI 重大信号通知。
     */
    public static void notifyAlert(Context ctx, String title, String content) {
        postNotification(ctx, CHANNEL_ID_ALERT, BASE_ALERT_ID + (++alertCounter % 100),
            title, content, NotificationCompat.PRIORITY_HIGH, true);
    }

    /**
     * 资产变动提醒通知。
     * 点击通知直接打开该笔交易详情页（TxDetailActivity），按下返回键回到钱包资产列表（HomeActivity）。
     * 若未携带交易哈希，则退化为直接打开资产列表。
     */
    public static void notifyAssetChange(Context ctx, String title, String content,
                                         String txHash, String chain) {
        int notifId = BASE_ASSET_ID + (++alertCounter % 100);
        try {
            createChannels(ctx);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            PendingIntent pi;
            if (txHash != null && !txHash.isEmpty()) {
                // 任务栈：HomeActivity(资产列表) -> TxDetailActivity(交易详情)
                // 返回键从交易详情退回资产列表
                Intent base = new Intent(ctx, HomeActivity.class);
                base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                Intent detail = new Intent(ctx, TxDetailActivity.class);
                detail.putExtra(TxDetailActivity.EXTRA_TX_HASH, txHash);
                detail.putExtra(TxDetailActivity.EXTRA_CHAIN, chain);
                TaskStackBuilder sb = TaskStackBuilder.create(ctx);
                sb.addNextIntent(base);
                sb.addNextIntent(detail);
                pi = sb.getPendingIntent(notifId,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            } else {
                Intent base = new Intent(ctx, HomeActivity.class);
                base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                pi = PendingIntent.getActivity(ctx, notifId, base,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }

            String bigText = content != null ? content : "";
            String summary = bigText.length() > 100 ? bigText.substring(0, 100) + "..." : bigText;

            Notification notification = new NotificationCompat.Builder(ctx, CHANNEL_ID_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(summary)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();

            nm.notify(notifId, notification);
            Logger.info(ctx, "AI通知", "已推送资产变动通知: " + title);
        } catch (Exception e) {
            Logger.error(ctx, "AI通知", "推送资产变动通知失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建并显示前台服务常驻通知。
     */
    public static Notification buildPersistNotification(Context ctx, String content) {
        createChannels(ctx);
        Intent intent = new Intent(ctx, AIAgentActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(ctx, CHANNEL_ID_PERSIST)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(ctx.getString(R.string.str_ai_currency_speculation_assistant))
            .setContentText(content)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private static void postNotification(Context ctx, String channelId, int notifId,
                                          String title, String content, int priority, boolean autoCancel) {
        try {
            createChannels(ctx);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Intent intent = new Intent(ctx, AIAgentActivity.class);
            intent.putExtra("from_notification", true);
            intent.putExtra("notification_title", title);
            intent.putExtra("notification_content", content);
            PendingIntent pi = PendingIntent.getActivity(ctx, notifId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String bigText = content != null ? content : "";
            String summary = bigText.length() > 100 ? bigText.substring(0, 100) + "..." : bigText;

            Notification notification = new NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(summary)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
                .setContentIntent(pi)
                .setAutoCancel(autoCancel)
                .setPriority(priority)
                .build();

            nm.notify(notifId, notification);
            Logger.info(ctx, "AI通知", "已推送通知: " + title);
        } catch (Exception e) {
            Logger.error(ctx, "AI通知", "推送通知失败: " + e.getMessage(), e);
        }
    }

    /**
     * 判断 AIAgentActivity 是否在前台。
     */
    private static boolean isChatActivityForeground() {
        try {
            return AIAgentActivity.isForeground || AgentForegroundService.activityInForeground;
        } catch (Exception e) {
            return false;
        }
    }
}