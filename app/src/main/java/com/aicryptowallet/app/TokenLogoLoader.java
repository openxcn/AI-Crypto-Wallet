package com.aicryptowallet.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 代币 LOGO 加载器 — 多层顺序重试策略
 *
 * 数据源优先级（从快到慢，从本地到网络）：
 * 0. 内置 assets/token_logos/（定制LOGO如R-MAB，零延迟）
 * 1. 本地缓存 internal storage（之前网络加载成功的缓存）
 * 2. 开源仓库：SmolDapp/tokenAssets（按链ID分类，覆盖多链）
 * 3. 开源仓库：spothq/cryptocurrency-icons（主流币种）
 * 4. 区块浏览器：BscScan / Etherscan 等
 * 5. DEX CDN：PancakeSwap / Uniswap 等
 * 6. 其他 CDN：1inch / Trust Wallet
 *
 * 全部失败时：使用 TokenLogoGenerator 运行时生成彩色圆形+首字母 LOGO
 */
public class TokenLogoLoader {

    private static final String CACHE_DIR = "token_logo_cache";
    private static final int TAG_LOGO_CONTRACT = 0x7F000001;

    /**
     * 加载代币 LOGO
     * @param placeholderView 可选，加载成功后隐藏此占位 View（如 tvTokenSymbol 字母占位）
     */
    public static void load(android.content.Context ctx, ImageView imageView, String symbol, String contract, View placeholderView) {
        if (imageView == null) return;

        // 清除旧 Glide 请求，防止 RecyclerView 复用时旧请求覆盖新 LOGO
        Glide.with(ctx).clear(imageView);

        // 用合约地址做 tag，用于异步回调校验
        String tag = (contract == null || contract.isEmpty()) ? ("native_" + (symbol != null ? symbol.toUpperCase() : "")) : contract.toLowerCase();
        imageView.setTag(TAG_LOGO_CONTRACT, tag);

        // 原生币（无合约地址）：用 native 路径
        if (contract == null || contract.isEmpty()) {
            loadNativeToken(ctx, imageView, symbol, placeholderView);
            return;
        }

        // EVM 代币：按合约地址查
        loadErc20Token(ctx, imageView, contract, symbol, placeholderView);
    }

    /** 重载兼容旧调用（无 placeholderView） */
    public static void load(android.content.Context ctx, ImageView imageView, String symbol, String contract) {
        load(ctx, imageView, symbol, contract, null);
    }

    /**
     * 加载原生币 LOGO
     */
    private static void loadNativeToken(android.content.Context ctx, ImageView imageView, String symbol, View placeholderView) {
        String sym = symbol != null ? symbol.toUpperCase() : "";
        String tag = "native_" + sym;

        if (tryLoadCache(ctx, imageView, tag, symbol, placeholderView)) {
            Logger.info(null, "Logo加载", "缓存 LOGO: " + symbol);
            return;
        }

        // 立即显示生成的 LOGO，后台尝试网络源升级
        showGeneratedLogo(imageView, symbol, tag, placeholderView);
        Logger.info(null, "Logo加载", "预显示生成 LOGO: " + symbol + "，后台尝试网络源");

        String s = sym.toLowerCase();
        String[] urls = {
            // 1) CoinCap 按 symbol（主流币种与代币覆盖较好）
            "https://assets.coincap.io/assets/icons/" + s + "@2x.png",
            // 2) 开源图标库
            "https://raw.githubusercontent.com/spothq/cryptocurrency-icons/master/128/color/" + s + ".png",
            "https://raw.githubusercontent.com/spothq/cryptocurrency-icons/master/32/color/" + s + ".png",
            // 3) TrustWallet 该币原生链 info/logo（仅用于有独立主链的币种）
            getTrustWalletNativeLogoUrl(sym)
        };
        loadWithRetryBg(ctx, imageView, urls, 0, symbol, tag, placeholderView);
    }

