package com.aicryptowallet.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 节点选择器 - 参考 TokenPocket 设计
 * 显示节点名称、延迟、区块高度，支持自定义节点
 */
public class NodeSelectorActivity extends BaseActivity {

    private String chain;
    private LinearLayout nodeListContainer;
    // 修复：CachedThreadPool 无上限，反复进出页面会累积线程；改用固定大小线程池
    private final ExecutorService executor = Executors.newFixedThreadPool(5);
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_node_selector);

        chain = getIntent().getStringExtra("chain");
        if (chain == null) chain = "ETH";

        // 设置标题为中文链名
        String chainChineseName = Logger.getChainChineseName(chain);
        ((TextView) findViewById(R.id.tvChainName)).setText(chainChineseName);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnMore).setOnClickListener(v -> showMoreOptions());
        findViewById(R.id.btnAddCustomNode).setOnClickListener(v -> showAddCustomDialog());

        nodeListContainer = findViewById(R.id.nodeListContainer);

        loadNodes();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 修复：之前无 onDestroy，Activity 销毁后 in-flight 请求返回仍会更新已销毁的视图
        // 且 CachedThreadPool 线程累积导致内存泄漏
        executor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
    }

    private void loadNodes() {
        nodeListContainer.removeAllViews();
        NodeManager.NodeEntry[] presets = NodeManager.getPresets(chain);
        String currentSelected = NodeManager.getSelectedNode(this, chain);
        String customNode = NodeManager.getCustomNode(this, chain);

        // Show custom node first if exists
        if (customNode != null && !customNode.isEmpty()) {
            addNodeItem("自定义节点", customNode, currentSelected, true);
        }

        // Show preset nodes
        for (NodeManager.NodeEntry entry : presets) {
            addNodeItem(entry.name, entry.url, currentSelected, false);
        }
    }

    private void addNodeItem(String name, String nodeUrl, String currentSelected, boolean isCustom) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(32, 24, 32, 24);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, 2);
        item.setLayoutParams(params);

        // White background
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(0);
        item.setBackground(bg);

        item.setClickable(true);

        // Top row: name + latency
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        // Node name
        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextColor(Color.parseColor("#333333"));
        tvName.setTextSize(15);

        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tvName.setLayoutParams(nameParams);

        // Latency text
        TextView tvLatency = new TextView(this);
        tvLatency.setText(getString(R.string.text_measuring_velocity));
        tvLatency.setTextColor(Color.parseColor("#999999"));
        tvLatency.setTextSize(13);
        tvLatency.setPadding(16, 0, 8, 0);

        // Speed dot
        TextView tvDot = new TextView(this);
        tvDot.setText("●");
        tvDot.setTextSize(10);
        tvDot.setTextColor(Color.parseColor("#cccccc"));
        tvDot.setPadding(0, 0, 8, 0);

        // Selected checkmark
        TextView tvSelected = new TextView(this);
        tvSelected.setText("✓");
        tvSelected.setTextColor(Color.parseColor("#2979ff"));
        tvSelected.setTextSize(16);
        tvSelected.setPadding(8, 0, 0, 0);
        tvSelected.setVisibility(nodeUrl.equals(currentSelected) ? TextView.VISIBLE : TextView.GONE);

        topRow.addView(tvName);
        topRow.addView(tvLatency);
        topRow.addView(tvDot);
        topRow.addView(tvSelected);

        // Bottom row: block height
        TextView tvBlockHeight = new TextView(this);
        tvBlockHeight.setText(getString(R.string.text_block_height));
        tvBlockHeight.setTextColor(Color.parseColor("#999999"));
        tvBlockHeight.setTextSize(12);
        tvBlockHeight.setPadding(0, 4, 0, 0);

        item.addView(topRow);
        item.addView(tvBlockHeight);

        // Click to select
        item.setOnClickListener(v -> {
            NodeManager.setSelectedNode(this, chain, nodeUrl);
            Toast.makeText(this, getString(R.string.toast_switched_to_2, name), Toast.LENGTH_SHORT).show();
            Logger.success(this, "节点切换", chain + " - " + name + " - " + nodeUrl);
            finish();
        });

        nodeListContainer.addView(item);

        // Ping + block height in background
        executor.execute(() -> {
            long latency = NodeManager.pingNodeSafe(chain, nodeUrl);
            long blockHeight = NodeManager.getBlockHeightSafe(chain, nodeUrl);

            handler.post(() -> {
                if (latency > 0) {
                    tvLatency.setText(latency + " ms");
                    if (latency < 300) {
                        tvLatency.setTextColor(Color.parseColor("#00d084"));
                        tvDot.setTextColor(Color.parseColor("#00d084"));
                    } else if (latency < 800) {
                        tvLatency.setTextColor(Color.parseColor("#ffa502"));
                        tvDot.setTextColor(Color.parseColor("#ffa502"));
                    } else {
                        tvLatency.setTextColor(Color.parseColor("#ff4757"));
                        tvDot.setTextColor(Color.parseColor("#ff4757"));
                    }
                } else {
                    tvLatency.setText(getString(R.string.text_timed_out));
                    tvLatency.setTextColor(Color.parseColor("#ff4757"));
                    tvDot.setTextColor(Color.parseColor("#ff4757"));
                }

                if (blockHeight > 0) {
                    tvBlockHeight.setText(getString(R.string.text_block_height, blockHeight));
                } else {
                    tvBlockHeight.setText(getString(R.string.text_block_height));
                }
            });
        });
    }

    private void showAddCustomDialog() {
        EditText etUrl = new EditText(this);
        etUrl.setHint(getString(R.string.hint_enter_the_rpc_url));
        etUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        etUrl.setPadding(32, 24, 32, 24);
        etUrl.setTextSize(14);

        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_adding_custom_token))
            .setView(etUrl)
            .setPositiveButton(getString(R.string.btn_saving), (dialog, which) -> {
                String url = etUrl.getText().toString().trim();
                if (!url.isEmpty()) {
                    NodeManager.setCustomNode(this, chain, url);
                    NodeManager.setSelectedNode(this, chain, url);
                    Toast.makeText(this, getString(R.string.toast_custom_node_saved), Toast.LENGTH_SHORT).show();
                    Logger.success(this, "节点设置", chain + " - 添加自定义节点：" + url);
                    loadNodes();
                }
            })
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    private void showMoreOptions() {
        String[] options = {"自动选择最快节点", "刷新测速", "查看日志"};
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_more_info))
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0:
                        autoSelectFastest();
                        break;
                    case 1:
                        loadNodes();
                        Toast.makeText(this, getString(R.string.toast_retesting_speed), Toast.LENGTH_SHORT).show();
                        break;
                    case 2:
                        startActivity(new android.content.Intent(this, LogViewerActivity.class));
                        break;
                }
            })
            .show();
    }

    private void autoSelectFastest() {
        Toast.makeText(this, getString(R.string.toast_testing_speed), Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            String fastest = NodeManager.findFastestNode(chain);
            handler.post(() -> {
                NodeManager.setSelectedNode(this, chain, fastest);
                Toast.makeText(this, getString(R.string.toast_fastest_node_selected), Toast.LENGTH_SHORT).show();
                Logger.success(this, "节点切换", chain + " - 自动选择最快节点：" + fastest);
                finish();
            });
        });
    }
}