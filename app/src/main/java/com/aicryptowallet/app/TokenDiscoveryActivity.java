package com.aicryptowallet.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/**
 * 代币发现页 - 通过区块浏览器网页（BscScan/Etherscan/Polygonscan 等）
 * 动态发现钱包涉及的所有代币，无需 API key
 *
 * 工作原理：
 * 1. 用 WebView 加载 https://<explorer>/tokentxns?a=<wallet>（绕过 Cloudflare JS challenge）
 * 2. 注入 JS 脚本解析 HTML，提取所有代币合约地址
 * 3. 通过 JavascriptInterface 把代币列表回传给 Java
 * 4. 用 RPC 批量查询每个代币的 balanceOf，过滤出余额 > 0 的
 * 5. 自动添加到钱包代币列表
 */
public class TokenDiscoveryActivity extends Activity {

    private WebView webView;
    private ProgressBar progressBar;
    private TextView tvStatus;
    private ScrollView resultContainer;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private String walletAddress;
    private String chain;
    private String explorerUrl;
    private final Set<String> discoveredTokens = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_token_discovery);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        tvStatus = findViewById(R.id.tvStatus);
        resultContainer = findViewById(R.id.resultContainer);

        walletAddress = WalletManager.getWalletAddress(this);
        chain = WalletManager.getChain(this);

        // 各链区块浏览器 URL
        switch (chain) {
            case "BNB":   explorerUrl = "https://bscscan.com/tokentxns?a=" + walletAddress; break;
            case "ETH":   explorerUrl = "https://etherscan.io/tokentxns?a=" + walletAddress; break;
            case "MATIC": explorerUrl = "https://polygonscan.com/tokentxns?a=" + walletAddress; break;
            case "ARB":   explorerUrl = "https://arbiscan.io/tokentxns?a=" + walletAddress; break;
            case "OP":    explorerUrl = "https://optimistic.etherscan.io/tokentxns?a=" + walletAddress; break;
            case "BASE":  explorerUrl = "https://basescan.org/tokentxns?a=" + walletAddress; break;
            case "AVAX":  explorerUrl = "https://snowtrace.io/tokentxns?a=" + walletAddress; break;
            case "FTM":   explorerUrl = "https://ftmscan.com/tokentxns?a=" + walletAddress; break;
            default:
                Toast.makeText(this, getString(R.string.toast_the_current_chain_does), Toast.LENGTH_SHORT).show();
                finish();
                return;
        }

        tvStatus.setText(getString(R.string.text_loading_token_list_from, ChainAPI.getChainName(chain), explorerUrl));
        setupWebView();
        webView.loadUrl(explorerUrl);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36");
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        webView.addJavascriptInterface(new DiscoveryJsInterface(), "AndroidDiscovery");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                tvStatus.setText(getString(R.string.text_page_loaded_parsing_token));

                // 注入 JS 脚本提取所有代币合约地址
                // BscScan 的代币链接格式：<a href="/token/0xABC...?a=0xWALLET">
                // 提取所有 href 中的合约地址，去重后通过 AndroidDiscovery.onTokensDiscovered 回传
                String js = "(function() {" +
                    "  var links = document.querySelectorAll('a[href*=\"/token/0x\"]');" +
                    "  var tokens = [];" +
                    "  var seen = {};" +
                    "  for (var i = 0; i < links.length; i++) {" +
                    "    var href = links[i].getAttribute('href');" +
                    "    var m = href.match(/\\/token\\/(0x[a-fA-F0-9]{40})/);" +
                    "    if (m && !seen[m[1].toLowerCase()]) {" +
                    "      seen[m[1].toLowerCase()] = true;" +
                    "      var symbol = '';" +
                    "      var name = '';" +
                    "      // 尝试从父元素中提取 symbol/name" +
                    "      var parent = links[i].closest('tr');" +
                    "      if (parent) {" +
                    "        var cells = parent.querySelectorAll('td');" +
                    "        if (cells.length > 0) {" +
                    "          var lastCell = cells[cells.length - 1];" +
                    "          if (lastCell) symbol = lastCell.innerText.trim();" +
                    "        }" +
                    "      }" +
                    "      // 直接从链接文本提取" +
                    "      if (!symbol) symbol = links[i].innerText.trim();" +
                    "      tokens.push({contract: m[1], symbol: symbol, name: name});" +
                    "    }" +
                    "  }" +
                    "  if (tokens.length > 0) {" +
                    "    AndroidDiscovery.onTokensDiscovered(JSON.stringify(tokens));" +
                    "  } else {" +
                    "    // 备用方案：直接解析 HTML" +
                    "    var html = document.documentElement.innerHTML;" +
                    "    var regex = /\\/token\\/(0x[a-fA-F0-9]{40})/g;" +
                    "    var match;" +
                    "    while ((match = regex.exec(html)) !== null) {" +
                    "      if (!seen[match[1].toLowerCase()]) {" +
                    "        seen[match[1].toLowerCase()] = true;" +
                    "        tokens.push({contract: match[1], symbol: '', name: ''});" +
                    "      }" +
                    "    }" +
                    "    AndroidDiscovery.onTokensDiscovered(JSON.stringify(tokens));" +
                    "  }" +
                    "  AndroidDiscovery.onStatus('解析完成，找到 ' + tokens.length + ' 个代币');" +
                    "})();";
                view.evaluateJavascript(js, null);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                tvStatus.setText(getString(R.string.toast_failed_to_load, description));
                progressBar.setVisibility(View.GONE);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
            }
        });
    }

    /**
     * JS 接口 - 接收从网页解析出的代币列表
     */
    private class DiscoveryJsInterface {
        @android.webkit.JavascriptInterface
        public void onTokensDiscovered(String tokensJson) {
            try {
                JSONArray tokens = new JSONArray(tokensJson);
                for (int i = 0; i < tokens.length(); i++) {
                    JSONObject token = tokens.getJSONObject(i);
                    String contract = token.getString("contract").toLowerCase();
                    String symbol = token.optString("symbol", "");
                    String name = token.optString("name", "");
                    if (!discoveredTokens.contains(contract)) {
                        discoveredTokens.add(contract);
                        // 添加到钱包代币列表（自动保存到 SharedPreferences）
                        // 签名: addCustomToken(ctx, chain, symbol, name, contract, decimals)
                        WalletManager.addCustomToken(TokenDiscoveryActivity.this, chain,
                            symbol, name, contract, "18");
                    }
                }
                uiHandler.post(() -> {
                    tvStatus.setText(getString(R.string.text_found_token_checking_balance, discoveredTokens.size()));
                    queryBalances();
                });
            } catch (Exception e) {
                Logger.error(TokenDiscoveryActivity.this, "代币发现", "解析代币列表失败: " + e.getMessage(), e);
                uiHandler.post(() -> tvStatus.setText(getString(R.string.text_parsing_failed, e.getMessage())));
            }
        }

        @android.webkit.JavascriptInterface
        public void onStatus(String status) {
            uiHandler.post(() -> tvStatus.setText(status));
        }
    }

    /**
     * 用 RPC 查询每个发现代币的余额，过滤出余额 > 0 的
     * 同时通过 eth_call 查询代币的 symbol()/name()/decimals() 完善元数据
     */
    private void queryBalances() {
        new Thread(() -> {
            try {
                String rpcUrl = ChainAPI.getRpcUrlStatic(this, chain);
                java.util.List<String> tokensList = new java.util.ArrayList<>(discoveredTokens);
                int found = 0;
                StringBuilder sb = new StringBuilder();

                for (String contract : tokensList) {
                    try {
                        // 先查 metadata [symbol, name, decimals]
                        String[] meta = ChainAPI.getERC20Metadata(rpcUrl, contract);
                        String symbol = (meta != null && meta.length > 0) ? meta[0] : "";
                        String name = (meta != null && meta.length > 1) ? meta[1] : symbol;
                        int decimals = (meta != null && meta.length > 2) ? Integer.parseInt(meta[2]) : 18;
                        double balance = ChainAPI.getERC20Balance(this, chain, walletAddress, contract, decimals);
                        if (balance > 0) {
                            found++;
                            String displaySymbol = symbol.isEmpty() ? contract.substring(0, 8) + "..." : symbol;
                            sb.append("✓ ").append(displaySymbol)
                              .append("  余额: ").append(balance)
                              .append("  合约: ").append(contract.substring(0, 10)).append("...\n");
                            // 用准确的元数据覆盖之前 symbol 为空时保存的版本
                            if (!symbol.isEmpty()) {
                                WalletManager.removeCustomToken(this, chain, contract);
                                WalletManager.addCustomToken(this, chain, symbol, name, contract, String.valueOf(decimals));
                            }
                        } else {
                            // 余额为 0 的代币从列表中移除，避免污染
                            WalletManager.removeCustomToken(this, chain, contract);
                        }
                    } catch (Exception e) {
                        Logger.error(this, "代币发现", "查询 " + contract + " 余额失败: " + e.getMessage(), e);
                    }
                }

                final int finalFound = found;
                final String detail = sb.toString();
                uiHandler.post(() -> {
                    String detailText = detail.isEmpty() ? getString(R.string.text_no_tokens_with_balance) : detail;
                    tvStatus.setText(getString(R.string.msg_token_discovery_result,
                        String.valueOf(discoveredTokens.size()),
                        String.valueOf(finalFound),
                        detailText));
                    resultContainer.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                uiHandler.post(() -> tvStatus.setText(getString(R.string.text_nbalance, e.getMessage())));
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
        }
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_OK);
        super.onBackPressed();
    }
}