    /**
     * 返回指定原生币在 TrustWallet 中的 info/logo 路径。
     * 避免把 BTC/ETH 等 fallback 到币安链图标。
     */
    private static String getTrustWalletNativeLogoUrl(String symbol) {
        String s = symbol.toLowerCase();
        String chain;
        switch (s) {
            case "btc":  chain = "bitcoin"; break;
            case "eth":  chain = "ethereum"; break;
            case "bnb":  chain = "binance"; break;
            case "sol":  chain = "solana"; break;
            case "trx":  chain = "tron"; break;
            case "xrp":  chain = "ripple"; break;
            case "ada":  chain = "cardano"; break;
            case "dot":  chain = "polkadot"; break;
            case "atom": chain = "cosmos"; break;
            case "avax": chain = "avalanchec"; break;
            case "matic":case "pol": chain = "polygon"; break;
            case "ftm":  chain = "fantom"; break;
            case "arb":  chain = "arbitrum"; break;
            case "op":   chain = "optimism"; break;
            case "base": chain = "base"; break;
            case "near": chain = "near"; break;
            case "algo": chain = "algorand"; break;
            case "doge": chain = "doge"; break;
            case "ltc":  chain = "litecoin"; break;
            case "bch":  chain = "bitcoincash"; break;
            case "etc":  chain = "classic"; break;
            case "xlm":  chain = "stellar"; break;
            case "xtz":  chain = "tezos"; break;
            case "eos":  chain = "eos"; break;
            case "vet":  chain = "vechain"; break;
            case "zil":  chain = "zilliqa"; break;
            case "one":  chain = "harmony"; break;
            case "sui":  chain = "sui"; break;
            case "sei":  chain = "sei"; break;
            case "tia":  chain = "celestia"; break;
            case "core": chain = "core"; break;
            case "hbar": chain = "hedera"; break;
            case "icp":  chain = "internet_computer"; break;
            case "stx":  chain = "stacks"; break;
            case "flow": chain = "flow"; break;
            case "imx":  chain = "immutablex"; break;
            case "kava": chain = "kava"; break;
            case "osmo": chain = "osmosis"; break;
            case "scrt": chain = "secret"; break;
            case "mina": chain = "mina"; break;
            case "celo": chain = "celo"; break;
            case "rose": chain = "oasis"; break;
            case "iotx": chain = "iotex"; break;
            case "dgb":  chain = "digibyte"; break;
            case "qtum": chain = "qtum"; break;
            case "btt":  chain = "bttc"; break;
            case "waves":chain = "waves"; break;
            case "neo":  chain = "neo"; break;
            case "theta":chain = "theta"; break;
            default:     chain = "ethereum"; break;
        }
        return "https://raw.githubusercontent.com/trustwallet/assets@master/blockchains/" + chain + "/info/logo.png";
    }

    /**
     * 加载代币 LOGO（ERC-20/BEP-20 通用）
     */
    private static void loadErc20Token(android.content.Context ctx, ImageView imageView, String contract, String symbol, View placeholderView) {
        String c = contract.toLowerCase();

        // 第0层：内置 assets/token_logos/（如R-MAB定制LOGO，直接返回，不走网络）
        if (tryLoadLocalAsset(ctx, imageView, c, symbol, placeholderView)) {
            Logger.info(null, "Logo加载", "内置 LOGO: " + symbol);
            return;
        }

        // 第1层：本地缓存
        if (tryLoadCache(ctx, imageView, c, symbol, placeholderView)) {
            Logger.info(null, "Logo加载", "缓存 LOGO: " + symbol);
            return;
        }

        // 立即显示生成的 LOGO（兜底），后台尝试网络源升级
        showGeneratedLogo(imageView, symbol, c, placeholderView);
        Logger.info(null, "Logo加载", "预显示生成 LOGO: " + symbol + "，后台尝试网络源");

        String[] urls = {
            // 1) TrustWallet 多链资产库（最权威，按合约地址）
            "https://raw.githubusercontent.com/trustwallet/assets@master/blockchains/ethereum/assets/" + c + "/logo.png",
            "https://raw.githubusercontent.com/trustwallet/assets@master/blockchains/smartchain/assets/" + c + "/logo.png",
            "https://raw.githubusercontent.com/trustwallet/assets@master/blockchains/polygon/assets/" + c + "/logo.png",
            "https://raw.githubusercontent.com/trustwallet/assets@master/blockchains/arbitrum/assets/" + c + "/logo.png",
            "https://raw.githubusercontent.com/trustwallet/assets@master/blockchains/avalanchec/assets/" + c + "/logo.png",
            "https://raw.githubusercontent.com/trustwallet/assets@master/blockchains/optimism/assets/" + c + "/logo.png",
            "https://raw.githubusercontent.com/trustwallet/assets@master/blockchains/base/assets/" + c + "/logo.png",
            "https://raw.githubusercontent.com/trustwallet/assets@master/blockchains/fantom/assets/" + c + "/logo.png",
            // 2) DEX tokenlist
            "https://tokens.pancakeswap.finance/images/" + c + ".png",
            "https://tokens.uniswap.org/images/" + c + ".png",
            // 3) 开源图标库（按 symbol）
            "https://raw.githubusercontent.com/spothq/cryptocurrency-icons/master/128/color/" + symbol.toLowerCase() + ".png"
        };
        loadWithRetryBg(ctx, imageView, urls, 0, symbol, c, placeholderView);
    }

