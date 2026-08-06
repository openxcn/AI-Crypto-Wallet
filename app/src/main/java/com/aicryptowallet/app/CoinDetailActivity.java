package com.aicryptowallet.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 币种详情页（行情版）
 *
 * 设计目标（参考 AVE 应用）：
 *  - 独立于钱包资产，纯行情展示
 *  - 不展示转账/收款（用户从行情页进入时）
 *  - 展示：币种介绍 + K线图 + 24h 基本面 + AI 数据接入
 *
 * 数据源：
 *  - 行情/K线：CoinDetailAPI（Gate.io + Binance CF 双源，国内可访问）
 *  - 币种介绍：CoinInfo（本地 hardcode 主流币种，无需海外 API）
 *
 * 入口：HomeActivity 行情页点击币种 → 跳转本页
 */
public class CoinDetailActivity extends BaseActivity {

    public static final String EXTRA_SYMBOL = "symbol";
    public static final String EXTRA_NAME = "name";

    private String symbol, coinName;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SimpleKlineView klineView;
    private ProgressBar progressKline;

    // 当前选中的时间周期
    private String currentInterval = "1d";
    // 折线图 / 蜡烛图模式
    private boolean showAsCandle = false;

    // 当前 K 线数据缓存（供 AI 分析时使用）
    private List<SimpleKlineView.Kline> currentKlines;
    private CoinDetailAPI.Ticker24h currentTicker;
    private CoinInfo.Info currentInfo;

