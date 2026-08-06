package com.aicryptowallet.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class BackupMnemonicActivity extends BaseActivity {

    private String mnemonic;
    private String walletName;
    private String password;
    private String address;
    private String chain;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_backup_mnemonic);

        mnemonic = getIntent().getStringExtra("mnemonic");
        walletName = getIntent().getStringExtra("walletName");
        password = getIntent().getStringExtra("password");
        address = getIntent().getStringExtra("address");
        chain = getIntent().getStringExtra("chain");

        if (mnemonic == null) {
            Toast.makeText(this, getString(R.string.toast_mnemonic_data_missing), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> {
            WalletManager.removeWalletByAddress(this, address);
            finish();
        });

        findViewById(R.id.btnCopyMnemonic).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("mnemonic", mnemonic));
                Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    ClipboardManager cm2 = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm2 != null) {
                        ClipData current = cm2.getPrimaryClip();
                        if (current != null && current.getItemCount() > 0) {
                            CharSequence text = current.getItemAt(0).getText();
                            if (text != null && text.toString().equals(mnemonic)) {
                                cm2.setPrimaryClip(ClipData.newPlainText("", ""));
                                Toast.makeText(this, getString(R.string.toast_clipboard_cleared_automatically), Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                }, 30_000);
            }
        });

        renderMnemonicGrid();

        findViewById(R.id.btnBackupDone).setOnClickListener(v -> {
            Intent intent = new Intent(this, VerifyMnemonicActivity.class);
            intent.putExtra("mnemonic", mnemonic);
            intent.putExtra("walletName", walletName);
            intent.putExtra("password", password);
            intent.putExtra("address", address);
            intent.putExtra("chain", chain);
            startActivity(intent);
            finish();
        });
    }

    private void renderMnemonicGrid() {
        LinearLayout grid = findViewById(R.id.mnemonicGrid);
        String[] words = mnemonic.trim().split("\\s+");
        int cols = 3;
        int count = words.length;

        for (int r = 0; r < count; r += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(8);
            row.setLayoutParams(rowParams);

            for (int c = 0; c < cols; c++) {
                int idx = r + c;
                if (idx >= count) break;

                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.HORIZONTAL);
                cell.setGravity(Gravity.CENTER_VERTICAL);
                cell.setBackground(getDrawable(R.drawable.mnemonic_word_bg));
                cell.setPadding(dp(12), dp(10), dp(12), dp(10));
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, dp(44), 1);
                if (c < cols - 1 && idx + 1 < count) cellParams.rightMargin = dp(8);
                cell.setLayoutParams(cellParams);

                TextView tvNum = new TextView(this);
                tvNum.setText((idx + 1) + "");
                tvNum.setTextColor(Color.parseColor("#6b7280"));
                tvNum.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                LinearLayout.LayoutParams numParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                numParams.rightMargin = dp(8);
                tvNum.setLayoutParams(numParams);

                TextView tvWord = new TextView(this);
                tvWord.setText(words[idx]);
                tvWord.setTextColor(Color.parseColor("#ffffff"));
                tvWord.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                tvWord.setSingleLine(true);
                LinearLayout.LayoutParams wordParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                tvWord.setLayoutParams(wordParams);

                cell.addView(tvNum);
                cell.addView(tvWord);
                row.addView(cell);
            }
            grid.addView(row);
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
            getResources().getDisplayMetrics());
    }
}
