package com.aicryptowallet.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
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
    public static final String EXTRA_HIGH = "high";
    public static final String EXTRA_LOW = "low";
    public static final String EXTRA_VOLUME = "volume";
    public static final String EXTRA_BACKGROUND = "background";

    private String symbol, name, background;
    private double price, changePercent, highPrice, lowPrice, volumeQuote;
    private Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView tvSymbol, tvName, tvPrice, tvChange, tvAnalysisResult;
    private ProgressBar progressAnalysis;
    private LinearLayout layoutAnalysisActions;
    private ScrollView scrollAnalysis;
    private View btnShare;
    private ImageView ivSharePreview;
    private String currentReport = "";
    private Bitmap currentShareBitmap = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        setContentView(R.layout.activity_market_coin_analyze);

        symbol = getIntent().getStringExtra(EXTRA_SYMBOL);
        name = getIntent().getStringExtra(EXTRA_NAME);
        price = getIntent().getDoubleExtra(EXTRA_PRICE, 0);
        changePercent = getIntent().getDoubleExtra(EXTRA_CHANGE, 0);
        highPrice = getIntent().getDoubleExtra(EXTRA_HIGH, 0);
        lowPrice = getIntent().getDoubleExtra(EXTRA_LOW, 0);
        volumeQuote = getIntent().getDoubleExtra(EXTRA_VOLUME, 0);
        background = getIntent().getStringExtra(EXTRA_BACKGROUND);

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
        ivSharePreview = findViewById(R.id.ivSharePreview);

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
        btnShare.setOnClickListener(v -> shareReport());
        ivSharePreview.setOnClickListener(v -> shareReport());
    }

    private void startAnalysis() {
        progressAnalysis.setVisibility(View.VISIBLE);
        tvAnalysisResult.setVisibility(View.GONE);
        layoutAnalysisActions.setVisibility(View.GONE);
        btnShare.setVisibility(View.GONE);
        ivSharePreview.setVisibility(View.GONE);

        if (!isNetworkAvailable()) {
            progressAnalysis.setVisibility(View.GONE);
            Toast.makeText(this, "当前设备未联网", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        executor.execute(() -> {
            try {
                // 基于行情页已传入的数据直接生成本地报告
                currentReport = buildLocalReport(null);

                handler.post(() -> {
                    progressAnalysis.setVisibility(View.GONE);
                    tvAnalysisResult.setText(currentReport);
                    tvAnalysisResult.setVisibility(View.VISIBLE);
                    layoutAnalysisActions.setVisibility(View.VISIBLE);
                    btnShare.setVisibility(View.VISIBLE);
                });

                generateAndShowShareImage();

                // 后台尝试补充新闻，成功后再刷新报告
                String news = fetchNewsSummary(symbol);
                if (news != null && !news.isEmpty()) {
                    currentReport = buildLocalReport(news);
                    handler.post(() -> {
                        tvAnalysisResult.setText(currentReport);
                        generateAndShowShareImage();
                    });
                }
            } catch (Exception e) {
                Logger.error(this, "行情分析", "分析失败: " + e.getMessage(), e);
                handler.post(() -> {
                    progressAnalysis.setVisibility(View.GONE);
                    currentReport = "【币种】" + symbol + " (" + name + ")\n\n"
                        + "分析过程出现异常：\n" + e.getMessage() + "\n\n"
                        + "请检查网络连接或稍后重试。";
                    tvAnalysisResult.setText(currentReport);
                    tvAnalysisResult.setVisibility(View.VISIBLE);
                    layoutAnalysisActions.setVisibility(View.VISIBLE);
                    btnShare.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }

    /**
     * 基于传入的行情数据生成本地分析报告
     * @param newsSummary 新闻摘要，可为 null
     */
    private String buildLocalReport(String newsSummary) {
        StringBuilder sb = new StringBuilder();

        // 行情摘要（一行）
        sb.append(symbol).append(" / ").append(name).append("  |  ")
          .append(formatPrice(price)).append("  |  24h ")
          .append((changePercent >= 0 ? "+" : ""))
          .append(String.format(java.util.Locale.getDefault(), "%.2f", changePercent)).append("%\n");
        if (highPrice > 0 && lowPrice > 0) {
            sb.append("24h 高：").append(formatPrice(highPrice))
              .append("  低：").append(formatPrice(lowPrice));
            if (volumeQuote > 0) {
                sb.append("  额：").append(formatVolume(volumeQuote));
            }
            sb.append("\n\n");
        } else if (volumeQuote > 0) {
            sb.append("24h 成交额：").append(formatVolume(volumeQuote)).append("\n\n");
        } else {
            sb.append("\n");
        }

        // 1) 短期趋势
        sb.append("趋势：");
        if (changePercent > 2) {
            sb.append("短期偏强，涨幅明显。");
        } else if (changePercent < -2) {
            sb.append("短期偏弱，跌幅明显。");
        } else {
            sb.append("波动较小，以震荡为主。");
        }
        if (highPrice > 0 && lowPrice > 0 && price > 0) {
            double range = highPrice - lowPrice;
            double position = (price - lowPrice) / range;
            if (position > 0.7) sb.append("价格接近 24h 高点。");
            else if (position < 0.3) sb.append("价格接近 24h 低点。");
            else sb.append("价格位于 24h 区间中部。");
        }
        sb.append("\n\n");

        // 2) 支撑/阻力
        if (lowPrice > 0 || highPrice > 0) {
            sb.append("支撑/阻力：");
            if (lowPrice > 0) sb.append("支撑 ").append(formatPrice(lowPrice)).append("  ");
            if (highPrice > 0) sb.append("阻力 ").append(formatPrice(highPrice));
            sb.append("\n\n");
        }

        // 3) 建议
        sb.append("建议：");
        if (changePercent >= 5) {
            sb.append("涨幅较大，不宜追高，可分批减仓或持有观望。");
        } else if (changePercent > 2) {
            sb.append("偏强，可轻仓持有。");
        } else if (changePercent <= -5) {
            sb.append("跌幅较大，或有超跌反弹机会，但需控制仓位。");
        } else if (changePercent < -2) {
            sb.append("偏弱，建议观望。");
        } else {
            sb.append("震荡，建议持有观望或逢低小仓位布局。");
        }
        sb.append("\n\n");

        // 4) 新闻
        if (newsSummary != null && !newsSummary.isEmpty()) {
            sb.append("新闻：\n").append(newsSummary).append("\n\n");
        }

        // 5) 项目背景（精简）
        if (background != null && !background.isEmpty()) {
            String brief = background.length() > 120 ? background.substring(0, 120) + "…" : background;
            sb.append("项目：").append(brief).append("\n\n");
        }

        // 6) 风险提示
        sb.append("声明：以上仅为基于行情数据的建议，不构成投资建议。本应用不对任何分析建议负责，投资有风险，决策需谨慎。");
        return sb.toString();
    }

    private String fetchNewsSummary(String symbol) {
        try {
            String url = "https://min-api.cryptocompare.com/data/v2/news/?lang=EN&limit=3&categories=" + symbol;
            okhttp3.Request request = new okhttp3.Request.Builder().url(url).get().build();
            try (okhttp3.Response response = new okhttp3.OkHttpClient().newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                String resp = response.body().string();
                org.json.JSONObject json = new org.json.JSONObject(resp);
                org.json.JSONArray data = json.getJSONObject("Data").getJSONArray("Data");
                StringBuilder sb = new StringBuilder();
                int count = Math.min(3, data.length());
                for (int i = 0; i < count; i++) {
                    org.json.JSONObject item = data.getJSONObject(i);
                    sb.append("- ").append(item.optString("title", "")).append("\n");
                }
                return sb.toString().trim();
            }
        } catch (Exception e) {
            Logger.warning(this, "行情分析", "获取新闻失败: " + e.getMessage());
            return null;
        }
    }

    private String formatVolume(double v) {
        String symbol = CurrencyManager.getCurrencySymbol(CurrencyManager.getSelectedCurrency(this));
        if (v >= 1_000_000_000) return symbol + String.format(java.util.Locale.getDefault(), "%.2f", v / 1_000_000_000) + "B";
        if (v >= 1_000_000) return symbol + String.format(java.util.Locale.getDefault(), "%.2f", v / 1_000_000) + "M";
        if (v >= 1_000) return symbol + String.format(java.util.Locale.getDefault(), "%.2f", v / 1_000) + "K";
        return symbol + String.format(java.util.Locale.getDefault(), "%.2f", v);
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

    private void generateAndShowShareImage() {
        executor.execute(() -> {
            try {
                Bitmap bitmap = MarketShareGenerator.generate(this, symbol, name, price, changePercent, currentReport);
                if (bitmap == null) return;
                currentShareBitmap = bitmap;
                handler.post(() -> {
                    ivSharePreview.setImageBitmap(bitmap);
                    ivSharePreview.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                Logger.warning(this, "行情分析", "生成分享预览图失败: " + e.getMessage());
            }
        });
    }

    private void shareReport() {
        if (currentReport.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_reports_to_share), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Bitmap bitmap = currentShareBitmap;
            if (bitmap == null) {
                bitmap = MarketShareGenerator.generate(this, symbol, name, price, changePercent, currentReport);
            }
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