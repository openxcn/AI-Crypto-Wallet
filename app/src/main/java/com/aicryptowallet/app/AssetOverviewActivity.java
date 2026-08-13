package com.aicryptowallet.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AssetOverviewActivity extends BaseActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private SwipeRefreshLayout swipeRefresh;
    private Map<String, Double> cachedPrices;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        setContentView(R.layout.activity_asset_overview);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setOnRefreshListener(() -> loadAllAssets());

        loadAllAssets();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void loadAllAssets() {
        setLoadingVisible(true, 0, "正在计算总资产...");

        executor.execute(() -> {
            try {
                List<WalletManager.WalletInfo> allWallets = WalletManager.getAllWallets(this);
                if (allWallets == null || allWallets.isEmpty()) {
                    handler.post(() -> showEmptyState());
                    return;
                }

                // 阶段1：获取价格表
                Map<String, Double> prices;
                try {
                    prices = ChainAPI.getPrices(this);
                    cachedPrices = prices;
                } catch (Exception e) {
                    prices = new java.util.HashMap<>();
                    cachedPrices = prices;
                }

                // 阶段2：快速计算所有钱包原生币总资产（先显示）
                final java.util.List<WalletAssetData> walletDataList = new java.util.ArrayList<>();
                double quickTotal = 0;
                int totalCount = allWallets.size();
                for (int i = 0; i < allWallets.size(); i++) {
                    final int idx = i;
                    WalletManager.WalletInfo w = allWallets.get(i);
                    if (w == null || w.address == null || w.address.isEmpty()) continue;
                    String chain = w.chain != null ? w.chain : "ETH";
                    try {
                        WalletAssetData wad = new WalletAssetData();
                        wad.wallet = w;
                        wad.chain = chain;

                        double nativeBalance = ChainAPI.getNativeBalance(this, chain, w.address);
                        double nativePrice = ChainAPI.resolveNativePrice(prices, this, chain);
                        double nativeValue = nativeBalance * nativePrice;
                        wad.nativeBalance = nativeBalance;
                        wad.nativeValue = nativeValue;
                        wad.nativeSymbol = chain;
                        wad.nativeName = ChainAPI.getChainName(chain);
                        wad.walletTotal = nativeValue;

                        walletDataList.add(wad);
                        quickTotal += nativeValue;

                        final int progress = (int) (((idx + 1) * 100.0) / totalCount);
                        final double showTotal = quickTotal;
                        handler.post(() -> {
                            updateTotalAndProgress(showTotal, progress, "正在计算总资产 (" + (idx + 1) + "/" + totalCount + ")");
                        });
                    } catch (Exception e) {
                        Logger.warning(this, "资产总览", "快速估算钱包失败 " + w.name + ": " + e.getMessage());
                    }
                }

                // 阶段3：先显示钱包列表框架（只含原生币）
                final double stage1Total = quickTotal;
                handler.post(() -> {
                    renderWalletSections(walletDataList, false);
                });

                // 阶段4：后台逐个加载每个钱包的详细代币余额
                for (int i = 0; i < walletDataList.size(); i++) {
                    final int index = i;
                    WalletAssetData wad = walletDataList.get(i);
                    try {
                        List<String[]> tokens = ChainAPI.getAllTokenBalances(this, wad.chain, wad.wallet.address, false);
                        if (tokens != null) {
                            for (String[] token : tokens) {
                                if (token.length < 4) continue;
                                try {
                                    double balance = Double.parseDouble(token[2]);
                                    double price = ChainAPI.resolveTokenPrice(prices, this, wad.chain, token[0]);
                                    double value = balance * price;
                                    if (value > 0.01) { // 忽略极小金额
                                        TokenAsset ta = new TokenAsset();
                                        ta.symbol = token[0];
                                        ta.name = token[1];
                                        ta.balance = balance;
                                        ta.value = value;
                                        ta.contract = token.length > 4 ? token[4] : "";
                                        wad.tokens.add(ta);
                                    }
                                    wad.walletTotal += value;
                                } catch (Exception ignored) {}
                            }
                        }

                        final int progress = (int) (((index + 1) * 100.0) / walletDataList.size());
                        final double currentTotal = recalcGrandTotal(walletDataList);
                        handler.post(() -> {
                            updateWalletSectionTokens(index, walletDataList.get(index));
                            updateTotalAndProgress(currentTotal, progress, "正在加载代币明细 (" + (index + 1) + "/" + walletDataList.size() + ")");
                        });
                    } catch (Exception e) {
                        Logger.warning(this, "资产总览", "加载代币失败 " + wad.wallet.name + ": " + e.getMessage());
                    }
                }

                final double finalTotal = recalcGrandTotal(walletDataList);
                handler.post(() -> {
                    updateTotalAndProgress(finalTotal, 100, "加载完成");
                    setLoadingVisible(false, 100, null);
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                });

            } catch (Exception e) {
                Logger.error(this, "资产总览", "加载失败: " + e.getMessage(), e);
                handler.post(() -> {
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    setLoadingVisible(false, 0, null);
                    Toast.makeText(this, getString(R.string.toast_failed_to_load), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showEmptyState() {
        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
        setLoadingVisible(false, 0, null);
        TextView tvTotal = findViewById(R.id.tvTotalValue);
        if (tvTotal != null) tvTotal.setText(CurrencyManager.formatFiat(this, 0));
        LinearLayout container = findViewById(R.id.walletAssetsContainer);
        if (container != null) {
            container.removeAllViews();
            TextView empty = new TextView(AssetOverviewActivity.this);
            empty.setText(getString(R.string.text_no_nfts));
            empty.setTextColor(0xFF6E6E7A);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dpToPx(32), 0, 0);
            container.addView(empty);
        }
    }

    private double recalcGrandTotal(java.util.List<WalletAssetData> list) {
        double total = 0;
        for (WalletAssetData wad : list) total += wad.walletTotal;
        return total;
    }

    private void setLoadingVisible(boolean visible, int progress, String text) {
        ProgressBar bar = findViewById(R.id.progressLoading);
        TextView tv = findViewById(R.id.tvLoading);
        TextView tvHint = findViewById(R.id.tvLoadingHint);
        if (bar != null) {
            bar.setVisibility(visible ? View.VISIBLE : View.GONE);
            bar.setProgress(progress);
        }
        if (tv != null) {
            tv.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (text != null) tv.setText(text);
        }
        if (tvHint != null) {
            tvHint.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void updateTotalAndProgress(double total, int progress, String text) {
        TextView tvTotal = findViewById(R.id.tvTotalValue);
        if (tvTotal != null) tvTotal.setText(CurrencyManager.formatFiat(this, total));
        ProgressBar bar = findViewById(R.id.progressLoading);
        if (bar != null) bar.setProgress(progress);
        TextView tv = findViewById(R.id.tvLoading);
        if (tv != null && text != null) tv.setText(text);
    }

    private void renderWalletSections(java.util.List<WalletAssetData> list, boolean withTokens) {
        LinearLayout container = findViewById(R.id.walletAssetsContainer);
        if (container == null) return;
        container.removeAllViews();
        for (WalletAssetData wad : list) {
            container.addView(createWalletSection(wad, withTokens));
        }
    }

    private void updateWalletSectionTokens(int index, WalletAssetData wad) {
        LinearLayout container = findViewById(R.id.walletAssetsContainer);
        if (container == null || index < 0 || index >= container.getChildCount()) return;
        View section = container.getChildAt(index);
        if (!(section instanceof LinearLayout)) return;
        // 找到该钱包的代币容器并重建
        LinearLayout tokenContainer = findTokenContainer((LinearLayout) section);
        if (tokenContainer != null) {
            tokenContainer.removeAllViews();
            for (TokenAsset ta : wad.tokens) {
                tokenContainer.addView(createAssetRow(ta.symbol, ta.name,
                    ChainAPI.formatAmount(ta.balance),
                    CurrencyManager.formatFiat(this, ta.value), ta.contract, false));
            }
        }
        // 更新钱包头部总资产
        TextView tvWalletTotal = findWalletTotalView((LinearLayout) section);
        if (tvWalletTotal != null) tvWalletTotal.setText(CurrencyManager.formatFiat(this, wad.walletTotal));
    }

    private LinearLayout findTokenContainer(LinearLayout section) {
        for (int i = 0; i < section.getChildCount(); i++) {
            View child = section.getChildAt(i);
            if (child instanceof LinearLayout && child.getTag() != null && "tokens".equals(child.getTag())) {
                return (LinearLayout) child;
            }
        }
        return null;
    }

    private TextView findWalletTotalView(LinearLayout section) {
        if (section.getChildCount() == 0) return null;
        View header = section.getChildAt(0);
        if (!(header instanceof LinearLayout)) return null;
        LinearLayout h = (LinearLayout) header;
        for (int i = 0; i < h.getChildCount(); i++) {
            View child = h.getChildAt(i);
            if (child instanceof TextView && "wallet_total".equals(child.getTag())) {
                return (TextView) child;
            }
        }
        return null;
    }

    private LinearLayout createWalletSection(WalletAssetData wad, boolean withTokens) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundResource(R.drawable.card_background);
        section.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sectionParams.bottomMargin = dpToPx(16);
        section.setLayoutParams(sectionParams);

        // 钱包头部：名称 + 链 + 地址
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView icon = new TextView(this);
        icon.setText("💼");
        icon.setTextSize(20);
        icon.setGravity(Gravity.CENTER);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(40), dpToPx(40)));
        header.addView(icon);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dpToPx(10), 0, 0, 0);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView name = new TextView(this);
        name.setText(wad.wallet.name);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(15);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(name);

        TextView addr = new TextView(this);
        addr.setText(wad.wallet.getShortAddress() + " | " + wad.chain);
        addr.setTextColor(0xFF9B9BA7);
        addr.setTextSize(12);
        addr.setPadding(0, dpToPx(2), 0, 0);
        info.addView(addr);

        header.addView(info);

        TextView val = new TextView(this);
        val.setTag("wallet_total");
        val.setText(CurrencyManager.formatFiat(this, wad.walletTotal));
        val.setTextColor(0xFFFFFFFF);
        val.setTextSize(15);
        val.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(val);

        section.addView(header);

        // 分隔线
        View divider = new View(this);
        divider.setBackgroundColor(0xFF1F1F26);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)));
        ((LinearLayout.LayoutParams) divider.getLayoutParams()).topMargin = dpToPx(12);
        ((LinearLayout.LayoutParams) divider.getLayoutParams()).bottomMargin = dpToPx(12);
        section.addView(divider);

        // 代币容器（带 tag，方便后面动态填充）
        LinearLayout tokenContainer = new LinearLayout(this);
        tokenContainer.setTag("tokens");
        tokenContainer.setOrientation(LinearLayout.VERTICAL);
        tokenContainer.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        section.addView(tokenContainer);

        // 原生币
        if (wad.nativeBalance > 0) {
            tokenContainer.addView(createAssetRow(wad.nativeSymbol, wad.nativeName,
                ChainAPI.formatAmount(wad.nativeBalance),
                CurrencyManager.formatFiat(this, wad.nativeValue), "", true));
        }

        // 代币列表
        if (withTokens) {
            for (TokenAsset ta : wad.tokens) {
                tokenContainer.addView(createAssetRow(ta.symbol, ta.name,
                    ChainAPI.formatAmount(ta.balance),
                    CurrencyManager.formatFiat(this, ta.value), ta.contract, false));
            }
        }

        return section;
    }

    private LinearLayout createAssetRow(String symbol, String name, String amount, String value,
                                         String contract, boolean isNative) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dpToPx(10), 0, dpToPx(10));
        row.setClickable(true);
        row.setFocusable(true);
        // 使用系统属性 ?attr/selectableItemBackground，需先解析为真实资源 ID，
        // 直接用 android.R.attr.selectableItemBackground 调用 setBackgroundResource
        // 在部分系统上会出现 Resources$NotFoundException 导致闪退
        TypedValue typedValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true);
        if (typedValue.resourceId != 0) {
            row.setBackgroundResource(typedValue.resourceId);
        } else {
            row.setBackgroundColor(0x00000000);
        }

        // 图标占位
        TextView icon = new TextView(this);
        icon.setText(isNative ? "●" : symbol.substring(0, Math.min(2, symbol.length())));
        icon.setTextColor(0xFF667eea);
        icon.setTextSize(14);
        icon.setGravity(Gravity.CENTER);
        int iconBg = getResources().getIdentifier("circle_action_bg", "drawable", getPackageName());
        if (iconBg != 0) icon.setBackgroundResource(iconBg);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(36), dpToPx(36)));
        row.addView(icon);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dpToPx(10), 0, 0, 0);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView sym = new TextView(this);
        sym.setText(symbol);
        sym.setTextColor(0xFFFFFFFF);
        sym.setTextSize(14);
        sym.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(sym);

        TextView bal = new TextView(this);
        bal.setText(amount + " " + symbol);
        bal.setTextColor(0xFF9B9BA7);
        bal.setTextSize(12);
        bal.setPadding(0, dpToPx(2), 0, 0);
        info.addView(bal);

        row.addView(info);

        TextView val = new TextView(this);
        val.setText(value);
        val.setTextColor(0xFFFFFFFF);
        val.setTextSize(14);
        row.addView(val);

        // 点击跳转代币详情
        row.setOnClickListener(v -> {
            if (!isNative && !contract.isEmpty()) {
                Intent intent = new Intent(AssetOverviewActivity.this, TokenDetailActivity.class);
                intent.putExtra("symbol", symbol);
                intent.putExtra("name", name);
                intent.putExtra("balance", amount);
                intent.putExtra("value", value);
                intent.putExtra("contract", contract);
                startActivity(intent);
            }
        });

        return row;
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static class WalletAssetData {
        WalletManager.WalletInfo wallet;
        String chain;
        double nativeBalance;
        double nativeValue;
        String nativeSymbol;
        String nativeName;
        double walletTotal;
        java.util.List<TokenAsset> tokens = new java.util.ArrayList<>();
    }

    private static class TokenAsset {
        String symbol;
        String name;
        double balance;
        double value;
        String contract;
    }
}