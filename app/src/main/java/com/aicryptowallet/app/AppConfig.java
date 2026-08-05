package com.aicryptowallet.app;

/**
 * 应用配置常量
 */
public class AppConfig {
    
    // 收益钱包地址（接收 0.5% 交易费）
    public static final String DEVELOPER_WALLET = "0x2A09f6F41507e6Ef34c4CC0c6749d4e15190FC7E";

    // 红魔 NFT 合约地址（币安链）
    public static final String RED_DEVIL_NFT_CONTRACT = "0x7ab1066638666c7f5fef3b97bbe2722fdc1f1f64";

    // SMART 代币合约地址
    public static final String SMART_TOKEN_CONTRACT = "0x92cB10E1D503b5c41f54fCC6B576176E6f29FBAD";

    // SMART 代币免手续费持有量
    public static final int SMART_FREE_THRESHOLD = 10000;

    // 交易手续费比例 0.5%
    public static final double TRADE_FEE_RATE = 0.005;

    // 非 EVM 链收益钱包占位地址（后续由用户补全）
    public static final String REVENUE_WALLET_SOL  = "";
    public static final String REVENUE_WALLET_TRX  = "";
    public static final String REVENUE_WALLET_SUI  = "";
    public static final String REVENUE_WALLET_APT  = "";
    public static final String REVENUE_WALLET_ADA  = "";
    public static final String REVENUE_WALLET_NEAR = "";
    public static final String REVENUE_WALLET_ATOM = "";
    public static final String REVENUE_WALLET_DOT  = "";
    public static final String REVENUE_WALLET_BTC  = "";

    // AI 自动交易最低余额要求（主流币资产 USD）
    public static final double MIN_BALANCE_FOR_AI = 200.0;

    // AI 自动交易放宽条件：持有 R-MAB 数量阈值（满足任一条件即可开启 AI）
    public static final double RMAB_THRESHOLD_FOR_AI = 20000.0;
    
    /**
     * 获取开发者钱包地址
     */
    public static String getDeveloperWallet() {
        return DEVELOPER_WALLET;
    }

    /**
     * 获取指定链的收益钱包地址
     * EVM 链共用同一个 0x 地址，非 EVM 链各自独立
     */
    public static String getRevenueWallet(String chain) {
        if (chain == null) return DEVELOPER_WALLET;
        switch (chain.toUpperCase()) {
            case "SOL":  return REVENUE_WALLET_SOL;
            case "TRX":  return REVENUE_WALLET_TRX;
            case "SUI":  return REVENUE_WALLET_SUI;
            case "APT":  return REVENUE_WALLET_APT;
            case "ADA":  return REVENUE_WALLET_ADA;
            case "NEAR": return REVENUE_WALLET_NEAR;
            case "ATOM": return REVENUE_WALLET_ATOM;
            case "DOT":  return REVENUE_WALLET_DOT;
            case "BTC":  return REVENUE_WALLET_BTC;
            default:     return DEVELOPER_WALLET;
        }
    }

    /**
     * 获取 SMART 代币合约地址
     */
    public static String getSmartTokenContract() {
        return SMART_TOKEN_CONTRACT;
    }
}