    /** 尝试从本地内置 assets/token_logos/ 加载 */
    private static boolean tryLoadLocalAsset(android.content.Context ctx, ImageView imageView, String contract, String symbol, View placeholderView) {
        try {
            String assetPath = "token_logos/" + contract + ".png";
            InputStream is = ctx.getAssets().open(assetPath);
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            is.close();
            if (bitmap != null) {
                hidePlaceholder(placeholderView);
                imageView.setImageBitmap(bitmap);
                imageView.setVisibility(View.VISIBLE);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** 尝试从本地缓存加载 */
    private static boolean tryLoadCache(android.content.Context ctx, ImageView imageView, String contract, String symbol, View placeholderView) {
        try {
            File cacheDir = new File(ctx.getCacheDir(), CACHE_DIR);
            File cacheFile = new File(cacheDir, contract + ".png");
            if (cacheFile.exists()) {
                Bitmap bitmap = BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
                if (bitmap != null) {
                    if (isChainLogo(bitmap, symbol)) {
                        Logger.warning(null, "Logo缓存", "缓存中为链图标，清理并重新加载: " + contract);
                        cacheFile.delete();
                        return false;
                    }
                    hidePlaceholder(placeholderView);
                    imageView.setImageBitmap(bitmap);
                    imageView.setVisibility(View.VISIBLE);
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** 保存 LOGO 到本地缓存 */
    private static void saveToCache(android.content.Context ctx, String contract, String symbol, Bitmap bitmap) {
        try {
            if (isChainLogo(bitmap, symbol)) {
                Logger.warning(null, "Logo缓存", "检测到链图标，丢弃不缓存: " + contract);
                return;
            }
            File cacheDir = new File(ctx.getCacheDir(), CACHE_DIR);
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File cacheFile = new File(cacheDir, contract + ".png");
            FileOutputStream fos = new FileOutputStream(cacheFile);
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos);
            fos.flush();
            fos.close();
        } catch (Exception e) {
            Logger.warning(null, "Logo缓存", "缓存失败: " + e.getMessage());
        }
    }

    /**
     * 简单启发式判断：下载的是否为链图标而非币种图标。
     * 币安链 LOGO 主体为黄色 #F3BA2F，和绝大多数币种颜色差异明显。
     * 注意：BNB/BSC 本身允许使用币安黄，不做拦截。
     */
    private static boolean isChainLogo(Bitmap bitmap, String symbol) {
        if (bitmap == null) return true;
        String sym = symbol != null ? symbol.toUpperCase() : "";
        // BNB 本身就用币安 LOGO，不应拦截
        if ("BNB".equals(sym) || "BSC".equals(sym)) return false;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w < 8 || h < 8) return true;
        int yellowPixels = 0;
        int solidPixels = 0;
        int step = Math.max(1, w / 16);
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int px = bitmap.getPixel(x, y);
                int a = (px >> 24) & 0xFF;
                if (a < 40) continue; // 跳过透明像素
                solidPixels++;
                int r = (px >> 16) & 0xFF;
                int g = (px >> 8) & 0xFF;
                int b = px & 0xFF;
                // 检测币安黄 #F3BA2F：R 高、G 中高、B 低
                if (r > 210 && g > 150 && b < 100 && r > g + 20 && g > b + 50) yellowPixels++;
            }
        }
        if (solidPixels == 0) return true;
        float ratio = (float) yellowPixels / solidPixels;
        return ratio > 0.30f;
    }

    /** 后台顺序重试加载（LOGO已提前显示，此处仅尝试升级为真实LOGO，不覆盖已有图片） */
    private static void loadWithRetryBg(android.content.Context ctx, ImageView imageView, String[] urls, int index, String symbol, String contract, View placeholderView) {
        // 优化：最多尝试3个源，避免11个源全部失败导致主线程回调风暴
        final int MAX_ATTEMPTS = 3;
        if (urls == null || index >= urls.length || index >= MAX_ATTEMPTS) {
            if (index >= MAX_ATTEMPTS) {
                Logger.info(null, "Logo加载", "已尝试 " + MAX_ATTEMPTS + " 个源均失败，保留生成 LOGO（快速放弃）");
            } else {
                Logger.warning(null, "Logo加载", "所有 " + (urls != null ? Math.min(urls.length, MAX_ATTEMPTS) : 0) + " 个网络源均失败，保留生成 LOGO");
            }
            return;
        }

        final int currentIdx = index;
        Logger.info(null, "Logo加载", "后台尝试源[" + (index + 1) + "/" + Math.min(urls.length, MAX_ATTEMPTS) + "]: " + urls[index].substring(0, Math.min(60, urls[index].length())) + "...");

        // 使用 CustomTarget 后台下载，不触碰 ImageView（避免清空已显示的生成 LOGO）
        Glide.with(imageView.getContext())
            .asBitmap()
            .load(urls[index])
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .centerCrop()
            .into(new CustomTarget<Bitmap>() {
                @Override
                public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                    if (!contract.equals(imageView.getTag(TAG_LOGO_CONTRACT))) return;
                    if (isChainLogo(resource, symbol)) {
                        Logger.warning(null, "Logo加载", "源[" + (currentIdx + 1) + "] 为链图标，跳过并尝试下一源");
                        // 优化：直接在当前线程重试，避免主线程回调
                        loadWithRetryBg(ctx, imageView, urls, currentIdx + 1, symbol, contract, placeholderView);
                        return;
                    }
                    Logger.info(null, "Logo加载", "源[" + (currentIdx + 1) + "] 加载成功，升级 LOGO!");
                    hidePlaceholder(placeholderView);
                    // 优化：UI 更新必须在主线程
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (!contract.equals(imageView.getTag(TAG_LOGO_CONTRACT))) return;
                        imageView.setImageBitmap(resource);
                        imageView.setVisibility(View.VISIBLE);
                    });
                    try {
                        saveToCache(ctx, contract, symbol, resource);
                    } catch (Exception ignored) {}
                }

                @Override
                public void onLoadFailed(@Nullable Drawable errorDrawable) {
                    Logger.warning(null, "Logo加载", "源[" + (currentIdx + 1) + "] 失败");
                    // 优化：直接在当前线程重试，避免主线程回调风暴
                    loadWithRetryBg(ctx, imageView, urls, currentIdx + 1, symbol, contract, placeholderView);
                }

                @Override
                public void onLoadCleared(@Nullable Drawable placeholder) {}
            });
    }

    /** 显示运行时生成的彩色圆形 LOGO（最终兜底方案） */
    private static void showGeneratedLogo(ImageView imageView, String symbol, String contract, View placeholderView) {
        if (imageView == null) return;
        try {
            hidePlaceholder(placeholderView);
            Bitmap logo = TokenLogoGenerator.generate(symbol, contract);
            imageView.setImageBitmap(logo);
            imageView.setVisibility(View.VISIBLE);
            Logger.info(null, "Logo加载", "生成 LOGO: " + symbol);
        } catch (Exception e) {
            Logger.error(null, "Logo加载", "生成 LOGO 失败: " + e.getMessage(), e);
            imageView.setVisibility(View.VISIBLE);
        }
    }

    /** 隐藏占位 View（如 tvTokenSymbol 字母占位） */
    private static void hidePlaceholder(View placeholderView) {
        if (placeholderView != null) {
            placeholderView.setVisibility(View.GONE);
        }
    }
}