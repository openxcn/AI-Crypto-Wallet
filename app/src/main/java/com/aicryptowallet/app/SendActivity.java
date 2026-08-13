package com.aicryptowallet.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.protobuf.ByteString;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;

import wallet.core.jni.CoinType;
import wallet.core.jni.PrivateKey;
import wallet.core.java.AnySigner;
import wallet.core.jni.proto.Ethereum;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

public class SendActivity extends BaseActivity {

    // startActivityForResult 请求码
    private static final int REQUEST_ADDRESS_BOOK = 1001;

    // 当前钱包下可选代币列表（点击 token symbol 切换时弹框用）
    // 每项: {symbol, name, balance, value, contract, isNative}
    private final List<String[]> tokenChoices = new ArrayList<>();

    private String tokenSymbol, tokenName, tokenBalance, tokenValue, contractAddress, chain;
    private double nativePrice = 0;
    private double tokenPrice = 0;
    private double gasPrice = 0;
    private String selectedGasSpeed = "normal";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    // 修复：改为静态单例，避免每次进入 Activity 都创建新 client 导致连接池/线程泄漏
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build();

    private EditText etToAddress, etAmount;
    private TextView tvAvailable, tvUsdValue, tvGasFee, tvTotal, tvNetwork;
    private TextView tvTokenSymbol, btnGasSlow, btnGasNormal, btnGasFast, btnSendConfirm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_send);

        tokenSymbol = getIntent().getStringExtra("symbol");
        tokenName = getIntent().getStringExtra("name");
        tokenBalance = getIntent().getStringExtra("balance");
        tokenValue = getIntent().getStringExtra("value");
        contractAddress = getIntent().getStringExtra("contract");
        chain = WalletManager.getChain(this);

        initViews();
        loadTokenInfo();
        loadGasPrice();
        setupListeners();
        // 后台预加载代币列表，用户点击切换时直接弹出，无等待
        loadTokenChoicesAsync();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 修复：关闭 executor 并移除 handler 回调，避免 Activity 销毁后异步任务仍持有引用导致内存泄漏
        executor.shutdownNow();
        handler.removeCallbacksAndMessages(null);
    }

    private void initViews() {
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            Logger.action(this, "UI操作", "返回", null);
            finish();
        });

        etToAddress = findViewById(R.id.etToAddress);
        etAmount = findViewById(R.id.etAmount);
        tvAvailable = findViewById(R.id.tvAvailable);
        tvUsdValue = findViewById(R.id.tvUsdValue);
        tvGasFee = findViewById(R.id.tvGasFee);
        tvTotal = findViewById(R.id.tvTotal);
        tvNetwork = findViewById(R.id.tvNetwork);
        tvTokenSymbol = findViewById(R.id.tvTokenSymbol);

        btnGasSlow = findViewById(R.id.btnGasSlow);
        btnGasNormal = findViewById(R.id.btnGasNormal);
        btnGasFast = findViewById(R.id.btnGasFast);
        btnSendConfirm = findViewById(R.id.btnSendConfirm);

        findViewById(R.id.btnPaste).setOnClickListener(v -> {
            Logger.action(this, "UI操作", "粘贴地址", null);
            pasteAddress();
        });
        findViewById(R.id.tvMaxLabel).setOnClickListener(v -> {
            Logger.action(this, "UI操作", "最大金额", null);
            setMaxAmount();
        });

        // 扫码转账：点击 📷 启动 zxing 扫码
        View btnScan = findViewById(R.id.btnScan);
        if (btnScan != null) {
            btnScan.setOnClickListener(v -> {
                Logger.action(this, "UI操作", "扫码", null);
                startQRScan();
            });
        }

        // 通讯录：点击 📇 打开通讯录选择联系人
        View btnAddressBook = findViewById(R.id.btnAddressBook);
        if (btnAddressBook != null) {
            btnAddressBook.setOnClickListener(v -> {
                Logger.action(this, "UI操作", "通讯录", null);
                try {
                    Intent intent = new Intent(this, AddressBookActivity.class);
                    startActivityForResult(intent, REQUEST_ADDRESS_BOOK);
                } catch (Exception e) {
                    Toast.makeText(this, getString(R.string.toast_failed_to_open_address, e.getMessage()), Toast.LENGTH_SHORT).show();
                }
            });
        }

        // 代币选择器：点击 token symbol 切换代币
        if (tvTokenSymbol != null) {
            tvTokenSymbol.setOnClickListener(v -> {
                Logger.action(this, "UI操作", "代币选择", null);
                showTokenPickerDialog();
            });
            // ▼ 提示符也响应点击
            View tvSwitchHint = findViewById(R.id.tvTokenSwitchHint);
            if (tvSwitchHint != null) {
                tvSwitchHint.setOnClickListener(v -> {
                    Logger.action(this, "UI操作", "代币选择", null);
                    showTokenPickerDialog();
                });
            }
        }
    }

    /** 启动 ZXing 扫码（用 ScanContract，新 API 不需要单独的 ScanActivity） */
    private void startQRScan() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt(getString(R.string.str_align_the_payout_address));
        options.setBeepEnabled(true);
        options.setOrientationLocked(false);
        options.setBarcodeImageEnabled(true);
        try {
            barcodeLauncher.launch(options);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_scanning_code_failed_to, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    /** ZXing 扫码结果回调 */
    private final androidx.activity.result.ActivityResultLauncher<ScanOptions> barcodeLauncher =
        registerForActivityResult(new ScanContract(), result -> {
            if (result == null) return;
            String contents = result.getContents();
            if (contents == null || contents.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_code_scan_canceled), Toast.LENGTH_SHORT).show();
                return;
            }
            handleScannedText(contents);
            Logger.actionResult(SendActivity.this, "UI操作", "扫码", "完成");
        });

    /**
     * 处理扫码结果：支持
     *  - 纯地址：直接填入
     *  - EIP-681 (ethereum:0xabc?value=1&token=0xdef)
     *  - 含 amount= 的 URI 自动填金额
     */
    private void handleScannedText(String raw) {
        try {
            String text = raw.trim();
            // 移除可能的前后引号
            if (text.startsWith("\"") && text.endsWith("\"")) {
                text = text.substring(1, text.length() - 1).trim();
            }

            String address;
            String amountStr = null;

            // 处理 EIP-681: ethereum:0x...?value=...
            int colonIdx = text.indexOf(':');
            if (colonIdx > 0 && colonIdx < text.length() - 1) {
                String rest = text.substring(colonIdx + 1);
                int qIdx = rest.indexOf('?');
                if (qIdx >= 0) {
                    address = rest.substring(0, qIdx);
                    String query = rest.substring(qIdx + 1);
                    // 简单解析参数
                    for (String kv : query.split("&")) {
                        int eq = kv.indexOf('=');
                        if (eq > 0) {
                            String k = kv.substring(0, eq);
                            String v = kv.substring(eq + 1);
                            if ("value".equalsIgnoreCase(k)) {
                                try {
                                    // value 可能是 wei 单位，转成 ETH
                                    BigInteger wei = new BigInteger(v, 10);
                                    double eth = new BigDecimal(wei)
                                        .divide(BigDecimal.valueOf(Math.pow(10, 18)))
                                        .doubleValue();
                                    amountStr = String.format("%.6f", eth);
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                } else {
                    address = rest;
                }
            } else {
                address = text;
            }

            // 用 wallet-core 校验地址
            if (!WalletManager.isValidAddress(address, chain)) {
                Toast.makeText(this, getString(R.string.toast_the_scanning_result_is, ChainAPI.getChainName(chain)), Toast.LENGTH_LONG).show();
                return;
            }

            etToAddress.setText(address);
            if (amountStr != null && !amountStr.isEmpty()) {
                etAmount.setText(amountStr);
            }
            Toast.makeText(this, getString(R.string.toast_filled_in_address, (amountStr != null ? getString(R.string.label_and_amount) : "")), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_failed_to_parse_scan, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ADDRESS_BOOK && resultCode == RESULT_OK && data != null) {
            String addr = data.getStringExtra(AddressBookActivity.EXTRA_ADDRESS);
            if (addr != null && !addr.isEmpty()) {
                etToAddress.setText(addr);
                Toast.makeText(this, getString(R.string.toast_selected_contact_address), Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 代币选择器：点击 token symbol 弹出当前钱包可用的代币列表
     * 后台异步加载，避免主线程网络请求卡顿
     */
    private void showTokenPickerDialog() {
        Logger.actionResult(this, "UI操作", "代币选择", "弹窗已打开");
        if (tokenChoices.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_loading_token_list), Toast.LENGTH_SHORT).show();
            loadTokenChoicesAsync();
            return;
        }
        renderTokenPickerDialog();
    }

    /** 后台加载当前钱包所有代币（原生币 + 代币） */
    private void loadTokenChoicesAsync() {
        executor.execute(() -> {
            try {
                List<String[]> list = new ArrayList<>();
                String address = WalletManager.getWalletAddress(this);
                String ch = WalletManager.getChain(this);

                // 1. 原生币
                double nativeBal = 0;
                try {
                    nativeBal = ChainAPI.getNativeBalance(this, ch, address);
                } catch (Exception ignored) {}
                double nativeValue = 0;
                try {
                    java.util.Map<String, Double> prices = ChainAPI.getPrices(this);
                    nativeValue = nativeBal * prices.getOrDefault(ch, 0.0);
                } catch (Exception ignored) {}
                list.add(new String[]{
                    ch, ChainAPI.getChainName(ch),
                    ChainAPI.formatAmount(nativeBal), CurrencyManager.formatFiat(this, nativeValue), "", "true"
                });

                // 2. 代币
                try {
                    List<String[]> tokens = ChainAPI.getAllTokenBalances(this, ch, address);
                    if (tokens != null) {
                        for (String[] t : tokens) {
                            if (t.length >= 5) {
                                list.add(new String[]{
                                    t[0], t[1], t[2], t[3], t[4], "false"
                                });
                            }
                        }
                    }
                } catch (Exception ignored) {}

                tokenChoices.clear();
                tokenChoices.addAll(list);
                handler.post(this::renderTokenPickerDialog);
            } catch (Exception e) {
                handler.post(() -> Toast.makeText(this, getString(R.string.toast_failed_to_load_token, e.getMessage()), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void renderTokenPickerDialog() {
        if (tokenChoices.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_tokens_available_in), Toast.LENGTH_SHORT).show();
            return;
        }
        // 延迟预加载（首次打开就有数据，下次切换更快）
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(48, 32, 48, 32);

        for (String[] t : tokenChoices) {
            TextView item = new TextView(this);
            String symbol = t[0];
            String name = t[1];
            String balance = t[2];
            item.setText(getString(R.string.text_nbalance, symbol, name, balance));
            item.setTextColor(0xFFFFFFFF);
            item.setTextSize(14);
            item.setPadding(24, 32, 24, 32);
            item.setBackgroundResource(R.drawable.card_background);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 12;
            item.setLayoutParams(lp);

            item.setOnClickListener(v -> {
                Logger.action(SendActivity.this, "UI操作", "选择代币-" + symbol, null);
                switchToken(t[0], t[1], t[2], t[3], t[4], "true".equals(t[5]));
                // 关闭弹窗
                if (tokenPickerDialog != null && tokenPickerDialog.isShowing()) {
                    tokenPickerDialog.dismiss();
                }
            });
            container.addView(item);
        }

        tokenPickerDialog = new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_select_token))
            .setView(container)
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .create();
        tokenPickerDialog.show();
    }

    private AlertDialog tokenPickerDialog;

    /** 切换当前要发送的代币 */
    private void switchToken(String symbol, String name, String balance, String value,
                              String contract, boolean isNative) {
        tokenSymbol = symbol;
        tokenName = name;
        tokenBalance = balance;
        tokenValue = value;
        contractAddress = isNative ? "" : contract;

        if (tvTokenSymbol != null) tvTokenSymbol.setText(tokenSymbol);
        if (tvAvailable != null) tvAvailable.setText(getString(R.string.text_available, tokenBalance));

        // 重置金额
        etAmount.setText("");
        // 重置价格
        tokenPrice = isNative ? nativePrice : 0;
        // 重新加载价格
        loadTokenInfo();
        // 重新加载 gas
        loadGasPrice();

        Toast.makeText(this, getString(R.string.toast_switched_to, symbol), Toast.LENGTH_SHORT).show();
    }

    private void loadTokenInfo() {
        ((TextView) findViewById(R.id.tvTokenSymbol)).setText(tokenSymbol);
        tvAvailable.setText(getString(R.string.text_available, tokenBalance));
        tvNetwork.setText(ChainAPI.getChainName(chain));

        executor.execute(() -> {
            try {
                java.util.Map<String, Double> prices = ChainAPI.getPrices(this);
                nativePrice = prices.getOrDefault(chain, 0.0);

                if (contractAddress != null && !contractAddress.isEmpty()) {
                    try {
                        // 按链映射 CoinGecko asset_platform_id，修复之前硬编码 ethereum 导致多链代币价格错误
                        String platform = getCoinGeckoPlatform(chain);
                        String url = "https://api.coingecko.com/api/v3/simple/token_price/" + platform
                            + "?contract_addresses=" + contractAddress + "&vs_currencies=usd";
                        Request request = new Request.Builder().url(url).get().build();
                        try (Response response = CLIENT.newCall(request).execute()) {
                            String resp = response.body() != null ? response.body().string() : "";
                            JSONObject json = new JSONObject(resp);
                            if (json.has(contractAddress.toLowerCase())) {
                                tokenPrice = json.getJSONObject(contractAddress.toLowerCase()).optDouble("usd", 0);
                            }
                        }
                    } catch (Exception e) {
                        tokenPrice = 0;
                    }
                } else {
                    tokenPrice = nativePrice;
                }

                handler.post(() -> updateUsdValue());
            } catch (Exception e) {
                // Price fetch failed
            }
        });
    }

    private void loadGasPrice() {
        if (!ChainAPI.isEVM(chain)) {
            tvGasFee.setText(getString(R.string.text_automatically_calculate));
            return;
        }

        executor.execute(() -> {
            try {
                String rpcUrl = WalletManager.getRpcUrl(this, chain);
                JSONObject body = new JSONObject();
                body.put("jsonrpc", "2.0");
                body.put("id", 1);
                body.put("method", "eth_gasPrice");
                body.put("params", new JSONArray());

                Request request = new Request.Builder()
                    .url(rpcUrl)
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build();

                try (Response response = CLIENT.newCall(request).execute()) {
                    String resp = response.body() != null ? response.body().string() : "";
                    JSONObject json = new JSONObject(resp);
                    String gasPriceHex = json.optString("result", "0x0");
                    gasPrice = new BigInteger(gasPriceHex.substring(2), 16).doubleValue() / Math.pow(10, 9);

                    handler.post(() -> updateGasFee());
                }
            } catch (Exception e) {
                handler.post(() -> tvGasFee.setText(getString(R.string.text_loading_failed)));
            }
        });
    }

    private void setupListeners() {
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                updateUsdValue();
                updateTotal();
            }
        };

        etAmount.addTextChangedListener(watcher);

        btnGasSlow.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "Gas费-慢速", null);
            Logger.actionResult(this, "UI操作", "Gas费", "慢速");
            selectedGasSpeed = "slow";
            updateGasButtons();
            updateGasFee();
            updateTotal();
        });

        btnGasNormal.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "Gas费-标准", null);
            Logger.actionResult(this, "UI操作", "Gas费", "标准");
            selectedGasSpeed = "normal";
            updateGasButtons();
            updateGasFee();
            updateTotal();
        });

        btnGasFast.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "Gas费-快速", null);
            Logger.actionResult(this, "UI操作", "Gas费", "快速");
            selectedGasSpeed = "fast";
            updateGasButtons();
            updateGasFee();
            updateTotal();
        });

        btnSendConfirm.setOnClickListener(v -> {
            Logger.action(this, "UI操作", "确认发送", null);
            confirmSend();
        });
    }

    private void updateGasButtons() {
        int defaultColor = getResources().getColor(R.color.text_secondary);
        int activeColor = getResources().getColor(R.color.gradient_start);

        btnGasSlow.setTextColor(defaultColor);
        btnGasNormal.setTextColor(defaultColor);
        btnGasFast.setTextColor(defaultColor);

        if ("slow".equals(selectedGasSpeed)) btnGasSlow.setTextColor(activeColor);
        else if ("normal".equals(selectedGasSpeed)) btnGasNormal.setTextColor(activeColor);
        else if ("fast".equals(selectedGasSpeed)) btnGasFast.setTextColor(activeColor);
    }

    private void updateUsdValue() {
        try {
            String amountStr = etAmount.getText().toString().trim();
            if (amountStr.isEmpty()) {
                tvUsdValue.setText("≈ " + CurrencyManager.formatFiat(this, 0));
                return;
            }
            double amount = Double.parseDouble(amountStr);
            double usd = amount * tokenPrice;
            tvUsdValue.setText("≈ " + CurrencyManager.formatFiat(this, usd));
        } catch (Exception e) {
            tvUsdValue.setText("≈ " + CurrencyManager.formatFiat(this, 0));
        }
    }

    private void updateGasFee() {
        if (!ChainAPI.isEVM(chain)) return;

        double multiplier = 1.0;
        if ("slow".equals(selectedGasSpeed)) multiplier = 0.8;
        else if ("fast".equals(selectedGasSpeed)) multiplier = 1.3;

        double estimatedGas = 21000;
        if (contractAddress != null && !contractAddress.isEmpty()) {
            estimatedGas = 65000;
        }

        double gasFee = gasPrice * multiplier * estimatedGas / Math.pow(10, 9);
        String gasFeeStr = String.format("%.6f", gasFee);
        double gasFeeUsd = gasFee * nativePrice;

        tvGasFee.setText(gasFeeStr + " " + chain + " (≈" + CurrencyManager.formatFiat(this, gasFeeUsd) + ")");
    }

    private void updateTotal() {
        try {
            String amountStr = etAmount.getText().toString().trim();
            double amount = amountStr.isEmpty() ? 0 : Double.parseDouble(amountStr);

            double gasAmount = 0;
            if (ChainAPI.isEVM(chain)) {
                double multiplier = 1.0;
                if ("slow".equals(selectedGasSpeed)) multiplier = 0.8;
                else if ("fast".equals(selectedGasSpeed)) multiplier = 1.3;

                double estimatedGas = contractAddress != null && !contractAddress.isEmpty() ? 65000 : 21000;
                gasAmount = gasPrice * multiplier * estimatedGas / Math.pow(10, 9);
            }

            double total = amount + gasAmount;
            tvTotal.setText(String.format("%.6f", total) + " " + tokenSymbol);
        } catch (Exception e) {
            tvTotal.setText("0 " + tokenSymbol);
        }
    }

    private void pasteAddress() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm.hasPrimaryClip()) {
            ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
            String text = item.getText().toString().trim();
            // 用 wallet-core 验证地址合法性
            if (WalletManager.isValidAddress(text, chain)) {
                etToAddress.setText(text);
                Toast.makeText(this, getString(R.string.toast_pasted_address), Toast.LENGTH_SHORT).show();
                Logger.actionResult(this, "UI操作", "粘贴地址", "成功");
            } else {
                Toast.makeText(this, getString(R.string.toast_clipboard_content_is_not), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void setMaxAmount() {
        try {
            double balance = Double.parseDouble(tokenBalance.replace(",", ""));
            double gasAmount = 0;

            if (ChainAPI.isEVM(chain) && (contractAddress == null || contractAddress.isEmpty())) {
                double multiplier = 1.0;
                if ("slow".equals(selectedGasSpeed)) multiplier = 0.8;
                else if ("fast".equals(selectedGasSpeed)) multiplier = 1.3;

                gasAmount = gasPrice * multiplier * 21000 / Math.pow(10, 9);
                balance = Math.max(0, balance - gasAmount);
            }

            etAmount.setText(String.format("%.6f", balance));
            Logger.actionResult(this, "UI操作", "最大金额", tokenBalance);
        } catch (Exception e) {
            // Parse failed
        }
    }

    private void confirmSend() {
        String toAddress = etToAddress.getText().toString().trim();
        String amountStr = etAmount.getText().toString().trim();

        if (toAddress.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_please_enter_payout_address), Toast.LENGTH_SHORT).show();
            return;
        }

        // 用 wallet-core 验证地址
        if (!WalletManager.isValidAddress(toAddress, chain)) {
            Toast.makeText(this, getString(R.string.toast_incorrect_address_format_chain, ChainAPI.getChainName(chain)), Toast.LENGTH_SHORT).show();
            return;
        }

        if (amountStr.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_please_enter_the_transfer), Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            double amount = Double.parseDouble(amountStr);
            double balance = Double.parseDouble(tokenBalance.replace(",", ""));

            if (amount > balance) {
                Toast.makeText(this, getString(R.string.toast_saldo_tidak_cukup), Toast.LENGTH_SHORT).show();
                return;
            }

            if (amount <= 0) {
                Toast.makeText(this, getString(R.string.toast_amount_must_be_greater), Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_incorrect_amount_format), Toast.LENGTH_SHORT).show();
            return;
        }

        // AI 风险检查：如果代币在黑名单中，阻止发送
        // R-MAB 平台币永远不受黑名单限制
        if (contractAddress != null && !contractAddress.isEmpty()
                && !TokenRiskAnalyzer.RMAB_CONTRACT.equalsIgnoreCase(contractAddress)
                && RiskManager.isBlacklisted(this, chain, contractAddress)) {
            new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setTitle(getString(R.string.title_ai_security_interception))
                .setMessage(getString(R.string.msg_token_blacklisted_send, tokenSymbol))
                .setPositiveButton(getString(R.string.btn_got_it), null)
                .show();
            return;
        }

        // 如果代币在白名单中，记录风险操作
        if (contractAddress != null && !contractAddress.isEmpty()
                && RiskManager.isWhitelisted(this, chain, contractAddress)) {
            RiskManager.addRiskLog(this, chain, contractAddress, tokenSymbol,
                "SEND", "用户向白名单高风险代币发起转账: " + amountStr + " " + tokenSymbol);
        }

        String fromAddress = WalletManager.getWalletAddress(this);
        String fromDisplay = fromAddress.length() > 10
            ? fromAddress.substring(0, 6) + "..." + fromAddress.substring(fromAddress.length() - 4)
            : fromAddress;
        String toDisplay = toAddress.length() > 10
            ? toAddress.substring(0, 6) + "..." + toAddress.substring(toAddress.length() - 4)
            : toAddress;

        String message = "确认转账信息：\n\n"
            + "从: " + fromDisplay + "\n"
            + "到: " + toDisplay + "\n"
            + "金额: " + amountStr + " " + tokenSymbol + "\n"
            + "网络: " + ChainAPI.getChainName(chain);

        if (ChainAPI.isEVM(chain)) {
            message += "\nGas: " + tvGasFee.getText();
        }

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_proceed_to_transfer))
            .setMessage(message)
            .setPositiveButton(getString(R.string.btn_confirm_send), (dialog, which) -> promptPaymentPassword(toAddress, amountStr))
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }

    /**
     * 支付密码验证 - 用户手动转账必须验证
     *
     * 钱包密码来源：创建钱包/导入钱包时用户输入的密码（PBKDF2 哈希存储）
     * 进入 App 不需要密码（符合行业惯例），但资金转出必须验证支付密码
     */
    private void promptPaymentPassword(String toAddress, String amountStr) {
        try {
            // —— 深色精致的密码输入区 ——
            // 副标题提示
            TextView tvSub = new TextView(this);
            tvSub.setText(getString(R.string.msg_please_enter_the_payment));
            tvSub.setTextColor(0xFF8892B0);
            tvSub.setTextSize(13);

            // 密码输入框：加大圆点、清晰可见
            final EditText etPwd = new EditText(this);
            etPwd.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            etPwd.setHint(getString(R.string.hint_enter_payment_password));
            etPwd.setTextColor(0xFFFFFFFF);
            etPwd.setHintTextColor(0xFF4a4a6a);
            etPwd.setTextSize(18);
            etPwd.setSingleLine(true);
            etPwd.setPadding(0, 12, 0, 12);
            etPwd.setBackgroundColor(0x00FFFFFF);

            // 小眼睛按钮：切换明文/密文
            final android.widget.ImageButton btnEye = new android.widget.ImageButton(this);
            btnEye.setImageResource(R.drawable.ic_eye);
            btnEye.setBackground(null);
            btnEye.setContentDescription("显示密码");
            btnEye.setPadding(8, 8, 8, 8);
            btnEye.setOnClickListener(v -> {
                int start = etPwd.getSelectionStart();
                int end = etPwd.getSelectionEnd();
                if (etPwd.getInputType() == (android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)) {
                    etPwd.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                            | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                    btnEye.setImageResource(R.drawable.ic_eye_off);
                } else {
                    etPwd.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    btnEye.setImageResource(R.drawable.ic_eye);
                }
                // 切换后保持光标位置
                etPwd.setSelection(start, end);
            });

            // 密码框 + 眼睛并排
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.addView(etPwd, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(btnEye, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            // 底部细分割线（输入框下缘）
            final View divider = new View(this);
            divider.setBackgroundColor(0xFF4a4a6a);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
            divLp.topMargin = 2;

            // 整体容器
            LinearLayout container = new LinearLayout(this);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(48, 24, 48, 16);
            container.addView(tvSub, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            container.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            container.addView(divider, divLp);

            // 密码错误时抖动输入框
            java.util.concurrent.atomic.AtomicReference<AlertDialog> dialogRef =
                new java.util.concurrent.atomic.AtomicReference<>();

            AlertDialog dlg = new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                .setTitle(getString(R.string.title_paypal_password))
                .setView(container)
                .setPositiveButton(getString(R.string.btn_okay), (d, w) -> {
                    String inputPwd = etPwd.getText().toString();
                    if (inputPwd.isEmpty()) {
                        Toast.makeText(this, getString(R.string.toast_password), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 后台验证密码（PBKDF2 哈希耗时较高，避免阻塞 UI）
                    d.dismiss();
                    executor.execute(() -> {
                        boolean ok = WalletManager.verifyPassword(this, inputPwd);
                        handler.post(() -> {
                            if (ok) {
                                executeTransfer(toAddress, amountStr);
                            } else {
                                shakeView(container);
                                Toast.makeText(this, getString(R.string.toast_wrong_password), Toast.LENGTH_LONG).show();
                            }
                        });
                    });
                })
                .setNegativeButton(getString(R.string.btn_s_decline), null)
                .create();
            dlg.setOnShowListener(d -> {
                // 统一按钮颜色，贴合主题
                dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(0xFFFF453A);
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setTextColor(0xFF8892B0);
            });
            dialogRef.set(dlg);
            dlg.show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_password_verification_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /** 输入框抖动动画（密码错误反馈） */
    private void shakeView(View view) {
        android.view.animation.TranslateAnimation shake =
            new android.view.animation.TranslateAnimation(0, 14, 0, 0);
        shake.setDuration(60);
        shake.setRepeatMode(android.view.animation.Animation.REVERSE);
        shake.setRepeatCount(4);
        view.startAnimation(shake);
    }

    /**
     * 统一转账入口 - 根据链类型分发到对应的签名逻辑
     */
    private void executeTransfer(String toAddress, String amountStr) {
        btnSendConfirm.setEnabled(false);
        btnSendConfirm.setText(getString(R.string.text_sending));
        btnSendConfirm.setTextColor(getResources().getColor(R.color.text_secondary));

        executor.execute(() -> {
            try {
                String txHash;
                if (ChainAPI.isEVM(chain)) {
                    // EVM 链：使用 wallet-core 的 Ethereum 签名
                    if (contractAddress != null && !contractAddress.isEmpty()) {
                        txHash = sendEVMERC20(toAddress, amountStr);
                    } else {
                        txHash = sendEVMNative(toAddress, amountStr);
                    }
                } else {
                    // 非 EVM 链：使用 wallet-core AnySigner 统一签名
                    txHash = sendNonEVM(toAddress, amountStr);
                }

                // 修复：之前 txHash 为空时（RPC 返回空对象/限流/截断）也显示"交易已发送"并 finish()
                // 用户误以为成功，实际链上无交易，可能造成违约或重复转账
                if (txHash == null || txHash.isEmpty()) {
                    throw new Exception("交易广播失败：节点未返回交易哈希（可能限流或网络异常）");
                }
                String finalTxHash = txHash;
                handler.post(() -> {
                    Toast.makeText(this, getString(R.string.toast_transaction_sent_tx, finalTxHash.substring(0, Math.min(10, finalTxHash.length()))), Toast.LENGTH_LONG).show();
                    Logger.actionResult(SendActivity.this, "UI操作", "确认发送", tokenSymbol + " 广播成功");
                    finish();
                });
            } catch (Exception e) {
                handler.post(() -> {
                    Toast.makeText(this, getString(R.string.toast_failed_to_send, e.getMessage()), Toast.LENGTH_LONG).show();
                    Logger.actionResult(SendActivity.this, "UI操作", "确认发送", "失败");
                    btnSendConfirm.setEnabled(true);
                    btnSendConfirm.setText(getString(R.string.title_proceed_to_transfer));
                    btnSendConfirm.setTextColor(getResources().getColor(R.color.text_primary));
                });
            }
        });
    }

    /**
     * EVM链原生币转账 - 使用 wallet-core Ethereum SigningInput
     */
    private String sendEVMNative(String toAddress, String amountStr) throws Exception {
        String rpcUrl = WalletManager.getRpcUrl(this, chain);
        String mnemonic = WalletManager.getMnemonic(this);
        String fromAddress = WalletManager.getWalletAddress(this);

        // 获取私钥
        PrivateKey privateKey = WalletManager.getPrivateKey(mnemonic, chain);
        if (privateKey == null) throw new Exception("私钥推导失败");

        // 获取 nonce
        BigInteger nonce = BigInteger.valueOf(getNonce(rpcUrl, fromAddress));

        // 获取 chainId
        long chainId = getChainId(rpcUrl);

        // Gas 参数
        double multiplier = 1.0;
        if ("slow".equals(selectedGasSpeed)) multiplier = 0.8;
        else if ("fast".equals(selectedGasSpeed)) multiplier = 1.3;
        BigInteger gasPriceWei = BigDecimal.valueOf(gasPrice * multiplier)
            .multiply(BigDecimal.valueOf(Math.pow(10, 9)))
            .toBigInteger();
        BigInteger gasLimit = BigInteger.valueOf(21000);

        // 金额转 wei
        BigInteger value = BigDecimal.valueOf(Double.parseDouble(amountStr))
            .multiply(BigDecimal.valueOf(Math.pow(10, 18)))
            .toBigInteger();

        // 使用 wallet-core 构建 EVM 交易签名
        Ethereum.SigningInput signerInput = Ethereum.SigningInput.newBuilder()
            .setChainId(ByteString.copyFrom(BigInteger.valueOf(chainId).toByteArray()))
            .setNonce(ByteString.copyFrom(nonce.toByteArray()))
            .setGasPrice(ByteString.copyFrom(gasPriceWei.toByteArray()))
            .setGasLimit(ByteString.copyFrom(gasLimit.toByteArray()))
            .setToAddress(toAddress)
            .setTransaction(Ethereum.Transaction.newBuilder()
                .setTransfer(Ethereum.Transaction.Transfer.newBuilder()
                    .setAmount(ByteString.copyFrom(value.toByteArray()))
                    .build())
                .build())
            .setPrivateKey(ByteString.copyFrom(privateKey.data()))
            .build();

        Ethereum.SigningOutput output = AnySigner.sign(signerInput, CoinType.ETHEREUM, Ethereum.SigningOutput.parser());
        String signedTx = bytesToHex(output.getEncoded().toByteArray());

        return broadcastTx(rpcUrl, signedTx);
    }

    /**
     * EVM链代币转账（ERC-20/BEP-20） - 使用 wallet-core Ethereum SigningInput
     */
    private String sendEVMERC20(String toAddress, String amountStr) throws Exception {
        String rpcUrl = WalletManager.getRpcUrl(this, chain);
        String mnemonic = WalletManager.getMnemonic(this);
        String fromAddress = WalletManager.getWalletAddress(this);

        PrivateKey privateKey = WalletManager.getPrivateKey(mnemonic, chain);
        if (privateKey == null) throw new Exception("私钥推导失败");

        int decimals = getTokenDecimals(rpcUrl, contractAddress);
        BigInteger tokenAmount = BigDecimal.valueOf(Double.parseDouble(amountStr))
            .multiply(BigDecimal.valueOf(Math.pow(10, decimals)))
            .toBigInteger();

        BigInteger nonce = BigInteger.valueOf(getNonce(rpcUrl, fromAddress));
        long chainId = getChainId(rpcUrl);

        double multiplier = 1.0;
        if ("slow".equals(selectedGasSpeed)) multiplier = 0.8;
        else if ("fast".equals(selectedGasSpeed)) multiplier = 1.3;
        BigInteger gasPriceWei = BigDecimal.valueOf(gasPrice * multiplier)
            .multiply(BigDecimal.valueOf(Math.pow(10, 9)))
            .toBigInteger();
        BigInteger gasLimit = BigInteger.valueOf(65000);

        // 代币转账：使用 wallet-core 的 ERC20Transfer message
        // toAddress=代币合约地址, ERC20Transfer.to=收款地址, ERC20Transfer.amount=代币数量
        Ethereum.SigningInput signerInput = Ethereum.SigningInput.newBuilder()
            .setChainId(ByteString.copyFrom(BigInteger.valueOf(chainId).toByteArray()))
            .setNonce(ByteString.copyFrom(nonce.toByteArray()))
            .setGasPrice(ByteString.copyFrom(gasPriceWei.toByteArray()))
            .setGasLimit(ByteString.copyFrom(gasLimit.toByteArray()))
            .setToAddress(contractAddress)
            .setTransaction(Ethereum.Transaction.newBuilder()
                .setErc20Transfer(Ethereum.Transaction.ERC20Transfer.newBuilder()
                    .setTo(toAddress)
                    .setAmount(ByteString.copyFrom(tokenAmount.toByteArray()))
                    .build())
                .build())
            .setPrivateKey(ByteString.copyFrom(privateKey.data()))
            .build();

        Ethereum.SigningOutput output = AnySigner.sign(signerInput, CoinType.ETHEREUM, Ethereum.SigningOutput.parser());
        String signedTx = bytesToHex(output.getEncoded().toByteArray());

        return broadcastTx(rpcUrl, signedTx);
    }

    /**
     * 非EVM链转账 - 使用 wallet-core AnySigner 签名 + 各链 RPC 广播
     * 已支持：SOL、TRX、ATOM 三条主流非EVM链
     * 其他链保持占位
     */
    private String sendNonEVM(String toAddress, String amountStr) throws Exception {
        switch (chain) {
            case "SOL":
                return sendSolana(toAddress, amountStr);
            case "TRX":
                return sendTron(toAddress, amountStr);
            case "ATOM":
                return sendCosmos(toAddress, amountStr);
            default:
                throw new Exception(chain + " 链转账签名开发中。\n" +
                    "已支持：EVM链(ETH/BNB/AVAX/MATIC等) + SOL/TRX/ATOM\n" +
                    "地址推导和验证：全部20条链");
        }
    }

    /**
     * Solana 转账 - wallet-core 签名 + Solana JSON RPC 广播
     */
    private String sendSolana(String toAddress, String amountStr) throws Exception {
        String rpcUrl = WalletManager.getRpcUrl(this, "SOL");
        String mnemonic = WalletManager.getMnemonic(this);
        PrivateKey privateKey = WalletManager.getPrivateKey(mnemonic, "SOL");

        // amount: SOL → lamports (1 SOL = 10^9 lamports)，用 BigDecimal 避免浮点精度损失
        long lamports = new java.math.BigDecimal(amountStr)
            .multiply(java.math.BigDecimal.TEN.pow(9)).longValue();

        // 获取最新 blockhash
        JSONObject bhReq = new JSONObject();
        bhReq.put("jsonrpc", "2.0");
        bhReq.put("id", 1);
        bhReq.put("method", "getLatestBlockhash");
        bhReq.put("params", new JSONArray());
        String blockhash;
        try (Response r = CLIENT.newCall(new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(bhReq.toString(), MediaType.parse("application/json")))
            .build()).execute()) {
            JSONObject resp = new JSONObject(r.body() != null ? r.body().string() : "{}");
            blockhash = resp.getJSONObject("result").getJSONObject("value").getString("blockhash");
        }

        // 使用 wallet-core Solana.SigningInput 签名
        wallet.core.jni.proto.Solana.SigningInput input = wallet.core.jni.proto.Solana.SigningInput.newBuilder()
            .setPrivateKey(ByteString.copyFrom(privateKey.data()))
            .setRecentBlockhash(blockhash)
            .setTransferTransaction(wallet.core.jni.proto.Solana.Transfer.newBuilder()
                .setRecipient(toAddress)
                .setValue(lamports)
                .build())
            .build();
        wallet.core.jni.proto.Solana.SigningOutput output = AnySigner.sign(input, CoinType.SOLANA,
            wallet.core.jni.proto.Solana.SigningOutput.parser());

        // encoded 字段已经是 base64 字符串，直接使用，避免双重编码
        String signedTxBase64 = output.getEncoded();

        // 广播
        JSONObject sendReq = new JSONObject();
        sendReq.put("jsonrpc", "2.0");
        sendReq.put("id", 1);
        sendReq.put("method", "sendTransaction");
        JSONArray params = new JSONArray();
        params.put(signedTxBase64);
        params.put(new JSONObject().put("encoding", "base64"));
        sendReq.put("params", params);

        try (Response r = CLIENT.newCall(new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(sendReq.toString(), MediaType.parse("application/json")))
            .build()).execute()) {
            JSONObject resp = new JSONObject(r.body() != null ? r.body().string() : "{}");
            if (resp.has("error")) throw new Exception("Solana 广播失败: " + resp.getJSONObject("error"));
            return resp.optString("result", "");
        }
    }

    /**
     * Tron 转账 - wallet-core 签名 + TronGrid HTTP API 广播
     */
    private String sendTron(String toAddress, String amountStr) throws Exception {
        String apiUrl = WalletManager.getRpcUrl(this, "TRX");
        String mnemonic = WalletManager.getMnemonic(this);
        PrivateKey privateKey = WalletManager.getPrivateKey(mnemonic, "TRX");
        String fromAddress = WalletManager.getWalletAddress(this);

        // amount: TRX → sun (1 TRX = 10^6 sun)，用 BigDecimal 避免浮点精度损失
        long sun = new java.math.BigDecimal(amountStr)
            .multiply(java.math.BigDecimal.TEN.pow(6)).longValue();

        // 获取最新区块
        JSONObject pbBody = new JSONObject();
        pbBody.put("only_block", false);
        pbBody.put("detail", false);
        JSONObject block;
        try (Response r = CLIENT.newCall(new Request.Builder()
            .url(apiUrl + "/wallet/getblock")
            .post(RequestBody.create(pbBody.toString(), MediaType.parse("application/json")))
            .build()).execute()) {
            JSONArray arr = new JSONArray(r.body() != null ? r.body().string() : "[]");
            block = arr.getJSONObject(0);
        }
        String refBlockHash = block.getJSONObject("block_header").getJSONObject("raw_data").getString("ref_block_hash");
        long refBlockNum = block.getJSONObject("block_header").getJSONObject("raw_data").getLong("number");
        long timestamp = System.currentTimeMillis();

        // wallet-core Tron.SigningInput
        wallet.core.jni.proto.Tron.SigningInput input = wallet.core.jni.proto.Tron.SigningInput.newBuilder()
            .setTransaction(wallet.core.jni.proto.Tron.Transaction.newBuilder()
                .setTransfer(wallet.core.jni.proto.Tron.TransferContract.newBuilder()
                    .setOwnerAddress(fromAddress)
                    .setToAddress(toAddress)
                    .setAmount(sun)
                    .build())
                .setTimestamp(timestamp)
                .setExpiration(timestamp + 60_000)
                .setBlockHeader(wallet.core.jni.proto.Tron.BlockHeader.newBuilder()
                    .setTimestamp(block.getJSONObject("block_header").getJSONObject("raw_data").getLong("timestamp"))
                    .setNumber(refBlockNum)
                    .setVersion(block.getJSONObject("block_header").getJSONObject("raw_data").getInt("version"))
                    .setTxTrieRoot(ByteString.copyFrom(hexToBytes(block.getJSONObject("block_header").getJSONObject("raw_data").optString("txTrieRoot"))))
                    .setWitnessAddress(ByteString.copyFrom(hexToBytes(block.getJSONObject("block_header").getJSONObject("raw_data").optString("witnessAddress"))))
                    .setParentHash(ByteString.copyFrom(hexToBytes(block.getJSONObject("block_header").getJSONObject("raw_data").optString("parentHash"))))
                    .build())
                .build())
            .setPrivateKey(ByteString.copyFrom(privateKey.data()))
            .build();
        wallet.core.jni.proto.Tron.SigningOutput output = AnySigner.sign(input, CoinType.TRON,
            wallet.core.jni.proto.Tron.SigningOutput.parser());

        // SigningOutput.getJson() 返回已签名的完整交易 JSON，直接广播即可
        if (output.getError().getNumber() != 0) {
            throw new Exception("Tron 签名失败: " + output.getErrorMessage());
        }
        String signedTxJson = output.getJson();

        try (Response r = CLIENT.newCall(new Request.Builder()
            .url(apiUrl + "/wallet/broadcast")
            .post(RequestBody.create(signedTxJson, MediaType.parse("application/json")))
            .build()).execute()) {
            JSONObject resp = new JSONObject(r.body() != null ? r.body().string() : "{}");
            if (resp.has("code") && resp.optInt("code") != 0) {
                throw new Exception("Tron 广播失败: " + resp.optString("message", resp.optString("Error")));
            }
            return resp.optString("txid", resp.optString("transaction", ""));
        }
    }

    /**
     * Cosmos 转账 - wallet-core 签名 + Cosmos REST API 广播
     */
    private String sendCosmos(String toAddress, String amountStr) throws Exception {
        String apiUrl = WalletManager.getRpcUrl(this, "ATOM");
        String mnemonic = WalletManager.getMnemonic(this);
        PrivateKey privateKey = WalletManager.getPrivateKey(mnemonic, "ATOM");
        String fromAddress = WalletManager.getWalletAddress(this);

        // amount: ATOM → uatom (1 ATOM = 10^6 uatom)，用 BigDecimal 避免浮点精度损失
        long uatom = new java.math.BigDecimal(amountStr)
            .multiply(java.math.BigDecimal.TEN.pow(6)).longValue();

        // 获取 account number + sequence
        JSONObject acctInfo;
        try (Response r = CLIENT.newCall(new Request.Builder()
            .url(apiUrl + "/cosmos/auth/v1beta1/accounts/" + fromAddress)
            .get().build()).execute()) {
            acctInfo = new JSONObject(r.body() != null ? r.body().string() : "{}");
        }
        JSONObject acct = acctInfo.getJSONObject("account");
        long accountNumber = acct.getLong("account_number");
        long sequence = acct.getLong("sequence");

        // Cosmos chainId 是字符串（如 "cosmoshub-4"），不能转为 long
        String cosmosChainId = "cosmoshub-4";

        // wallet-core Cosmos.SigningInput
        wallet.core.jni.proto.Cosmos.SigningInput input = wallet.core.jni.proto.Cosmos.SigningInput.newBuilder()
            .setPrivateKey(ByteString.copyFrom(privateKey.data()))
            .setAccountNumber(accountNumber)
            .setSequence(sequence)
            .setMode(wallet.core.jni.proto.Cosmos.BroadcastMode.SYNC)
            .setSigningMode(wallet.core.jni.proto.Cosmos.SigningMode.Protobuf)
            .setChainId(cosmosChainId)
            .addMessages(wallet.core.jni.proto.Cosmos.Message.newBuilder()
                .setSendCoinsMessage(wallet.core.jni.proto.Cosmos.Message.Send.newBuilder()
                    .setFromAddress(fromAddress)
                    .setToAddress(toAddress)
                    .addAmounts(wallet.core.jni.proto.Cosmos.Amount.newBuilder()
                        .setAmount(String.valueOf(uatom))
                        .setDenom("uatom")
                        .build())
                    .build())
                .build())
            .build();
        wallet.core.jni.proto.Cosmos.SigningOutput output = AnySigner.sign(input, CoinType.COSMOS,
            wallet.core.jni.proto.Cosmos.SigningOutput.parser());

        // 广播
        JSONObject broadcastBody = new JSONObject();
        broadcastBody.put("tx_bytes", android.util.Base64.encodeToString(output.getSerializedBytes().toByteArray(), android.util.Base64.NO_WRAP));
        broadcastBody.put("mode", "BROADCAST_MODE_SYNC");

        try (Response r = CLIENT.newCall(new Request.Builder()
            .url(apiUrl + "/cosmos/tx/v1beta1/txs")
            .post(RequestBody.create(broadcastBody.toString(), MediaType.parse("application/json")))
            .build()).execute()) {
            JSONObject resp = new JSONObject(r.body() != null ? r.body().string() : "{}");
            if (resp.has("code") && resp.getInt("code") != 0) {
                throw new Exception("Cosmos 广播失败: " + resp.optString("raw_log"));
            }
            return resp.optJSONObject("tx_response") != null
                ? resp.getJSONObject("tx_response").optString("txhash", "")
                : resp.optString("txhash", "");
        }
    }

    // ========== RPC 通信方法 ==========

    private long getNonce(String rpcUrl, String address) throws Exception {
        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "eth_getTransactionCount");
        JSONArray params = new JSONArray();
        params.put(address);
        // 修复：与 DexTrader/TradeFeeManager 保持一致，用 pending 包含 mempool 中未确认交易
        // 之前用 latest 只统计已上链交易，用户连发两笔会用相同 nonce 导致交易被替换或拒绝
        params.put("pending");
        body.put("params", params);

        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
            .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            String nonceHex = json.optString("result", "0x0");
            return Long.parseLong(nonceHex.substring(2), 16);
        }
    }

    private int getTokenDecimals(String rpcUrl, String contractAddress) throws Exception {
        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "eth_call");
        JSONArray params = new JSONArray();
        JSONObject callObj = new JSONObject();
        callObj.put("to", contractAddress);
        callObj.put("data", "0x313ce567");
        params.put(callObj);
        params.put("latest");
        body.put("params", params);

        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
            .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            String result = json.optString("result", "0x");
            // 防护空返回或非标准合约，默认 18
            if (result.length() <= 2 || result.equals("0x")) return 18;
            return Integer.parseInt(result.substring(2), 16);
        }
    }

    private long getChainId(String rpcUrl) throws Exception {
        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "eth_chainId");
        body.put("params", new JSONArray());

        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
            .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            String chainIdHex = json.optString("result", "0x1");
            return Long.parseLong(chainIdHex.substring(2), 16);
        }
    }

    private String broadcastTx(String rpcUrl, String signedTx) throws Exception {
        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "eth_sendRawTransaction");
        JSONArray params = new JSONArray();
        params.put("0x" + signedTx);
        body.put("params", params);

        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
            .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            if (json.has("error")) {
                throw new Exception(json.getJSONObject("error").optString("message", "未知错误"));
            }
            return json.optString("result", "");
        }
    }

    // ========== 工具方法 ==========

    private String padHex(String hex, int length) {
        while (hex.length() < length) {
            hex = "0" + hex;
        }
        return hex;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null) return new byte[0];
        hex = hex.startsWith("0x") ? hex.substring(2) : hex;
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /**
     * 将链代码映射到 CoinGecko 的 asset_platform_id
     * 修复之前所有链都用 "ethereum" 平台导致多链代币价格查询错误
     */
    private static String getCoinGeckoPlatform(String chain) {
        if (chain == null) return "ethereum";
        switch (chain) {
            case "ETH":   return "ethereum";
            case "BNB":   return "binance-smart-chain";
            case "MATIC": return "polygon-pos";
            case "AVAX":  return "avalanche";
            case "FTM":   return "fantom";
            case "GLMR":  return "moonbeam";
            case "KAVA":  return "kava";
            case "CELO":  return "celo";
            case "ONE":   return "harmony-shard-0";
            default:      return "ethereum";
        }
    }
}