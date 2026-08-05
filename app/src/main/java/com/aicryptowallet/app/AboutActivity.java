package com.aicryptowallet.app;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

/**
 * 关于页面
 * 展示 App 自述声明、开发团队、免责声明
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

        // 自述声明内容（润色版）
        TextView tvStatement = findViewById(R.id.tvAboutStatement);
        if (tvStatement != null) {
            tvStatement.setText(getStatement());
        }
    }

    /**
     * 自述声明（润色版）
     */
    private String getStatement() {
        return getString(R.string.str_about_statement);
    }
}