package com.aicryptowallet.app.crosschain;

import android.content.Context;
import com.aicryptowallet.app.ChainAPI;
import com.aicryptowallet.app.WalletManager;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 跨链工具：链名映射、地址获取、金额转换
 */
public class CrossChainUtils {

    /** 是否是 EVM 链 */
    public static boolean isEVM(String chain) {
        return ChainAPI.isEVM(chain);
    }

    /** 获取当前选中钱包在某链的地址 */
    public static String getWalletAddress(Context ctx, String chain) {
        String mnemonic = WalletManager.getMnemonic(ctx);
        if (mnemonic == null || mnemonic.isEmpty()) return "";
        return WalletManager.deriveAddress(mnemonic, chain);
    }

    /** 把 USD 金额按价格换算成代币最小单位数量 */
    public static String amountToSmallestUnit(double amount, int decimals) {
        BigDecimal bd = BigDecimal.valueOf(amount);
        bd = bd.multiply(BigDecimal.TEN.pow(decimals));
        return bd.toBigInteger().toString();
    }

    /** 把最小单位数量转换为可读金额 */
    public static double smallestUnitToAmount(String amount, int decimals) {
        if (amount == null || amount.isEmpty()) return 0;
        BigDecimal bd = new BigDecimal(amount);
        bd = bd.divide(BigDecimal.TEN.pow(decimals));
        return bd.doubleValue();
    }

    /** 补齐常见代币 decimals */
    public static int defaultDecimals(String tokenSymbol) {
        if (tokenSymbol == null) return 18;
        switch (tokenSymbol.toUpperCase()) {
            case "USDT": case "USDC": case "BUSD": case "DAI": return 18;
            case "USDC.E": return 6;
            case "WBTC": return 8;
            case "TRX": return 6;
            default: return 18;
        }
    }

    /** 根据链和代币获取 decimals */
    public static int getTokenDecimals(Context ctx, String chain, String token) {
        if (token == null || token.isEmpty() || "NATIVE".equalsIgnoreCase(token)
            || "0x0000000000000000000000000000000000000000".equalsIgnoreCase(token)) {
            if ("TRX".equalsIgnoreCase(chain)) return 6;
            return 18;
        }
        // 如果是合约地址，优先查链上
        if (token.startsWith("0x") && token.length() == 42) {
            try {
                String rpcUrl = WalletManager.getRpcUrl(ctx, chain);
                return ChainAPI.getTokenDecimals(rpcUrl, token);
            } catch (Exception e) {
                // fallback
            }
        }
        return defaultDecimals(token);
    }
}
