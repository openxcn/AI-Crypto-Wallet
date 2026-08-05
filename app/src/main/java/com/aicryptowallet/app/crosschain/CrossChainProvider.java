package com.aicryptowallet.app.crosschain;

/**
 * 跨链兑换 Provider 抽象接口
 */
public interface CrossChainProvider {

    /** Provider 名称 */
    String getName();

    /**
     * 是否支持该跨链路径
     * @param fromChain 源链（如 BNB、ETH、TRON）
     * @param toChain   目标链
     * @param fromToken 源代币（NATIVE 或合约地址）
     * @param toToken   目标代币（NATIVE 或合约地址）
     */
    boolean isSupported(String fromChain, String toChain, String fromToken, String toToken);

    /** 获取报价 */
    CrossChainQuote quote(CrossChainRequest request);

    /** 创建交易数据 */
    CrossChainResult swap(CrossChainRequest request, CrossChainQuote quote);

    /** 查询交易状态 */
    CrossChainStatus checkStatus(String requestId, String txHash);
}
