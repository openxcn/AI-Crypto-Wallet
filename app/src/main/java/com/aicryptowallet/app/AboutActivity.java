/*
 * Copyright (C) 2026 红魔团队 (Red Devil Team)
 *
 * This software is proprietary and confidential.
 * Unauthorized copying, distribution, or modification is strictly prohibited.
 *
 * Licensed to: Authorized Users Only
 * Authorization required: Contact aibgsps@gmail.com
 */
package com.aicryptowallet.app;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

/**
 * 关于页面
 * 展示 App 自述声明、开发团队、免责声明、版权授权信息
 *
 * AI Crypto Wallet - 红魔团队专有软件
 * 未经授权禁止复制、修改或分发
 */
public class AboutActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        // 返回按钮
        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                getSupportActionBar().setTitle(getString(R.string.title_about_us));
            }
            toolbar.setNavigationOnClickListener(v -> finish());
        }

        // 版本号
        TextView tvVersion = findViewById(R.id.tvAboutVersion);
        if (tvVersion != null) {
            tvVersion.setText("v" + BuildConfig.VERSION_NAME);
        }

        // 自述声明内容
        TextView tvStatement = findViewById(R.id.tvAboutStatement);
        if (tvStatement != null) {
            tvStatement.setText(getStatement());
        }

        // 版权声明（动态生成）
        TextView tvCopyright = findViewById(R.id.tvCopyright);
        if (tvCopyright != null) {
            tvCopyright.setText(LicenseManager.getCopyrightNotice());
        }

        // 授权声明
        TextView tvLicense = findViewById(R.id.tvLicense);
        if (tvLicense != null) {
            tvLicense.setText(LicenseManager.getLicenseNoticeChinese());
        }

        // 联系方式
        TextView tvContact = findViewById(R.id.tvContact);
        if (tvContact != null) {
            tvContact.setText(LicenseManager.getContactInfo());
        }
    }

    /**
     * 自述声明
     */
    private String getStatement() {
        return getString(R.string.str_about_statement);
    }
}