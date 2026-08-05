package com.aicryptowallet.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.gridlayout.widget.GridLayout;

public class MainActivity extends BaseActivity {

    private String selectedChain = "ETH";
    private TextView selectedView = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeManager.applyTheme(this);
        setContentView(R.layout.activity_main);

        showSafetyWarning();
    }

    private void showSafetyWarning() {
        SharedPreferences prefs = getSharedPreferences("app_config", MODE_PRIVATE);
        boolean firstLaunch = prefs.getBoolean("first_launch", true);
        if (!firstLaunch) {
            initMainScreen();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_safety_reminder))
            .setMessage(getString(R.string.msg_aicw_wallet_runs_purely))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.label_ok), (dialog, which) -> {
                dialog.dismiss();
                prefs.edit().putBoolean("first_launch", false).apply();
                initMainScreen();
            })
            .show();
    }

    private void initMainScreen() {
        boolean forceCreate = getIntent().getBooleanExtra("force_create", false);
        if (!forceCreate && WalletManager.hasWallet(this)) {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
            return;
        }

        // 若从钱包列表的「+」进入，直接带上目标链，无需用户再选一次
        String targetChain = getIntent().getStringExtra("target_chain");
        if (targetChain != null && !targetChain.isEmpty()) {
            selectedChain = targetChain;
        }

        GridLayout grid = findViewById(R.id.chainGrid);
        grid.removeAllViews();

        for (String[] chainInfo : ChainAPI.CHAIN_CONFIG) {
            String code = chainInfo[0];
            String name = chainInfo[1];

            TextView tv = new TextView(this);
            tv.setText(code);
            tv.setTextColor(getResources().getColor(R.color.text_secondary));
            tv.setTextSize(13);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(8, 16, 8, 16);

            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
            params.setMargins(4, 4, 4, 4);
            tv.setLayoutParams(params);

            if (code.equals(selectedChain)) {
                tv.setBackground(getResources().getDrawable(R.drawable.chain_selected_background));
                tv.setTextColor(getResources().getColor(R.color.text_primary));
                selectedView = tv;
            } else {
                tv.setBackground(getResources().getDrawable(R.drawable.chain_unselected_background));
            }

            tv.setOnClickListener(v -> {
                if (selectedView != null) {
                    selectedView.setBackground(getResources().getDrawable(R.drawable.chain_unselected_background));
                    selectedView.setTextColor(getResources().getColor(R.color.text_secondary));
                }
                tv.setBackground(getResources().getDrawable(R.drawable.chain_selected_background));
                tv.setTextColor(getResources().getColor(R.color.text_primary));
                selectedChain = code;
                selectedView = tv;
            });

            grid.addView(tv);
        }

        findViewById(R.id.btnCreate).setOnClickListener(v -> {
            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra("mode", "create");
            intent.putExtra("chain", selectedChain);
            startActivity(intent);
        });

        findViewById(R.id.btnImport).setOnClickListener(v -> {
            Intent intent = new Intent(this, SetupActivity.class);
            intent.putExtra("mode", "import");
            intent.putExtra("chain", selectedChain);
            startActivity(intent);
        });
    }
}