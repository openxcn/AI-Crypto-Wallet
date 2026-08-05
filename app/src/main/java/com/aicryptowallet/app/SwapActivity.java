package com.aicryptowallet.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SwapActivity extends BaseActivity {

    private String fromToken, toToken;
    private String fromTokenAddress, toTokenAddress;
    private double fromBalance = 0, toBalance = 0;
    private double fromPrice = 0, toPrice = 0;
    private double slippage = 0.5;

    private EditText etFromAmount, etToAmount;
    private TextView tvFromBalance, tvToBalance, tvFromUsd, tvToUsd;
    private TextView tvRate, tvSlippage, tvMinReceived;
    private TextView btnSwapConfirm, btnSwitchTokens;
    private SwipeRefreshLayout swipeRefresh;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DexTrader dexTrader = new DexTrader();

    // 复用静态 OkHttpClient，避免每次查询创建新实例导致内存泄漏
    private static final okhttp3.OkHttpClient SWAP_CLIENT = new okhttp3.OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build();

    // 防抖：避免每次按键都触发链上查询
    private Runnable pendingQueryRunnable;
    private static final int QUERY_DEBOUNCE_MS = 600;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_swap);

        fromToken = getIntent().getStringExtra("fromToken");
        toToken = getIntent().getStringExtra("toToken");
        fromTokenAddress = getIntent().getStringExtra("fromTokenAddress");
        toTokenAddress = getIntent().getStringExtra("toTokenAddress");

        if (fromToken == null) fromToken = WalletManager.getChain(this);
        if (toToken == null) toToken = "USDT";

        initViews();
        loadBalances();
        setupListeners();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 修复：关闭 executor、移除防抖回调、清空 handler，避免内存泄漏
        if (pendingQueryRunnable != null) {
            handler.removeCallbacks(pendingQueryRunnable);
            pendingQueryRunnable = null;
        }
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }

    private void initViews() {
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        etFromAmount = findViewById(R.id.etFromAmount);
        etToAmount = findViewById(R.id.etToAmount);
        tvFromBalance = findViewById(R.id.tvFromBalance);
        tvToBalance = findViewById(R.id.tvToBalance);
        tvFromUsd = findViewById(R.id.tvFromUsd);
        tvToUsd = findViewById(R.id.tvToUsd);
        tvRate = findViewById(R.id.tvRate);
        tvSlippage = findViewById(R.id.tvSlippage);
        tvMinReceived = findViewById(R.id.tvMinReceived);
        btnSwapConfirm = findViewById(R.id.btnSwapConfirm);
        btnSwitchTokens = findViewById(R.id.btnSwitchTokens);

        ((TextView) findViewById(R.id.tvFromToken)).setText(fromToken);
        ((TextView) findViewById(R.id.tvToToken)).setText(toToken);

        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setColorSchemeResources(R.color.text_blue);
        swipeRefresh.setOnRefreshListener(() -> {
            loadBalances();
            etFromAmount.setText("");
            etToAmount.setText("");
        });
    }

    private void loadBalances() {
        executor.execute(() -> {
            try {
                String chain = WalletManager.getChain(this);
                String address = WalletManager.getWalletAddress(this);
                String rpcUrl = WalletManager.getRpcUrl(this, chain);

                // 修复：之前 fromBalance 永远查原生币，且 else-if 分支把 fromTokenAddress 的余额赋给了 toBalance
                // 现在分别根据是否有合约地址决定查代币还是原生币
                fromBalance = (fromTokenAddress != null && !fromTokenAddress.isEmpty())
                    ? getERC20Balance(rpcUrl, address, fromTokenAddress, getTokenDecimals(rpcUrl, fromTokenAddress))
                    : getNativeBalance(rpcUrl, address);
                toBalance = (toTokenAddress != null && !toTokenAddress.isEmpty())
                    ? getERC20Balance(rpcUrl, address, toTokenAddress, getTokenDecimals(rpcUrl, toTokenAddress))
                    : getNativeBalance(rpcUrl, address);

                // 拉取价格
                java.util.Map<String, Double> prices = ChainAPI.getPrices(this);
                fromPrice = prices.getOrDefault(fromToken, 0.0);
                toPrice = prices.getOrDefault(toToken, 0.0);

                handler.post(() -> {
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    tvFromBalance.setText(getString(R.string.str_balance_1, String.format("%.6f", fromBalance)));
                    tvToBalance.setText(getString(R.string.str_balance_1, String.format("%.6f", toBalance)));
                    updateUsdValues();
                    updateSwapButton();
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    tvFromBalance.setText(getString(R.string.text_nbalance));
                    tvToBalance.setText(getString(R.string.text_nbalance));
                });
            }
        });
    }

    /**
     * 查询代币的 decimals（精度）
     * 修复：之前 getERC20Balance 硬编码 18 位精度，导致 USDT(6)/USDC(6)/WBTC(8) 等代币余额显示放大 10^12 倍
     */
    private int getTokenDecimals(String rpcUrl, String contract) throws Exception {
        // decimals() = 0x313ce567
        org.json.JSONObject body = new org.json.JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "eth_call");
        org.json.JSONArray params = new org.json.JSONArray();
        org.json.JSONObject callObj = new org.json.JSONObject();
        callObj.put("to", contract);
        callObj.put("data", "0x313ce567");
        params.put(callObj);
        params.put("latest");
        body.put("params", params);

        okhttp3.Request request = new okhttp3.Request.Builder()
            .url(rpcUrl)
            .post(okhttp3.RequestBody.create(body.toString(), okhttp3.MediaType.parse("application/json")))
            .build();

        try (okhttp3.Response response = SWAP_CLIENT.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            org.json.JSONObject json = new org.json.JSONObject(resp);
            String result = json.optString("result", "0x12");
            if (result.length() < 3) return 18;
            try {
                return new java.math.BigInteger(result.substring(2), 16).intValue();
            } catch (Exception e) {
                return 18;
            }
        }
    }

    private double getNativeBalance(String rpcUrl, String address) throws Exception {
        org.json.JSONObject body = new org.json.JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "eth_getBalance");
        org.json.JSONArray params = new org.json.JSONArray();
        params.put(address);
        params.put("latest");
        body.put("params", params);

        okhttp3.Request request = new okhttp3.Request.Builder()
            .url(rpcUrl)
            .post(okhttp3.RequestBody.create(body.toString(), okhttp3.MediaType.parse("application/json")))
            .build();

        try (okhttp3.Response response = SWAP_CLIENT.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            org.json.JSONObject json = new org.json.JSONObject(resp);
            String balHex = json.optString("result", "0x0");
            return new java.math.BigInteger(balHex.substring(2), 16).doubleValue() / 1e18;
        }
    }

    private double getERC20Balance(String rpcUrl, String address, String contract, int decimals) throws Exception {
        // balanceOf(address) = 0x70a08231 + pad(address)
        String data = "0x70a08231" + String.format("%64s", address.substring(2)).replace(' ', '0');
        org.json.JSONObject body = new org.json.JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "eth_call");
        org.json.JSONArray params = new org.json.JSONArray();
        org.json.JSONObject callObj = new org.json.JSONObject();
        callObj.put("to", contract);
        callObj.put("data", data);
        params.put(callObj);
        params.put("latest");
        body.put("params", params);

        okhttp3.Request request = new okhttp3.Request.Builder()
            .url(rpcUrl)
            .post(okhttp3.RequestBody.create(body.toString(), okhttp3.MediaType.parse("application/json")))
            .build();

        try (okhttp3.Response response = SWAP_CLIENT.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            org.json.JSONObject json = new org.json.JSONObject(resp);
            String result = json.optString("result", "0x0");
            if (result.length() < 3) return 0;
            // 修复：用传入的 decimals 而非硬编码 18，避免 USDT(6)/USDC(6)/WBTC(8) 余额显示错误
            return new java.math.BigDecimal(new java.math.BigInteger(result.substring(2), 16))
                .divide(java.math.BigDecimal.TEN.pow(decimals)).doubleValue();
        }
    }

    private void setupListeners() {
        etFromAmount.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                calculateOutput();
                updateUsdValues();
                updateSwapButton();
            }
        });

        btnSwitchTokens.setOnClickListener(v -> {
            String tempToken = fromToken;
            fromToken = toToken;
            toToken = tempToken;

            String tempAddress = fromTokenAddress;
            fromTokenAddress = toTokenAddress;
            toTokenAddress = tempAddress;

            double tempPrice = fromPrice;
            fromPrice = toPrice;
            toPrice = tempPrice;

            ((TextView) findViewById(R.id.tvFromToken)).setText(fromToken);
            ((TextView) findViewById(R.id.tvToToken)).setText(toToken);

            etFromAmount.setText("");
            etToAmount.setText("");
            updateUsdValues();
        });

        btnSwapConfirm.setOnClickListener(v -> confirmSwap());
    }

    /**
     * 计算兑换输出
     * 优化：先用价格比给出即时估算，再用防抖异步查询链上真实 getAmountsOut 更新
     * 修复：之前只用价格比估算，与 DEX 实际输出差异大（无滑点曲线）
     */
    private void calculateOutput() {
        try {
            String amountStr = etFromAmount.getText().toString().trim();
            if (amountStr.isEmpty()) {
                etToAmount.setText("");
                tvRate.setText("1 " + fromToken + " = 0 " + toToken);
                tvMinReceived.setText("0 " + toToken);
                // 取消待执行的链上查询
                if (pendingQueryRunnable != null) {
                    handler.removeCallbacks(pendingQueryRunnable);
                }
                return;
            }

            double amount = Double.parseDouble(amountStr);

            // 先用价格比给出即时估算（用户立刻看到反馈）
            if (fromPrice > 0 && toPrice > 0) {
                double outputAmount = amount * fromPrice / toPrice;
                etToAmount.setText(String.format("%.6f", outputAmount));
                tvRate.setText("1 " + fromToken + " = " + String.format("%.4f", fromPrice / toPrice) + " " + toToken);
                double minReceived = outputAmount * (100 - slippage) / 100;
                tvMinReceived.setText(getString(R.string.str_text_73e63f, String.format("%.6f", minReceived), toToken, ""));
            } else {
                tvMinReceived.setText(getString(R.string.text_fetching_quote));
            }

            // 防抖异步查询链上真实输出（避免每次按键都发请求）
            if (pendingQueryRunnable != null) {
                handler.removeCallbacks(pendingQueryRunnable);
            }
            final double finalAmount = amount;
            pendingQueryRunnable = () -> queryChainAmountOut(finalAmount);
            handler.postDelayed(pendingQueryRunnable, QUERY_DEBOUNCE_MS);
        } catch (Exception e) {
            // Parse failed
        }
    }

    /**
     * 查询链上真实兑换输出并更新 UI
     */
    private void queryChainAmountOut(double amount) {
        executor.execute(() -> {
            try {
                String chain = WalletManager.getChain(this);
                // 原生币的合约地址传 null
                String tokenIn = fromTokenAddress != null && !fromTokenAddress.isEmpty() ? fromTokenAddress : null;
                String tokenOut = toTokenAddress != null && !toTokenAddress.isEmpty() ? toTokenAddress : null;

                double realOutput = dexTrader.getAmountOutPublic(this, chain, tokenIn, tokenOut, amount);
                handler.post(() -> {
                    try {
                        etToAmount.setText(String.format("%.6f", realOutput));
                        if (fromPrice > 0 && toPrice > 0) {
                            tvRate.setText("1 " + fromToken + " = " +
                                String.format("%.6f", realOutput / amount) + " " + toToken + " (链上)");
                        }
                        double minReceived = realOutput * (100 - slippage) / 100;
                        tvMinReceived.setText(String.format("%.6f", minReceived) + " " + toToken);
                    } catch (Exception e) {
                        // UI 更新失败
                    }
                });
            } catch (Exception e) {
                handler.post(() -> {
                    // 链上查询失败时保留价格估算，但提示用户
                    if (tvMinReceived.getText().toString().contains("估算") ||
                        tvMinReceived.getText().toString().contains("获取报价")) {
                        tvMinReceived.setText(getString(R.string.text_failed_to_get_on));
                    }
                });
            }
        });
    }

    private void updateUsdValues() {
        try {
            String fromAmountStr = etFromAmount.getText().toString().trim();
            double fromAmount = fromAmountStr.isEmpty() ? 0 : Double.parseDouble(fromAmountStr);
            tvFromUsd.setText("≈ " + CurrencyManager.formatFiat(this, fromAmount * fromPrice));

            String toAmountStr = etToAmount.getText().toString().trim();
            double toAmount = toAmountStr.isEmpty() ? 0 : Double.parseDouble(toAmountStr);
            tvToUsd.setText("≈ " + CurrencyManager.formatFiat(this, toAmount * toPrice));
        } catch (Exception e) {
            tvFromUsd.setText("≈ " + CurrencyManager.formatFiat(this, 0));
            tvToUsd.setText("≈ " + CurrencyManager.formatFiat(this, 0));
        }
    }

    private void updateSwapButton() {
        String amountStr = etFromAmount.getText().toString().trim();
        if (amountStr.isEmpty()) {
            btnSwapConfirm.setText(getString(R.string.text_enter_amount));
            btnSwapConfirm.setEnabled(false);
        } else {
            try {
                double amount = Double.parseDouble(amountStr);
                // 修复：之前未校验 amount <= 0，输入 0 或负数也会启用按钮
                if (amount <= 0) {
                    btnSwapConfirm.setText(getString(R.string.text_amount_must_be_greater));
                    btnSwapConfirm.setEnabled(false);
                } else if (amount > fromBalance) {
                    btnSwapConfirm.setText(getString(R.string.toast_saldo_tidak_cukup));
                    btnSwapConfirm.setEnabled(false);
                } else {
                    btnSwapConfirm.setText(getString(R.string.text_exchange));
                    btnSwapConfirm.setEnabled(true);
                }
            } catch (Exception e) {
                btnSwapConfirm.setText(getString(R.string.text_enter_amount));
                btnSwapConfirm.setEnabled(false);
            }
        }
    }

    private void confirmSwap() {
        String amountStr = etFromAmount.getText().toString().trim();
        if (amountStr.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_please_enter_redemption_amount), Toast.LENGTH_SHORT).show();
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(amountStr);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_incorrect_amount_format), Toast.LENGTH_SHORT).show();
            return;
        }

        // 修复：与 SendActivity 保持一致，校验金额 > 0
        if (amount <= 0) {
            Toast.makeText(this, getString(R.string.toast_amount_must_be_greater), Toast.LENGTH_SHORT).show();
            return;
        }

        String message = "确认兑换信息：\n\n"
            + "从: " + amount + " " + fromToken + "\n"
            + "到: " + etToAmount.getText().toString() + " " + toToken + "\n"
            + "滑点: " + slippage + "%\n"
            + "最小获得: " + tvMinReceived.getText();

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_confirm_exchange))
            .setMessage(message)
            .setPositiveButton(getString(R.string.title_confirm_exchange), (dialog, which) -> executeSwap(amount))
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    private void executeSwap(double amount) {
        btnSwapConfirm.setEnabled(false);
        btnSwapConfirm.setText(getString(R.string.text_exchange));

        executor.execute(() -> {
            try {
                String txHash = dexTrader.swapTokens(
                    this,
                    fromTokenAddress,
                    toTokenAddress,
                    amount,
                    slippage
                );

                handler.post(() -> {
                    Toast.makeText(this, getString(R.string.toast_redeemed_successfully_tx, txHash.substring(0, 10)), Toast.LENGTH_LONG).show();
                    finish();
                });
            } catch (Exception e) {
                handler.post(() -> {
                    Toast.makeText(this, getString(R.string.toast_redemption_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                    btnSwapConfirm.setEnabled(true);
                    btnSwapConfirm.setText(getString(R.string.text_exchange));
                });
            }
        });
    }
}