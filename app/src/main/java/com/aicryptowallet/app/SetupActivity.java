package com.aicryptowallet.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class SetupActivity extends BaseActivity {

    private String mode = "create";
    private String chain = "ETH";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        // 允许截图和复制助记词（用户自行负责安全）
        // 不再设置 FLAG_SECURE

        mode = getIntent().getStringExtra("mode");
        chain = getIntent().getStringExtra("chain");
        if (mode == null) mode = "create";
        if (chain == null) chain = "ETH";

        TextView tvTitle = findViewById(R.id.tvTitle);
        TextView btnBack = findViewById(R.id.btnBack);
        Button btnConfirm = findViewById(R.id.btnConfirm);
        EditText etMnemonic = findViewById(R.id.etMnemonic);
        TextView labelMnemonic = findViewById(R.id.labelMnemonic);
        EditText etConfirmPassword = findViewById(R.id.etConfirmPassword);
        TextView labelConfirm = findViewById(R.id.labelConfirm);

        if ("import".equals(mode)) {
            tvTitle.setText(getString(R.string.text_import_wallet));
            btnConfirm.setText(getString(R.string.text_import_wallet));
            etMnemonic.setVisibility(View.VISIBLE);
            labelMnemonic.setVisibility(View.VISIBLE);
            etConfirmPassword.setVisibility(View.GONE);
            labelConfirm.setVisibility(View.GONE);
        } else {
            tvTitle.setText(getString(R.string.text_create_wallet_2));
            btnConfirm.setText(getString(R.string.text_create_wallet_2));
        }

        btnBack.setOnClickListener(v -> finish());

        btnConfirm.setOnClickListener(v -> {
            String walletName = ((EditText) findViewById(R.id.etWalletName)).getText().toString().trim();
            String password = ((EditText) findViewById(R.id.etPassword)).getText().toString().trim();

            if (walletName.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_please_enter_wallet_name), Toast.LENGTH_SHORT).show();
                return;
            }
            if (password.isEmpty() || password.length() < 6) {
                Toast.makeText(this, getString(R.string.toast_the_password_is_at), Toast.LENGTH_SHORT).show();
                return;
            }

            String mnemonic;
            String address;

            boolean isPrivateKeyImport = false;
            if ("import".equals(mode)) {
                mnemonic = etMnemonic.getText().toString().trim();
                if (mnemonic.isEmpty()) {
                    Toast.makeText(this, getString(R.string.toast_please_enter_mnemonic_or), Toast.LENGTH_SHORT).show();
                    return;
                }
                // 检测是私钥还是助记词
                isPrivateKeyImport = WalletManager.isPrivateKey(mnemonic);
            } else {
                String confirmPassword = etConfirmPassword.getText().toString().trim();
                if (!password.equals(confirmPassword)) {
                    Toast.makeText(this, getString(R.string.toast_two_passwords_are_inconsistent), Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    mnemonic = WalletManager.generateMnemonic();
                } catch (RuntimeException e) {
                    Toast.makeText(this, getString(R.string.toast_mnemonic_word_generation_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                    return;
                }
            }

            if (isPrivateKeyImport) {
                // 私钥导入：从私钥直接推导地址
                address = WalletManager.deriveAddressFromPrivateKey(mnemonic, chain);
                if (address == null || address.isEmpty()) {
                    Toast.makeText(this, getString(R.string.toast_invalid_private_key_address), Toast.LENGTH_LONG).show();
                    return;
                }
                WalletManager.saveImportedWallet(this, walletName, password, mnemonic, address, chain);
            } else {
                // 助记词导入/创建：从助记词推导地址
                address = WalletManager.deriveAddress(mnemonic, chain);
                if (address == null || address.isEmpty()) {
                    Toast.makeText(this, getString(R.string.toast_address_deduction_failed_please), Toast.LENGTH_LONG).show();
                    return;
                }
                WalletManager.saveWallet(this, walletName, password, mnemonic, address, chain);
            }

            if ("create".equals(mode)) {
                // 跳转到助记词备份页面
                Intent intent = new Intent(this, BackupMnemonicActivity.class);
                intent.putExtra("mnemonic", mnemonic);
                intent.putExtra("walletName", walletName);
                intent.putExtra("password", password);
                intent.putExtra("address", address);
                intent.putExtra("chain", chain);
                startActivity(intent);
                finish();
            } else if (isPrivateKeyImport) {
                Toast.makeText(this, getString(R.string.toast_private_key_wallet_imported), Toast.LENGTH_SHORT).show();
                goToHome();
            } else {
                Toast.makeText(this, getString(R.string.toast_wallet_imported_successfully), Toast.LENGTH_SHORT).show();
                goToHome();
            }
        });
    }

    private void goToHome() {
        Intent intent = new Intent(this, HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}