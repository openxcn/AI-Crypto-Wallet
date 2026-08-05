package com.aicryptowallet.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class WalletManagementActivity extends BaseActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wallet_management);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // 加载钱包信息
        String name = WalletManager.getWalletName(this);
        String address = WalletManager.getWalletAddress(this);
        TextView tvName = findViewById(R.id.tvWalletName);
        TextView tvAddr = findViewById(R.id.tvWalletAddress);
        if (tvName != null && name != null) tvName.setText(name);
        if (tvAddr != null && address != null && address.length() > 10) {
            tvAddr.setText(address.substring(0, 6) + "..." + address.substring(address.length() - 4));
        }

        // 导出助记词
        findViewById(R.id.btnExportMnemonic).setOnClickListener(v -> showMnemonicExportDialog());

        // 删除钱包
        findViewById(R.id.btnDeleteWallet).setOnClickListener(v -> {
            new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setTitle(getString(R.string.title_delete_wallet))
                .setMessage(getString(R.string.msg_are_you_sure_you_2))
                .setPositiveButton(getString(R.string.text_delete), (dialog, which) -> {
                    WalletManager.clearWallet(WalletManagementActivity.this);
                    Intent intent = new Intent(WalletManagementActivity.this, MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton(getString(R.string.btn_s_decline), null)
                .show();
        });
    }

    private void showMnemonicExportDialog() {
        final EditText etPassword = new EditText(this);
        etPassword.setHint(getString(R.string.hint_please_enter_your_wallet));
        etPassword.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_security_verification))
            .setMessage(getString(R.string.msg_password_verification_is_required))
            .setView(etPassword)
            .setPositiveButton(getString(R.string.btn_verify), (dialog, which) -> {
                String inputPwd = etPassword.getText().toString().trim();
                if (WalletManager.verifyPassword(this, inputPwd)) {
                    showMnemonicContent();
                } else {
                    Toast.makeText(this, getString(R.string.toast_wrong_password), Toast.LENGTH_SHORT).show();
                    Logger.warning(this, "钱包", "助记词导出密码验证失败");
                }
            })
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    private void showMnemonicContent() {
        String mnemonic = WalletManager.getMnemonic(this);
        if (mnemonic == null || mnemonic.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_failed_to_read_mnemonic), Toast.LENGTH_SHORT).show();
            return;
        }

        // 创建自定义布局：助记词文本可长按选中复制
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 16);

        android.widget.TextView tvMnemonic = new android.widget.TextView(this);
        tvMnemonic.setText(mnemonic);
        tvMnemonic.setTextSize(16);
        tvMnemonic.setTextColor(0xFFFFFFFF);
        tvMnemonic.setTextIsSelectable(true); // 允许长按选中复制
        tvMnemonic.setPadding(16, 16, 16, 16);
        tvMnemonic.setBackgroundColor(0xFF1E1E2E);
        layout.addView(tvMnemonic);

        android.widget.TextView tvTip = new android.widget.TextView(this);
        tvTip.setText(getString(R.string.text_don_reveal_it_you));
        tvTip.setTextSize(13);
        tvTip.setTextColor(0xFF9B9BA7);
        tvTip.setPadding(0, 16, 0, 0);
        layout.addView(tvTip);

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_mnemonic_please_keep_it))
            .setView(layout)
            .setPositiveButton(getString(R.string.btn_copy_and_close), (dialog, which) -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("mnemonic", mnemonic));
                    Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_LONG).show();
                    Logger.info(this, "钱包", "助记词已复制，将在 30 秒后清除剪贴板");
                    handler.postDelayed(() -> {
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
            })
            .setNegativeButton(getString(R.string.btn_view_only), null)
            .show();
    }
}