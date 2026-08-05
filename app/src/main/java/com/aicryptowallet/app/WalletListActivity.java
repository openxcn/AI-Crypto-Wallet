package com.aicryptowallet.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;

public class WalletListActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        setContentView(R.layout.activity_wallet_list);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        refreshWalletList();
    }

    private void refreshWalletList() {
        LinearLayout container = findViewById(R.id.walletListContainer);
        if (container == null) return;
        container.removeAllViews();

        List<WalletManager.WalletInfo> wallets = WalletManager.getAllWallets(this);
        String activeId = WalletManager.getActiveWalletId(this);

        if (wallets.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.text_no_nfts));
            empty.setTextColor(0xFF6E6E7A);
            empty.setTextSize(14);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dpToPx(40), 0, 0);
            container.addView(empty);
            return;
        }

        for (WalletManager.WalletInfo wallet : wallets) {
            boolean isActive = wallet.id != null && wallet.id.equals(activeId);
            container.addView(createWalletItem(wallet, isActive));
        }
    }

    private LinearLayout createWalletItem(WalletManager.WalletInfo wallet, boolean isActive) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        item.setBackgroundResource(R.drawable.card_background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dpToPx(12);
        item.setLayoutParams(params);
        item.setClickable(true);
        item.setFocusable(true);
        item.setForeground(getResources().getDrawable(android.R.attr.selectableItemBackground, null));

        // 左侧图标
        TextView icon = new TextView(this);
        icon.setText("💼");
        icon.setTextSize(22);
        icon.setGravity(Gravity.CENTER);
        icon.setTextColor(0xFFFFFFFF);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(44), dpToPx(44)));
        item.addView(icon);

        // 中间信息
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        info.setPadding(dpToPx(12), 0, dpToPx(12), 0);

        TextView name = new TextView(this);
        name.setText(wallet.name + (isActive ? getString(R.string.label_current) : ""));
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(15);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        info.addView(name);

        TextView address = new TextView(this);
        address.setText(wallet.getShortAddress());
        address.setTextColor(0xFF9B9BA7);
        address.setTextSize(12);
        address.setFontFeatureSettings("tnum");
        address.setPadding(0, dpToPx(4), 0, 0);
        info.addView(address);

        TextView type = new TextView(this);
        type.setText(wallet.getTypeLabel());
        type.setTextColor(0xFF667eea);
        type.setTextSize(11);
        type.setPadding(0, dpToPx(4), 0, 0);
        info.addView(type);

        item.addView(info);

        // 右侧箭头/选中标记
        TextView mark = new TextView(this);
        mark.setText(isActive ? "✓" : "›");
        mark.setTextColor(isActive ? 0xFF4ADE80 : 0xFF6E6E7A);
        mark.setTextSize(18);
        mark.setGravity(Gravity.CENTER);
        item.addView(mark);

        item.setOnClickListener(v -> {
            if (isActive) {
                finish();
                return;
            }
            WalletManager.setActiveWalletId(this, wallet.id);
            Logger.action(this, "钱包切换", "切换到钱包: " + wallet.name, wallet.address);
            Toast.makeText(this, getString(R.string.toast_switched_to_3, wallet.name), Toast.LENGTH_SHORT).show();

            // 切换钱包后刷新 DApp 浏览器等页面
            Intent result = new Intent();
            result.putExtra("wallet_switched", true);
            setResult(RESULT_OK, result);
            finish();
        });

        return item;
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}