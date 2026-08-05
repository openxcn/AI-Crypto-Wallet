package com.aicryptowallet.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MarketCoinAnalyzeActivity extends BaseActivity {

    public static final String EXTRA_SYMBOL = "symbol";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_PRICE = "price";
    public static final String EXTRA_CHANGE = "change";

    private String symbol, name;
    private double price, changePercent;
    private Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView tvSymbol, tvName, tvPrice, tvChange, tvAnalysisResult;
    private ProgressBar progressAnalysis;
    private LinearLayout layoutAnalysisActions;
    private ScrollView scrollAnalysis;
    private View btnShare;
    private String currentReport = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        setContentView(R.layout.activity_market_coin_analyze);

        symbol = getIntent().getStringExtra(EXTRA_SYMBOL);
        name = getIntent().getStringExtra(EXTRA_NAME);
        price = getIntent().getDoubleExtra(EXTRA_PRICE, 0);
        changePercent = getIntent().getDoubleExtra(EXTRA_CHANGE, 0);

        initViews();
        startAnalysis();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
    }

    private void initViews() {
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        tvSymbol = findViewById(R.id.tvSymbol);
        tvName = findViewById(R.id.tvName);
        tvPrice = findViewById(R.id.tvPrice);
        tvChange = findViewById(R.id.tvChange);
        tvAnalysisResult = findViewById(R.id.tvAnalysisResult);
        progressAnalysis = findViewById(R.id.progressAnalysis);
        layoutAnalysisActions = findViewById(R.id.layoutAnalysisActions);
        scrollAnalysis = findViewById(R.id.scrollAnalysis);
        btnShare = findViewById(R.id.btnShare);

        tvSymbol.setText(symbol);
        tvName.setText(name);
        tvPrice.setText(formatPrice(price));

        String changeText = (changePercent >= 0 ? "+" : "") + String.format(java.util.Locale.getDefault(), "%.2f", changePercent) + "%";
        tvChange.setText(changeText);
        tvChange.setTextColor(changePercent >= 0 ? 0xFF34C759 : 0xFFFF453A);

        ImageView ivLogo = findViewById(R.id.ivLogo);
        TextView tvLogo = findViewById(R.id.tvLogo);
        TokenLogoLoader.load(this, ivLogo, symbol, "", tvLogo);

        findViewById(R.id.btnCopyReport).setOnClickListener(v -> copyReport());
        findViewById(R.id.btnAiDialog).setOnClickListener(v -> openAiDialog());
        btnShare.setOnClickListener(v -> shareReport());
    }

    private void startAnalysis() {
        progressAnalysis.setVisibility(View.VISIBLE);
        tvAnalysisResult.setVisibility(View.GONE);
        layoutAnalysisActions.setVisibility(View.GONE);
        btnShare.setVisibility(View.GONE);

        executor.execute(() -> {
            try {
                String chain = WalletManager.getChain(this);
                MarketData data = MultiChainMarketData.getKlines(chain, "1h", 100);
                AIAnalyzer analyzer = new AIAnalyzer();
                TradingSignal signal = analyzer.analyze(this, data, chain);

                currentReport = buildReport(signal);

                handler.post(() -> {
                    progressAnalysis.setVisibility(View.GONE);
                    tvAnalysisResult.setText(currentReport);
                    tvAnalysisResult.setVisibility(View.VISIBLE);
                    layoutAnalysisActions.setVisibility(View.VISIBLE);
                    btnShare.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                Logger.error(this, "行情分析", "分析失败: " + e.getMessage(), e);
                handler.post(() -> {
                    progressAnalysis.setVisibility(View.GONE);
                    tvAnalysisResult.setText(getString(R.string.text_ai_analysis, e.getMessage()));
                    tvAnalysisResult.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private String buildReport(TradingSignal signal) {
        StringBuilder sb = new StringBuilder();
        sb.append("【币种】").append(symbol).append(" (").append(name).append(")\n");
        sb.append("【当前价格】").append(formatPrice(price)).append("\n");
        sb.append("【24H 涨跌】").append((changePercent >= 0 ? "+" : "")).append(String.format(java.util.Locale.getDefault(), "%.2f", changePercent)).append("%\n\n");
        sb.append("【AI 信号】").append(signalTypeText(signal.type)).append("\n");
        int confidence = (int) Math.round(Math.max(signal.buyRatio, signal.sellRatio) * 100);
        sb.append("【置信度】").append(confidence).append("%\n");
        sb.append("【建议仓位】买 ").append(String.format(java.util.Locale.getDefault(), "%.0f", signal.buyRatio * 100))
          .append("% / 卖 ").append(String.format(java.util.Locale.getDefault(), "%.0f", signal.sellRatio * 100)).append("%\n\n");
        sb.append("【分析理由】\n").append(signal.reason);
        return sb.toString();
    }

    private String signalTypeText(TradingSignal.SignalType type) {
        switch (type) {
            case STRONG_BUY: return "强烈买入";
            case BUY: return "买入";
            case HOLD: return "持有观望";
            case SELL: return "卖出";
            case STRONG_SELL: return "强烈卖出";
            default: return "观望";
        }
    }

    private String formatPrice(double price) {
        String symbol = CurrencyManager.getCurrencySymbol(CurrencyManager.getSelectedCurrency(this));
        if (price >= 1) return symbol + String.format(java.util.Locale.getDefault(), "%,.2f", price);
        if (price >= 0.01) return symbol + String.format(java.util.Locale.getDefault(), "%.4f", price);
        return symbol + String.format(java.util.Locale.getDefault(), "%.6f", price);
    }

    private void copyReport() {
        if (currentReport.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("analysis", currentReport));
            Toast.makeText(this, getString(R.string.toast_report_copied), Toast.LENGTH_SHORT).show();
        }
    }

    private void openAiDialog() {
        Intent intent = new Intent(this, AIAgentActivity.class);
        intent.putExtra("preset_message", currentReport);
        startActivity(intent);
    }

    private void shareReport() {
        if (currentReport.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_reports_to_share), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Bitmap bitmap = MarketShareGenerator.generate(this, symbol, name, price, changePercent, currentReport);
            if (bitmap == null) {
                Toast.makeText(this, getString(R.string.toast_failed_to_generate_share), Toast.LENGTH_SHORT).show();
                return;
            }
            java.io.File cacheDir = new java.io.File(getCacheDir(), "share");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            String fileName = "行情分析_" + symbol + "_" + System.currentTimeMillis() + ".png";
            java.io.File file = new java.io.File(cacheDir, fileName);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();

            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", file);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("image/png");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, getString(R.string.str_share)));
        } catch (Exception e) {
            Logger.error(this, "行情分析分享", "失败: " + e.getMessage(), e);
            Toast.makeText(this, getString(R.string.toast_failed_to_share, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }
}