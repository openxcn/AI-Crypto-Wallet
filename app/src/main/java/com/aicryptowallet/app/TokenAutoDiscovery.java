package com.aicryptowallet.app;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 用 WebView 加载区块浏览器网页，绕过 Cloudflare，自动发现钱包的所有代币。
 *
 * 工作原理：
 * 1. WebView 加载 https://<explorer>/tokentxns?a=<wallet>
 * 2. onPageFinished 注入 JS，用 querySelectorAll('a[href*="/token/0x"]') 提取代币合约地址
 * 3. 通过 JavascriptInterface 回传 Java
 * 4. 调用 WalletManager.addCustomToken 保存到 SharedPreferences
 *
 * 专为 BSC 等对 eth_getLogs 封锁的链设计 —— Cloudflare 会拦截 OkHttp 请求，
 * 但 WebView 有真实浏览器引擎能执行 JS Challenge，是唯一能免费抓取 BscScan 的方案。
 *
 * 用法：
 *   TokenAutoDiscovery.discover(activity, chain, walletAddress, () -> {
 *       // 扫描完成回调（在主线程）
 *   });
 */
public class TokenAutoDiscovery {

    /** 单次扫描超时（秒）—— Cloudflare 在中国无法通过，快速放弃避免卡死 */
    private static final long TIMEOUT_SECONDS = 15;

    /** 防止并发扫描 */
    private static volatile boolean isRunning = false;