    private TextView btnInterval1h, btnInterval4h, btnInterval1d, btnInterval1w;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_coin_detail);

            symbol = getIntent().getStringExtra(EXTRA_SYMBOL);
            coinName = getIntent().getStringExtra(EXTRA_NAME);
            if (symbol == null || symbol.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_missing_currency_parameter), Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            if (coinName == null || coinName.isEmpty()) coinName = symbol;

            initViews();
            loadCoinInfo();
            loadMarketData();
            loadKlines();
        } catch (Exception e) {
            Logger.error(this, "币种详情", "初始化失败: " + e.getMessage(), e);
            Toast.makeText(this, getString(R.string.toast_failed_to_load, e.getMessage()), Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void initViews() {
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // 标题
        ((TextView) findViewById(R.id.tvTitle)).setText(getString(R.string.text_details, symbol.toUpperCase()));

        // 头部币种信息
        ((TextView) findViewById(R.id.tvCoinSymbol)).setText(symbol.toUpperCase());
        ((TextView) findViewById(R.id.tvCoinFullName)).setText(coinName);
        TextView tvIcon = findViewById(R.id.tvCoinIcon);
        tvIcon.setText(symbol.length() > 4 ? symbol.substring(0, 4) : symbol);

        // K 线 View
        klineView = findViewById(R.id.klineView);
        progressKline = findViewById(R.id.progressKline);

        // 时间周期按钮
        btnInterval1h = findViewById(R.id.btnInterval1h);
        btnInterval4h = findViewById(R.id.btnInterval4h);
        btnInterval1d = findViewById(R.id.btnInterval1d);
        btnInterval1w = findViewById(R.id.btnInterval1w);

        View.OnClickListener intervalClick = v -> {
            String newInterval;
            if (v.getId() == R.id.btnInterval1h) newInterval = "1h";
            else if (v.getId() == R.id.btnInterval4h) newInterval = "4h";
            else if (v.getId() == R.id.btnInterval1w) newInterval = "1w";
            else newInterval = "1d";

            if (newInterval.equals(currentInterval)) return;
            currentInterval = newInterval;
            updateIntervalButtonStyles();
            loadKlines();
        };
        btnInterval1h.setOnClickListener(intervalClick);
        btnInterval4h.setOnClickListener(intervalClick);
        btnInterval1d.setOnClickListener(intervalClick);
        btnInterval1w.setOnClickListener(intervalClick);

        // 折线/蜡烛图切换
        findViewById(R.id.btnToggleChart).setOnClickListener(v -> {
            showAsCandle = !showAsCandle;
            ((TextView) v).setText(showAsCandle ? getString(R.string.label_line) : getString(R.string.str_write_candlestick_file));
            if (currentKlines != null && !currentKlines.isEmpty()) {
                klineView.setData(currentKlines, !showAsCandle);
            }
        });

        // AI 分析按钮：进入行情页直接展示分析结果和分享图
        findViewById(R.id.btnAskAI).setOnClickListener(v -> openMarketAnalyze());

        // 官网/白皮书/区块浏览器点击事件（在 loadCoinInfo 中动态绑定 URL）
    }

    /** 更新周期按钮样式（选中态高亮） */
    private void updateIntervalButtonStyles() {
        int activeBg = R.drawable.button_primary;
        int inactiveBg = R.drawable.card_background;
        int activeColor = 0xFFFFFFFF;
        int inactiveColor = 0xFF8892b0;

        btnInterval1h.setBackgroundResource(currentInterval.equals("1h") ? activeBg : inactiveBg);
        btnInterval1h.setTextColor(currentInterval.equals("1h") ? activeColor : inactiveColor);
        btnInterval4h.setBackgroundResource(currentInterval.equals("4h") ? activeBg : inactiveBg);
        btnInterval4h.setTextColor(currentInterval.equals("4h") ? activeColor : inactiveColor);
        btnInterval1d.setBackgroundResource(currentInterval.equals("1d") ? activeBg : inactiveBg);
        btnInterval1d.setTextColor(currentInterval.equals("1d") ? activeColor : inactiveColor);
        btnInterval1w.setBackgroundResource(currentInterval.equals("1w") ? activeBg : inactiveBg);
        btnInterval1w.setTextColor(currentInterval.equals("1w") ? activeColor : inactiveColor);
    }

    /** 加载币种介绍（本地数据） */
    private void loadCoinInfo() {
        currentInfo = CoinInfo.get(symbol);
        TextView tvDesc = findViewById(R.id.tvDescription);
        TextView tvFounded = findViewById(R.id.tvFoundedYear);
        TextView tvSupply = findViewById(R.id.tvMaxSupply);
        TextView btnWebsite = findViewById(R.id.btnWebsite);
        TextView btnWhitepaper = findViewById(R.id.btnWhitepaper);
        TextView btnExplorer = findViewById(R.id.btnExplorer);

        if (currentInfo == null) {
            tvDesc.setText(getString(R.string.text_there_is_no_detailed, symbol));
            tvFounded.setText("-");
            tvSupply.setText("-");
            btnWebsite.setVisibility(View.GONE);
            btnWhitepaper.setVisibility(View.GONE);
            btnExplorer.setVisibility(View.GONE);
            return;
        }

        tvDesc.setText(currentInfo.description);
        tvFounded.setText(currentInfo.foundedYear);
        tvSupply.setText(currentInfo.maxSupply);
        ((TextView) findViewById(R.id.tvCoinFullName)).setText(currentInfo.fullName);

        // 官网
        if (currentInfo.website != null && !currentInfo.website.isEmpty()) {
            btnWebsite.setVisibility(View.VISIBLE);
            btnWebsite.setOnClickListener(v -> openUrl(currentInfo.website));
        } else {
            btnWebsite.setVisibility(View.GONE);
        }

        // 白皮书
        if (currentInfo.whitepaper != null && !currentInfo.whitepaper.isEmpty()) {
            btnWhitepaper.setVisibility(View.VISIBLE);
            btnWhitepaper.setOnClickListener(v -> openUrl(currentInfo.whitepaper));
        } else {
            btnWhitepaper.setVisibility(View.GONE);
        }

        // 区块浏览器
        if (currentInfo.explorer != null && !currentInfo.explorer.isEmpty()) {
            btnExplorer.setVisibility(View.VISIBLE);
            btnExplorer.setOnClickListener(v -> openUrl(currentInfo.explorer));
        } else {
            btnExplorer.setVisibility(View.GONE);
        }
    }

    /** 加载 24h 行情数据 */
    private void loadMarketData() {
        executor.execute(() -> {
            CoinDetailAPI.Ticker24h ticker = CoinDetailAPI.fetchTicker24h(this, symbol);
            currentTicker = ticker;
            handler.post(() -> {
                if (isFinishing()) return;
                TextView tvPrice = findViewById(R.id.tvCurrentPrice);
                TextView tvChange = findViewById(R.id.tvChangePercent);
                TextView tvHigh = findViewById(R.id.tvHigh24h);
                TextView tvLow = findViewById(R.id.tvLow24h);
                TextView tvVolume = findViewById(R.id.tvVolume24h);
                TextView tvSource = findViewById(R.id.tvDataSource);

                String currencySymbol = CurrencyManager.getCurrencySymbol(CurrencyManager.getSelectedCurrency(this));
                if (!ticker.success) {
                    tvPrice.setText(currencySymbol + " --");
                    tvChange.setText(getString(R.string.str_no_data));
                    tvChange.setTextColor(0xFF8892b0);
                    tvHigh.setText(currencySymbol + " --");
                    tvLow.setText(currencySymbol + " --");
                    tvVolume.setText(currencySymbol + " --");
                    tvSource.setText("");
                    return;
                }

                tvPrice.setText(formatPrice(ticker.lastPrice));
                String changeText = (ticker.changePercent >= 0 ? "+" : "") + String.format("%.2f", ticker.changePercent) + "%";
                tvChange.setText(changeText);
                tvChange.setTextColor(ticker.changePercent >= 0 ? 0xFF22C55E : 0xFFEF4444);
                tvHigh.setText(formatPrice(ticker.highPrice));
                tvLow.setText(formatPrice(ticker.lowPrice));
                tvVolume.setText(formatVolume(ticker.volumeQuote));
                tvSource.setText(getString(R.string.text_data_source, ticker.source));
            });
        });
    }

    /** 加载 K 线数据 */
    private void loadKlines() {
        progressKline.setVisibility(View.VISIBLE);
        // 根据周期确定 K 线数量
        int limit;
        switch (currentInterval) {
            case "1h": limit = 24; break;   // 24 小时
            case "4h": limit = 30; break;   // 5 天
            case "1w": limit = 52; break;   // 1 年
            case "1d":
            default:   limit = 90; break;   // 3 个月
        }

        final String interval = currentInterval;
        executor.execute(() -> {
            List<SimpleKlineView.Kline> klines = CoinDetailAPI.fetchKlines(this, symbol, interval, limit);
            handler.post(() -> {
                if (isFinishing()) return;
                progressKline.setVisibility(View.GONE);
                if (klines.isEmpty()) {
                    klineView.clear();
                    Toast.makeText(this, getString(R.string.toast_k_line_data_loading, symbol), Toast.LENGTH_SHORT).show();
                    return;
                }
                currentKlines = klines;
                klineView.setData(klines, !showAsCandle);
                Logger.info(this, "币种详情", symbol + " K线渲染完成 " + klines.size() + " 根 interval=" + interval);
            });
        });
    }

    /** 打开行情分析页，直接展示 AI 分析结果和分享图 */
    private void openMarketAnalyze() {
        double p = currentTicker != null ? currentTicker.lastPrice : 0.0;
        double c = currentTicker != null ? currentTicker.changePercent : 0.0;
        double h = currentTicker != null ? currentTicker.highPrice : 0.0;
        double l = currentTicker != null ? currentTicker.lowPrice : 0.0;
        double v = currentTicker != null ? currentTicker.volumeQuote : 0.0;
        String coinName = currentInfo != null && !TextUtils.isEmpty(currentInfo.fullName)
            ? currentInfo.fullName : symbol;
        String bg = currentInfo != null ? currentInfo.description : "";
        Intent intent = new Intent(this, MarketCoinAnalyzeActivity.class);
        intent.putExtra(MarketCoinAnalyzeActivity.EXTRA_SYMBOL, symbol);
        intent.putExtra(MarketCoinAnalyzeActivity.EXTRA_NAME, coinName);
        intent.putExtra(MarketCoinAnalyzeActivity.EXTRA_PRICE, p);
        intent.putExtra(MarketCoinAnalyzeActivity.EXTRA_CHANGE, c);
        intent.putExtra(MarketCoinAnalyzeActivity.EXTRA_HIGH, h);
        intent.putExtra(MarketCoinAnalyzeActivity.EXTRA_LOW, l);
        intent.putExtra(MarketCoinAnalyzeActivity.EXTRA_VOLUME, v);
        intent.putExtra(MarketCoinAnalyzeActivity.EXTRA_BACKGROUND, bg);
        startActivity(intent);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_failed_to_open_link, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private String formatPrice(double p) {
        String symbol = CurrencyManager.getCurrencySymbol(CurrencyManager.getSelectedCurrency(this));
        if (p >= 1000) return symbol + String.format("%.0f", p);
        if (p >= 1) return symbol + String.format("%.2f", p);
        if (p >= 0.01) return symbol + String.format("%.4f", p);
        return symbol + String.format("%.6f", p);
    }

    private String formatVolume(double v) {
        String symbol = CurrencyManager.getCurrencySymbol(CurrencyManager.getSelectedCurrency(this));
        if (v >= 1_000_000_000) return symbol + String.format("%.2f", v / 1_000_000_000) + "B";
        if (v >= 1_000_000) return symbol + String.format("%.2f", v / 1_000_000) + "M";
        if (v >= 1_000) return symbol + String.format("%.2f", v / 1_000) + "K";
        return symbol + String.format("%.0f", v);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!executor.isShutdown()) executor.shutdown();
    }
}