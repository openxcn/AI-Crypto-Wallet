package com.aicryptowallet.app.crosschain;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import com.aicryptowallet.app.Logger;
import com.aicryptowallet.app.R;

/**
 * 跨链兑换最高优先级风险提示弹窗
 * 使用 SYSTEM_ALERT_WINDOW，确保即使 App 在后台也能弹出
 */
public class CrossChainRiskDialog {

    public interface Callback {
        void onConfirmed();
        void onCancelled();
    }

    /**
     * 显示首次使用风险确认弹窗（系统级覆盖）
     */
    public static void showRiskConfirm(Context ctx, String provider, String routeDesc,
                                        double amountUsd, Callback callback) {
        Context appCtx = ctx.getApplicationContext();
        if (!Settings.canDrawOverlays(appCtx)) {
            // 没有悬浮窗权限，回退到普通 AlertDialog + 高优先级通知
            Logger.warning(appCtx, "CrossChain", "无悬浮窗权限，回退普通弹窗");
            showNormalDialog(ctx, provider, routeDesc, amountUsd, callback);
            return;
        }

        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            try {
                WindowManager wm = (WindowManager) appCtx.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) {
                    showNormalDialog(ctx, provider, routeDesc, amountUsd, callback);
                    return;
                }

                View view = LayoutInflater.from(appCtx).inflate(R.layout.dialog_cross_chain_risk, null);
                TextView tvTitle = view.findViewById(R.id.tvRiskTitle);
                TextView tvContent = view.findViewById(R.id.tvRiskContent);
                Button btnConfirm = view.findViewById(R.id.btnRiskConfirm);
                Button btnCancel = view.findViewById(R.id.btnRiskCancel);

                tvTitle.setText(ctx.getString(R.string.text_cross_chain_exchange_risk));
                StringBuilder sb = new StringBuilder();
                sb.append("本次操作将通过【").append(provider).append("】完成跨链兑换。\n\n");
                sb.append("兑换路径：").append(routeDesc).append("\n");
                sb.append("预估金额：$").append(String.format("%.2f", amountUsd)).append("\n\n");
                sb.append("【重要提示】\n");
                sb.append("1. 跨链兑换依赖第三方桥接服务，存在资金延迟到账或桥接合约风险；\n");
                sb.append("2. 实际到账数量可能因滑点、Gas 波动而变化；\n");
                sb.append("3. 超出 AI 自动限额的部分，建议你手动去其他平台兑换；\n");
                sb.append("4. 请确认已了解风险并自愿继续。\n");
                tvContent.setText(sb.toString());

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                );
                params.gravity = Gravity.CENTER;

                View[] root = new View[]{view};
                btnConfirm.setOnClickListener(v -> {
                    try {
                        wm.removeView(root[0]);
                    } catch (Exception ignored) {}
                    Logger.action(appCtx, "CrossChain", "用户确认跨链风险", null);
                    if (callback != null) callback.onConfirmed();
                });
                btnCancel.setOnClickListener(v -> {
                    try {
                        wm.removeView(root[0]);
                    } catch (Exception ignored) {}
                    Logger.action(appCtx, "CrossChain", "用户取消跨链风险", null);
                    if (callback != null) callback.onCancelled();
                });

                wm.addView(view, params);
                Logger.info(appCtx, "CrossChain", "已显示系统级跨链风险提示弹窗");
            } catch (Exception e) {
                Logger.error(appCtx, "CrossChain", "系统级弹窗失败", e);
                showNormalDialog(ctx, provider, routeDesc, amountUsd, callback);
            }
        });
    }

    /**
     * 显示超额提示弹窗：建议手动去其他平台
     */
    public static void showOverLimitSuggestion(Context ctx, String reason, Callback callback) {
        Context appCtx = ctx.getApplicationContext();
        String msg = "【超出 AI 自动跨链限额】\n\n" + reason
            + "\n\n建议：超过限额的部分请手动使用 ChangeNOW、Changelly 等外部平台完成兑换，"
            + "或前往设置提高限额。";

        if (Settings.canDrawOverlays(appCtx)) {
            Handler main = new Handler(Looper.getMainLooper());
            main.post(() -> {
                try {
                    WindowManager wm = (WindowManager) appCtx.getSystemService(Context.WINDOW_SERVICE);
                    if (wm == null) throw new Exception("WindowManager null");

                    View view = LayoutInflater.from(appCtx).inflate(R.layout.dialog_cross_chain_risk, null);
                    TextView tvTitle = view.findViewById(R.id.tvRiskTitle);
                    TextView tvContent = view.findViewById(R.id.tvRiskContent);
                    Button btnConfirm = view.findViewById(R.id.btnRiskConfirm);
                    Button btnCancel = view.findViewById(R.id.btnRiskCancel);

                    tvTitle.setText(ctx.getString(R.string.text_auto_limit_exceeded));
                    tvContent.setText(msg);
                    btnConfirm.setText(ctx.getString(R.string.text_go_to_external_platform));
                    btnCancel.setText(ctx.getString(R.string.btn_s_decline));

                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    );
                    params.gravity = Gravity.CENTER;

                    View[] root = new View[]{view};
                    btnConfirm.setOnClickListener(v -> {
                        try { wm.removeView(root[0]); } catch (Exception ignored) {}
                        // 打开 ChangeNOW 推荐链接
                        String url = "https://changenow.io/?from=usdtbsc&to=trx&amount=1";
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        appCtx.startActivity(intent);
                        if (callback != null) callback.onCancelled();
                    });
                    btnCancel.setOnClickListener(v -> {
                        try { wm.removeView(root[0]); } catch (Exception ignored) {}
                        if (callback != null) callback.onCancelled();
                    });

                    wm.addView(view, params);
                } catch (Exception e) {
                    showNormalMessage(ctx, msg, callback);
                }
            });
        } else {
            showNormalMessage(ctx, msg, callback);
        }
    }

    private static void showNormalDialog(Context ctx, String provider, String routeDesc,
                                          double amountUsd, Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            try {
                new AlertDialog.Builder(ctx)
                    .setTitle(ctx.getString(R.string.text_cross_chain_exchange_risk))
                    .setMessage(ctx.getString(R.string.msg_cross_chain_risk_normal, provider, routeDesc, String.format("%.2f", amountUsd)))
                    .setCancelable(false)
                    .setPositiveButton(ctx.getString(R.string.str_i_understand_the_risks), (d, w) -> {
                        if (callback != null) callback.onConfirmed();
                    })
                    .setNegativeButton(ctx.getString(R.string.str_s_decline), (d, w) -> {
                        if (callback != null) callback.onCancelled();
                    })
                    .show();
            } catch (Exception e) {
                Logger.error(ctx, "CrossChain", "普通弹窗也失败", e);
                if (callback != null) callback.onCancelled();
            }
        });
    }

    private static void showNormalMessage(Context ctx, String msg, Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            try {
                new AlertDialog.Builder(ctx)
                    .setTitle(ctx.getString(R.string.title_hint))
                    .setMessage(msg)
                    .setCancelable(false)
                    .setPositiveButton(ctx.getString(R.string.btn_got_it), (d, w) -> {
                        if (callback != null) callback.onCancelled();
                    })
                    .show();
            } catch (Exception e) {
                if (callback != null) callback.onCancelled();
            }
        });
    }
}