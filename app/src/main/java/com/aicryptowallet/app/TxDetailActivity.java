package com.aicryptowallet.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 单笔交易详情页
 * 展示交易状态、金额、发送方/接收方、交易哈希、时间、区块号、Gas费等
 * 参考TokenPocket/MetaMask的交易详情页设计
 */
public class TxDetailActivity extends BaseActivity {

    public static final String EXTRA_TX_HASH = "tx_hash";
    public static final String EXTRA_CHAIN = "chain";
    public static final String EXTRA_TX_DATA = "tx_data"; // 可选：从列表传过来的已有数据

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String txHash;
    private String chain;

    // UI元素
    private TextView tvStatusIcon, tvStatus, tvAmount, tvAmountUsd;
    private TextView tvType, tvFrom, tvTo, tvHash, tvTime;
    private TextView tvBlock, tvGas;
    private View rowBlock, dividerBlock, rowGas;
    private ProgressBar progressLoading;
    private View headerCard;
    private TextView btnViewInExplorer;
    private SwipeRefreshLayout swipeRefresh;

    // 列表传过来的已有数据（可能不完整，需RPC补充）
    private String[] existingData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_tx_detail);

            txHash = getIntent().getStringExtra(EXTRA_TX_HASH);
            chain = getIntent().getStringExtra(EXTRA_CHAIN);
            existingData = getIntent().getStringArrayExtra(EXTRA_TX_DATA);

            if (txHash == null || txHash.isEmpty()) {
                finish();
                return;
            }
            if (chain == null || chain.isEmpty()) {
                chain = WalletManager.getChain(this);
            }

            initViews();
            loadTxDetail();
        } catch (Exception e) {
            Logger.error(this, "TxDetail", "onCreate异常: " + e.getMessage());
            finish();
        }
    }

    private void initViews() {
        tvStatusIcon = findViewById(R.id.tvStatusIcon);
        tvStatus = findViewById(R.id.tvStatus);
        tvAmount = findViewById(R.id.tvAmount);
        tvAmountUsd = findViewById(R.id.tvAmountUsd);
        tvType = findViewById(R.id.tvType);
        tvFrom = findViewById(R.id.tvFrom);
        tvTo = findViewById(R.id.tvTo);
        tvHash = findViewById(R.id.tvHash);
        tvTime = findViewById(R.id.tvTime);
        tvBlock = findViewById(R.id.tvBlock);
        tvGas = findViewById(R.id.tvGas);
        rowBlock = findViewById(R.id.rowBlock);
        dividerBlock = findViewById(R.id.dividerBlock);
        rowGas = findViewById(R.id.rowGas);
        headerCard = findViewById(R.id.headerCard);
        btnViewInExplorer = findViewById(R.id.btnViewInExplorer);
        progressLoading = new ProgressBar(this);
        progressLoading.setVisibility(View.GONE);

        // 返回按钮
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // 分享按钮
        findViewById(R.id.btnShare).setOnClickListener(v -> {
            String explorerUrl = ChainAPI.getExplorerTxUrl(chain, txHash);
            if (explorerUrl != null) {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TEXT, explorerUrl);
                startActivity(Intent.createChooser(shareIntent, getString(R.string.str_share)));
            }
        });

        // 复制按钮
        findViewById(R.id.btnCopyFrom).setOnClickListener(v -> copyToClipboard(tvFrom.getText().toString(), "发送方地址"));
        findViewById(R.id.btnCopyTo).setOnClickListener(v -> copyToClipboard(tvTo.getText().toString(), "接收方地址"));
        findViewById(R.id.btnCopyHash).setOnClickListener(v -> copyToClipboard(txHash, "交易哈希"));

        // 在浏览器查看
        btnViewInExplorer.setOnClickListener(v -> {
            String url = ChainAPI.getExplorerTxUrl(chain, txHash);
            if (url != null) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            }
        });

        // 更新浏览器按钮文案
        String explorerName = getExplorerName(chain);
        btnViewInExplorer.setText(getString(R.string.text_view_in, explorerName));

        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setColorSchemeResources(R.color.text_blue);
        swipeRefresh.setOnRefreshListener(this::loadTxDetail);
    }

    /**
     * 加载交易详情
     * 先用列表传过来的数据快速渲染，后台再RPC查询完整信息
     */
    private void loadTxDetail() {
        // 1. 先用已有数据快速渲染
        if (existingData != null && existingData.length >= 7) {
            renderTxDetail(existingData, true);
        } else {
            // 显示加载中
            tvStatus.setText(getString(R.string.text_memuat));
            tvAmount.setText("");
            tvAmountUsd.setVisibility(View.GONE);
        }

        // 2. 后台RPC查询完整详情
        executor.execute(() -> {
            String[] detail = ChainAPI.getTransactionDetail(this, chain, txHash);
            handler.post(() -> {
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                if (detail != null) {
                    renderTxDetail(detail, false);
                } else if (existingData == null) {
                    // 查询失败且没有缓存数据
                    tvStatus.setText(getString(R.string.text_query_failed));
                    tvStatusIcon.setText("!");
                    tvStatusIcon.setTextColor(0xFFFF3B30);
                }
            });
        });
    }

    /**
     * 渲染交易详情
     * @param data [hash, from, to, value, time, status, type, symbol, blockNumber, gas, ...]
     * @param isPartial 是否为不完整数据（来自列表，可能缺少blockNumber和gas）
     */
    private void renderTxDetail(String[] data, boolean isPartial) {
        String hash = data.length > 0 ? data[0] : txHash;
        String from = data.length > 1 ? data[1] : "";
        String to = data.length > 2 ? data[2] : "";
        String value = data.length > 3 ? data[3] : "0";
        String time = data.length > 4 ? data[4] : "";
        String status = data.length > 5 ? data[5] : "unknown";
        String type = data.length > 6 ? data[6] : "transfer";
        String symbol = data.length > 7 ? data[7] : "";
        String blockNumber = data.length > 8 ? data[8] : "";
        String gas = data.length > 9 ? data[9] : "";

        // 我的钱包地址
        String myAddress = WalletManager.getWalletAddress(this);
        if (myAddress == null) myAddress = "";
        boolean isSend = from.equalsIgnoreCase(myAddress);

        // 状态图标和文字
        String statusIcon, statusText;
        int statusColor;
        if ("success".equalsIgnoreCase(status)) {
            statusIcon = "✓";
            statusText = "交易成功";
            statusColor = 0xFF34C759;
        } else if ("failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status)) {
            statusIcon = "✕";
            statusText = "交易失败";
            statusColor = 0xFFFF3B30;
        } else {
            statusIcon = "⏳";
            statusText = "确认中";
            statusColor = 0xFFFF9500;
        }
        tvStatusIcon.setText(statusIcon);
        tvStatusIcon.setTextColor(statusColor);
        tvStatus.setText(statusText);

        // 交易类型
        String typeLabel;
        if ("contract_call".equals(type)) {
            typeLabel = "合约调用";
        } else if ("approval".equals(type)) {
            typeLabel = "授权";
        } else {
            typeLabel = "转账";
        }
        tvType.setText(typeLabel);

        // 金额（授权不显示金额，合约调用有symbol时显示）
        if ("approval".equals(type)) {
            tvAmount.setText("--");
            tvAmount.setTextColor(0xFF9B9BA7);
            tvAmountUsd.setVisibility(View.GONE);
        } else if ("contract_call".equals(type)) {
            if (symbol.isEmpty() || value.isEmpty() || "0".equals(value)) {
                tvAmount.setText("--");
                tvAmount.setTextColor(0xFF9B9BA7);
                tvAmountUsd.setVisibility(View.GONE);
            } else {
                String amountText = (isSend ? "-" : "+") + value + " " + symbol;
                tvAmount.setText(amountText);
                tvAmount.setTextColor(isSend ? 0xFFFF453A : 0xFF34C759);
                tvAmountUsd.setVisibility(View.GONE);
            }
        } else {
            String amountText = (isSend ? "-" : "+") + value + (symbol.isEmpty() ? "" : " " + symbol);
            tvAmount.setText(amountText);
            tvAmount.setTextColor(isSend ? 0xFFFF453A : 0xFF34C759);
            // 法币估值（简单估算，暂不显示）
            tvAmountUsd.setVisibility(View.GONE);
        }

        // 地址
        tvFrom.setText(from.isEmpty() ? "-" : from);
        tvTo.setText(to.isEmpty() ? "-" : to);
        tvHash.setText(hash.isEmpty() ? "-" : hash);

        // 时间
        tvTime.setText(time.isEmpty() ? "-" : time);

        // 区块号和Gas费（仅在RPC查询到完整数据时显示）
        if (!isPartial) {
            if (!blockNumber.isEmpty()) {
                tvBlock.setText("#" + blockNumber);
                rowBlock.setVisibility(View.VISIBLE);
                dividerBlock.setVisibility(View.VISIBLE);
            }
            if (!gas.isEmpty()) {
                tvGas.setText(gas + " " + (symbol.isEmpty() ? "" : symbol));
                rowGas.setVisibility(View.VISIBLE);
            }
        }
    }

    private void copyToClipboard(String text, String label) {
        if (text == null || text.isEmpty() || "-".equals(text)) return;
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(this, getString(R.string.toast_copied, label), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {}
    }

    private String getExplorerName(String chain) {
        switch (chain) {
            case "BNB":   return "BscScan";
            case "ETH":   return "Etherscan";
            case "MATIC": return "PolygonScan";
            case "ARB":   return "Arbiscan";
            case "AVAX":  return "Snowtrace";
            case "FTM":   return "FtmScan";
            default:      return "区块浏览器";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}