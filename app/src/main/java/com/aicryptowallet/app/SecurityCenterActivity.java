package com.aicryptowallet.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.aicryptowallet.app.crosschain.CrossChainLimitConfig;
import java.util.List;
import java.util.Locale;

public class SecurityCenterActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        setContentView(R.layout.activity_security_center);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        refreshValues();

        findViewById(R.id.rowRiskLog).setOnClickListener(v -> showRiskLog());
        findViewById(R.id.rowExportLog).setOnClickListener(v -> exportRiskLog());
        findViewById(R.id.rowCrossChainLimit).setOnClickListener(v -> showCrossChainLimitDialog());
        findViewById(R.id.rowDailyLossLimit).setOnClickListener(v -> showDailyLossLimitDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshValues();
    }

    private void refreshValues() {
        String chain = WalletManager.getChain(this);
        List<RiskManager.RiskLogEntry> logs = RiskManager.getRiskLogs(this, chain);

        CrossChainLimitConfig crossChainConfig = new CrossChainLimitConfig(this);
        double crossSingle = crossChainConfig.getSingleLimitUsd();
        double crossDaily = crossChainConfig.getDailyLimitUsd();
        double dailyLossLimit = new RiskManager(this).getDailyLossLimit();

        TextView tvRiskLogCount = findViewById(R.id.tvRiskLogCount);
        if (tvRiskLogCount != null) {
            tvRiskLogCount.setText(getString(R.string.text_records, logs.size()));
        }

        String currencyCode = CurrencyManager.getSelectedCurrency(this);
        String symbol = CurrencyManager.getCurrencySymbol(currencyCode);

        TextView tvCrossChainLimit = findViewById(R.id.tvCrossChainLimit);
        if (tvCrossChainLimit != null) {
            tvCrossChainLimit.setText(getString(R.string.text_single_stroke_stroke_stroke, symbol, formatLimit(crossSingle), symbol, formatLimit(crossDaily)));
        }

        TextView tvDailyLossLimit = findViewById(R.id.tvDailyLossLimit);
        if (tvDailyLossLimit != null) {
            tvDailyLossLimit.setText(symbol + formatLimit(dailyLossLimit));
        }
    }

    private String formatLimit(double value) {
        if (value == Math.floor(value)) {
            return String.format(Locale.getDefault(), "%.0f", value);
        }
        return String.format(Locale.getDefault(), "%.2f", value);
    }

    private void showRiskLog() {
        String chain = WalletManager.getChain(this);
        String logText = RiskManager.exportRiskLog(this, chain);
        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_risk_action_record))
            .setMessage(logText)
            .setPositiveButton(getString(R.string.btn_off), null)
            .setNeutralButton(getString(R.string.btn_copy_log), (d, w) -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("risk_log", logText));
                    Toast.makeText(this, getString(R.string.toast_risk_log_saved_to), Toast.LENGTH_SHORT).show();
                }
            })
            .show();
        Logger.actionResult(this, "UI操作", "安全中心-查看风险日志", "已打开");
    }

    private void exportRiskLog() {
        String chain = WalletManager.getChain(this);
        String logText = RiskManager.exportRiskLog(this, chain);
        try {
            java.io.File logDir = new java.io.File(getExternalFilesDir(null), "risk_logs");
            if (!logDir.exists()) logDir.mkdirs();
            String fileName = "risk_log_" + chain + "_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                    .format(new java.util.Date()) + ".txt";
            java.io.File logFile = new java.io.File(logDir, fileName);
            java.io.FileWriter fw = new java.io.FileWriter(logFile);
            fw.write(logText);
            fw.close();
            Toast.makeText(this, getString(R.string.toast_risk_log_saved_to, logFile.getAbsolutePath()), Toast.LENGTH_LONG).show();
            Logger.action(this, "安全中心", "风险日志已导出", logFile.getAbsolutePath());
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_export_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void showCrossChainLimitDialog() {
        CrossChainLimitConfig config = new CrossChainLimitConfig(this);
        double currentSingle = config.getSingleLimitUsd();
        double currentDaily = config.getDailyLimitUsd();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(8));

        String currencyCode = CurrencyManager.getSelectedCurrency(this);

        TextView tvSingleLabel = new TextView(this);
        tvSingleLabel.setText(getString(R.string.text_single_cross_chain_limit, currencyCode));
        tvSingleLabel.setTextColor(0xFFFFFFFF);
        tvSingleLabel.setTextSize(14);
        layout.addView(tvSingleLabel);

        EditText etSingle = new EditText(this);
        etSingle.setHint(getString(R.string.hint_e_10));
        etSingle.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etSingle.setTextColor(0xFFFFFFFF);
        etSingle.setHintTextColor(0xFF9B9BA7);
        etSingle.setText(String.format(Locale.getDefault(), "%.2f", currentSingle));
        layout.addView(etSingle);

        TextView tvDailyLabel = new TextView(this);
        tvDailyLabel.setText(getString(R.string.text_daily_cross_chain_limit, currencyCode));
        tvDailyLabel.setTextColor(0xFFFFFFFF);
        tvDailyLabel.setTextSize(14);
        tvDailyLabel.setPadding(0, dpToPx(12), 0, 0);
        layout.addView(tvDailyLabel);

        EditText etDaily = new EditText(this);
        etDaily.setHint(getString(R.string.hint_e_10));
        etDaily.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etDaily.setTextColor(0xFFFFFFFF);
        etDaily.setHintTextColor(0xFF9B9BA7);
        etDaily.setText(String.format(Locale.getDefault(), "%.2f", currentDaily));
        layout.addView(etDaily);

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_cross_chain_limit_settings))
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_saving), (dialog, which) -> {
                try {
                    double single = Double.parseDouble(etSingle.getText().toString().trim());
                    double daily = Double.parseDouble(etDaily.getText().toString().trim());
                    if (single <= 0 || daily <= 0) {
                        Toast.makeText(this, getString(R.string.toast_limit_must_be_greater), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (single > daily) {
                        Toast.makeText(this, getString(R.string.toast_single_limit_cannot_be), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    config.setSingleLimitUsd(single);
                    config.setDailyLimitUsd(daily);
                    Logger.action(this, "UI操作", "跨链限额保存", "single=" + single + ", daily=" + daily);
                    Toast.makeText(this, getString(R.string.toast_cross_chain_quota_saved), Toast.LENGTH_SHORT).show();
                    refreshValues();
                } catch (NumberFormatException e) {
                    Toast.makeText(this, getString(R.string.toast_please_enter_valid_number), Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
        Logger.action(this, "UI操作", "安全中心-跨链限额", "打开设置");
    }

    private void showDailyLossLimitDialog() {
        RiskManager riskManager = new RiskManager(this);
        TradeAuthManager tradeAuthManager = new TradeAuthManager(this);
        double currentLimit = riskManager.getDailyLossLimit();
        String currencyCode = CurrencyManager.getSelectedCurrency(this);

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(8));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(getString(R.string.text_maximum_daily_loss_limit, currencyCode));
        tvLabel.setTextColor(0xFFFFFFFF);
        tvLabel.setTextSize(14);
        layout.addView(tvLabel);

        android.widget.EditText etLimit = new android.widget.EditText(this);
        etLimit.setHint(getString(R.string.hint_e_10));
        etLimit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etLimit.setTextColor(0xFFFFFFFF);
        etLimit.setHintTextColor(0xFF9B9BA7);
        etLimit.setText(String.format(Locale.getDefault(), "%.2f", currentLimit));
        layout.addView(etLimit);

        TextView tvTip = new TextView(this);
        tvTip.setText(getString(R.string.text_ai_trading_will_automatically));
        tvTip.setTextColor(0xFF9B9BA7);
        tvTip.setTextSize(12);
        tvTip.setPadding(0, dpToPx(12), 0, 0);
        layout.addView(tvTip);

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_daily_loss_limit_settings))
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_saving), (dialog, which) -> {
                try {
                    double limit = Double.parseDouble(etLimit.getText().toString().trim());
                    if (limit <= 0) {
                        Toast.makeText(this, getString(R.string.toast_limit_must_be_greater), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    riskManager.setDailyLossLimit(limit);
                    tradeAuthManager.setDailyLossLimit(limit);
                    Logger.action(this, "UI操作", "日亏损限额保存", "limit=" + limit);
                    Toast.makeText(this, getString(R.string.toast_daily_loss_limit_saved), Toast.LENGTH_SHORT).show();
                    refreshValues();
                } catch (NumberFormatException e) {
                    Toast.makeText(this, getString(R.string.toast_please_enter_valid_number), Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}