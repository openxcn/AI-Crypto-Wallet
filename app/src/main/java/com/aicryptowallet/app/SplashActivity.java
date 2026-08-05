package com.aicryptowallet.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用欢迎页。
 *
* 启动后显示 2 秒品牌页，同时后台并行完成：
 * 1. 设备安全检测（Root / 模拟器 / USB调试 / 开发者选项）
 * 2. 网络环境检测（是否疑似中国大陆）
 * 3. 钱包存在性检测
 *
 * 2 秒结束后：
 * - 若检测到设备安全隐患，优先弹窗警告“退出/继续使用（风险自担）”
 * - 若检测到大陆网络，弹窗提醒“退出/继续使用”
 * - 用户选择后，再跳转 MainActivity 或退出
 */
public class SplashActivity extends BaseActivity {

    private static final long SPLASH_DURATION_MS = 2000;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private volatile boolean networkCheckDone = false;
    private volatile boolean isMainland = false;
    private volatile boolean hasWallet = false;

    private volatile boolean securityCheckDone = false;
    private volatile List<String> securityRisks = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        startDotAnimation();
        startBackgroundChecks();
        scheduleSplashFinish();
    }

    private void startDotAnimation() {
        Animation pulse = AnimationUtils.loadAnimation(this, R.anim.splash_pulse);
        View dot1 = findViewById(R.id.dot1);
        View dot2 = findViewById(R.id.dot2);
        View dot3 = findViewById(R.id.dot3);
        if (dot1 != null) dot1.startAnimation(pulse);
        if (dot2 != null) {
            Animation pulse2 = AnimationUtils.loadAnimation(this, R.anim.splash_pulse);
            pulse2.setStartOffset(200);
            dot2.startAnimation(pulse2);
        }
        if (dot3 != null) {
            Animation pulse3 = AnimationUtils.loadAnimation(this, R.anim.splash_pulse);
            pulse3.setStartOffset(400);
            dot3.startAnimation(pulse3);
        }
    }

    private void startBackgroundChecks() {
        executor.execute(() -> {
            try {
                securityRisks = DeviceSecurityChecker.checkSecurityRisks(SplashActivity.this);
                isMainland = NetworkEnvChecker.isMainlandChina(SplashActivity.this);
                hasWallet = WalletManager.hasWallet(SplashActivity.this);
            } catch (Exception e) {
                Logger.warning(SplashActivity.this, "Splash", "后台检测异常: " + e.getMessage());
            }
            securityCheckDone = true;
            networkCheckDone = true;
        });
    }

    private void scheduleSplashFinish() {
        uiHandler.postDelayed(this::onSplashFinished, SPLASH_DURATION_MS);
    }

    private void onSplashFinished() {
        // 如果后台检测还没完成，再等待一小段时间（最多 1.5 秒）
        if (!networkCheckDone || !securityCheckDone) {
            uiHandler.postDelayed(this::onSplashFinished, 300);
            return;
        }

        // 优先提示设备安全隐患
        if (securityRisks != null && !securityRisks.isEmpty()) {
            showSecurityWarning();
            return;
        }

        if (isMainland) {
            showNetworkWarning();
        } else {
            goNext();
        }
    }

    private void showSecurityWarning() {
        StringBuilder sb = new StringBuilder();
        sb.append("检测到当前设备存在以下安全隐患，继续使用可能导致资产被盗：\n\n");
        for (int i = 0; i < securityRisks.size(); i++) {
            sb.append(i + 1).append(". ").append(securityRisks.get(i)).append("\n");
        }
        sb.append("\n建议在未 Root、关闭 USB 调试和开发者选项的安全设备上使用，或更换设备。");

        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_device_security_warning))
            .setMessage(sb.toString())
            .setCancelable(false)
            .setPositiveButton(getString(R.string.btn_quit), (dialog, which) -> {
                dialog.dismiss();
                finish();
            })
            .setNegativeButton(getString(R.string.label_continue_to_use_at_your_own_risk), (dialog, which) -> {
                dialog.dismiss();
                if (isMainland) {
                    showNetworkWarning();
                } else {
                    goNext();
                }
            })
            .show();
    }

    private void showNetworkWarning() {
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_network_environment_reminder))
            .setMessage(getString(R.string.msg_detecting_that_the_current))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.btn_quit), (dialog, which) -> {
                dialog.dismiss();
                finish();
            })
            .setNegativeButton(getString(R.string.label_no_continue), (dialog, which) -> {
                dialog.dismiss();
                goNext();
            })
            .show();
    }

    private void goNext() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("has_wallet", hasWallet);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        uiHandler.removeCallbacksAndMessages(null);
    }
}