    /**
     * 异步扫描区块浏览器，发现钱包所有代币并保存到 SharedPreferences。
     * 扫描完成后回调（主线程）。如果正在扫描中，直接回调不重复扫描。
     *
     * @param activity  Activity 上下文（WebView 必须在主线程创建）
     * @param chain     链代码（BNB/ETH/MATIC/ARB/OP/BASE/AVAX/FTM）
     * @param wallet    钱包地址
     * @param callback  扫描完成回调（主线程，参数为发现的代币数量；-1 表示失败或跳过）
     */
    public static void discover(Activity activity, String chain, String wallet, DiscoveryCallback callback) {
        if (isRunning) {
            Logger.info(activity, "自动发现", "已有扫描任务在运行，跳过");
            new Handler(Looper.getMainLooper()).post(() -> callback.onComplete(-1));
            return;
        }
        isRunning = true;

        final String explorerUrl = getExplorerUrl(chain, wallet);
        if (explorerUrl == null) {
            Logger.info(activity, "自动发现", "当前链不支持网页扫描: " + chain);
            isRunning = false;
            new Handler(Looper.getMainLooper()).post(() -> callback.onComplete(-1));
            return;
        }

        final Handler uiHandler = new Handler(Looper.getMainLooper());
        final int[] foundCount = {0};
        final CountDownLatch latch = new CountDownLatch(1);

        uiHandler.post(() -> {
            try {
                WebView webView = new WebView(activity);
                // 关键：WebView 必须 attach 到 View 树才会加载页面
                // 用 0x0 大小 + INVISIBLE 添加到 Activity 的 ContentView
                android.view.ViewGroup root = (android.view.ViewGroup) activity.findViewById(android.R.id.content);
                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(0, 0);
                webView.setVisibility(View.INVISIBLE);
                webView.setLayoutParams(lp);
                root.addView(webView);

                webView.getSettings().setJavaScriptEnabled(true);
                webView.getSettings().setDomStorageEnabled(true);
                webView.getSettings().setUserAgentString(
                    "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
                webView.setWebChromeClient(new WebChromeClient());
                webView.setWebViewClient(new WebViewClient());

                final Set<String> discovered = new HashSet<>();
                final Context ctx = activity.getApplicationContext();

                webView.addJavascriptInterface(new Object() {
                    @JavascriptInterface
                    public void onTokensDiscovered(String tokensJson) {
                        try {
                            JSONArray tokens = new JSONArray(tokensJson);
                            Logger.info(ctx, "自动发现", "JS 回传 " + tokens.length() + " 个代币合约");
                            for (int i = 0; i < tokens.length(); i++) {
                                JSONObject t = tokens.getJSONObject(i);
                                String contract = t.getString("contract").toLowerCase();
                                if (discovered.contains(contract)) continue;
                                discovered.add(contract);
                                String symbol = t.optString("symbol", "");
                                String name = t.optString("name", "");
                                // 优先从合约本身查询 decimals，避免默认 18 导致金额错误
                                int decimals = 18;
                                try {
                                    String rpcUrl = WalletManager.getRpcUrl(ctx, chain);
                                    if (rpcUrl != null && !rpcUrl.isEmpty()) {
                                        decimals = ChainAPI.getTokenDecimals(rpcUrl, contract);
                                    }
                                } catch (Exception e) {
                                    Logger.warning(ctx, "自动发现", "合约 " + contract + " decimals 查询失败，使用默认值 18");
                                }
                                WalletManager.addCustomToken(ctx, chain,
                                    symbol.isEmpty() ? "UNKNOWN" : symbol,
                                    name.isEmpty() ? symbol : name,
                                    contract, String.valueOf(decimals));
                            }
                            foundCount[0] = discovered.size();
                        } catch (Exception e) {
                            Logger.error(ctx, "自动发现", "解析 JS 回传失败: " + e.getMessage(), e);
                        } finally {
                            latch.countDown();
                        }
                    }
                }, "AndroidDiscovery");

                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        Logger.info(ctx, "自动发现", "页面加载完成: " + url);
                        // Cloudflare Challenge 页也会触发 onPageFinished
                        // 需要多次重试，等 Challenge 完成后真实页面加载
                        tryExtract(uiHandler, view, ctx, discovered, foundCount, latch, 0);
                    }

                    @Override
                    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                        Logger.warning(ctx, "自动发现", "WebView 加载错误 code=" + errorCode + " " + description);
                        latch.countDown();
                    }
                });

                Logger.info(ctx, "自动发现", "开始加载区块浏览器: " + explorerUrl);
                webView.loadUrl(explorerUrl);
            } catch (Exception e) {
                Logger.error(activity, "自动发现", "WebView 创建失败: " + e.getMessage(), e);
                latch.countDown();
            }
        });

        // 后台线程等待扫描完成
        new Thread(() -> {
            try {
                if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    Logger.warning(activity, "自动发现", "扫描超时 " + TIMEOUT_SECONDS + "s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                isRunning = false;
                final int count = foundCount[0];
                uiHandler.post(() -> {
                    // 清理 WebView 避免内存泄漏
                    try {
                        android.view.ViewGroup root = (android.view.ViewGroup) activity.findViewById(android.R.id.content);
                        for (int i = root.getChildCount() - 1; i >= 0; i--) {
                            if (root.getChildAt(i) instanceof WebView) {
                                WebView wv = (WebView) root.getChildAt(i);
                                wv.stopLoading();
                                wv.removeAllViews();
                                wv.destroy();
                                root.removeViewAt(i);
                                Logger.info(activity, "自动发现", "WebView 已销毁并从 View 树移除");
                            }
                        }
                    } catch (Exception e) {
                        Logger.warning(activity, "自动发现", "清理 WebView 失败: " + e.getMessage());
                    }
                    callback.onComplete(count);
                });
            }
        }).start();
    }

    /**
     * 注入代币提取 JS，带 Cloudflare Challenge 检测和重试。
     * 第 0 次等 2s，第 1 次 2s 重试。最多 2 次（约 4s）。
     * Cloudflare JS Challenge 在中国大陆几乎无法通过，快速放弃避免卡死。
     */
    private static void tryExtract(Handler uiHandler, WebView view, Context ctx,
                                    Set<String> discovered, int[] foundCount,
                                    CountDownLatch latch, int attempt) {
        if (attempt > 1) {
            Logger.warning(ctx, "自动发现", "重试 " + attempt + " 次仍未提取到代币，快速放弃（Cloudflare在中国无法通过）");
            latch.countDown();
            return;
        }

        long delay = 2000; // 每次 2s，给 Cloudflare Challenge 一点时间，但不久等
        uiHandler.postDelayed(() -> {
            if (latch.getCount() == 0) return; // 已完成，不再重试
            // 先检查页面标题，确认不是 Cloudflare Challenge 页
            view.evaluateJavascript(
                "(function(){ return document.title || ''; })()",
                title -> {
                    if (latch.getCount() == 0) return;
                    String t = title == null ? "" : title.replace("\"", "");
                    Logger.info(ctx, "自动发现", "第 " + attempt + " 次尝试，页面标题: " + t);
                    // Cloudflare Challenge 页标题：英文 "Just a moment..."，中文 "请稍候…"，或为空
                    if (t.contains("Just a moment") || t.contains("请稍候") || t.contains("请稍候…") || t.isEmpty()) {
                        Logger.info(ctx, "自动发现", "检测到 Cloudflare Challenge 页，等待重试");
                        tryExtract(uiHandler, view, ctx, discovered, foundCount, latch, attempt + 1);
                        return;
                    }
                    // 真实页面已加载，注入提取 JS
                    injectExtractJs(uiHandler, view, ctx, discovered, foundCount, latch, attempt);
                }
            );
        }, delay);
    }

    /** 注入代币提取 JS */
    private static void injectExtractJs(Handler uiHandler, WebView view, Context ctx,
                                         Set<String> discovered, int[] foundCount,
                                         CountDownLatch latch, int attempt) {
        String js = "(function(){"
            + "  var links = document.querySelectorAll('a[href*=\"/token/0x\"]');"
            + "  var tokens = [];"
            + "  var seen = {};"
            + "  for (var i = 0; i < links.length; i++) {"
            + "    var href = links[i].getAttribute('href');"
            + "    var m = href.match(/\\/token\\/(0x[a-fA-F0-9]{40})/);"
            + "    if (m && !seen[m[1].toLowerCase()]) {"
            + "      seen[m[1].toLowerCase()] = true;"
            + "      var symbol = '';"
            + "      try {"
            + "        var img = links[i].closest('tr') && links[i].closest('tr').querySelector('img');"
            + "        if (img) {"
            + "          var alt = img.getAttribute('alt') || '';"
            + "          var sm = alt.match(/\\(([^)]+)\\)/);"
            + "          if (sm) symbol = sm[1];"
            + "        }"
            + "      } catch(e){}"
            + "      tokens.push({contract: m[1], symbol: symbol, name: symbol});"
            + "    }"
            + "  }"
            + "  if (tokens.length === 0) {"
            + "    var all = document.querySelectorAll('a');"
            + "    for (var j = 0; j < all.length; j++) {"
            + "      var h = all[j].getAttribute('href') || '';"
            + "      var mm = h.match(/\\/token\\/(0x[a-fA-F0-9]{40})/);"
            + "      if (mm && !seen[mm[1].toLowerCase()]) {"
            + "        seen[mm[1].toLowerCase()] = true;"
            + "        tokens.push({contract: mm[1], symbol: '', name: ''});"
            + "      }"
            + "    }"
            + "  }"
            + "  if (tokens.length > 0) {"
            + "    try { AndroidDiscovery.onTokensDiscovered(JSON.stringify(tokens)); } catch(e) {}"
            + "  }"
            + "  return tokens.length;"
            + "})()";
        view.evaluateJavascript(js, result -> {
            Logger.info(ctx, "自动发现", "JS 注入完成，第 " + attempt + " 次，提取结果: " + result);
            // 如果 result 是 0（页面还没加载完或确实是空钱包），触发重试
            // 如果 result > 0 但 JS Interface 没回调，2 秒后用返回值兜底
            uiHandler.postDelayed(() -> {
                if (latch.getCount() == 0) return;
                int count = parseCount(result);
                if (count > 0) {
                    // JS Interface 回调失败但提取到了代币，用返回值兜底
                    Logger.warning(ctx, "自动发现", "JS Interface 回调超时，但提取到 " + count + " 个代币（合约地址丢失）");
                    latch.countDown();
                } else {
                    // 提取到 0 个，快速放弃（Cloudflare 在中国无法通过）
                    Logger.info(ctx, "自动发现", "第 " + attempt + " 次提取到 0 个代币，快速放弃");
                    latch.countDown();
                }
            }, 2000);
        });
    }

    private static int parseCount(String result) {
        try {
            if (result == null || "null".equals(result)) return 0;
            String cleaned = result.replace("\"", "");
            return Integer.parseInt(cleaned);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 各链区块浏览器 URL（tokentxns 页面列出钱包的所有代币转账记录） */
    private static String getExplorerUrl(String chain, String wallet) {
        switch (chain) {
            case "BNB":   return "https://bscscan.com/tokentxns?a=" + wallet;
            case "ETH":   return "https://etherscan.io/tokentxns?a=" + wallet;
            case "MATIC": return "https://polygonscan.com/tokentxns?a=" + wallet;
            case "ARB":   return "https://arbiscan.io/tokentxns?a=" + wallet;
            case "OP":    return "https://optimistic.etherscan.io/tokentxns?a=" + wallet;
            case "BASE":  return "https://basescan.org/tokentxns?a=" + wallet;
            case "AVAX":  return "https://snowtrace.io/tokentxns?a=" + wallet;
            case "FTM":   return "https://ftmscan.com/tokentxns?a=" + wallet;
            default:      return null;
        }
    }

    /** 扫描完成回调 */
    public interface DiscoveryCallback {
        /**
         * @param foundCount 发现的代币合约数量；-1 表示跳过（正在扫描或不支持的链）
         */
        void onComplete(int foundCount);
    }
}
