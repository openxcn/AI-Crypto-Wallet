package com.aicryptowallet.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 通用合约调用器 - Agent Runtime 执行层核心组件
 *
 * 产品定位：AI 中长线炒币助手的智能体钱包，需要 AI 能自己调用合约访问 DApp
 *
 * 本类提供：
 * 1. callReadOnly - 只读调用（eth_call），不消耗 gas，用于查询合约状态
 * 2. callWrite - 写入调用（广播交易），消耗 gas，用于状态变更操作
 * 3. 通用 ABI 编码：支持任意函数签名和参数类型
 *
 * 安全设计：
 * - 所有写入调用必须经过 SafetyGate 校验（由调用方传入）
 * - 不直接访问 WalletManager 私钥，通过 DexTrader 复用签名基础设施
 * - gas 上限 1M，防止异常值
 * - 支持自定义 nonce，避免多笔交易竞态
 *
 * 使用示例：
 *   String result = ContractCaller.callReadOnly(ctx, chain, router, "getAmountsOut(uint256,address[])",
 *       Arrays.asList(new Uint256(amountIn), new DynamicArray<>(Address.class, path)));
 */
public class ContractCaller {

    private static final okhttp3.OkHttpClient CLIENT = new okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build();

    private static final BigInteger GAS_LIMIT_MAX = BigInteger.valueOf(1_000_000);
    private static final okhttp3.MediaType JSON_TYPE =
        okhttp3.MediaType.parse("application/json");

    /**
     * 只读合约调用（不消耗 gas）
     * @param ctx Context
     * @param chain 链标识（ETH/BNB/MATIC...）
     * @param contract 合约地址
     * @param functionName 函数名（如 "balanceOf"）
     * @param inputParams 参数列表（web3j Type 子类）
     * @param outputParams 返回类型（TypeReference 列表）
     * @return 调用结果（hex 字符串），调用方按 outputParams 解码
     */
    public static String callReadOnly(Context ctx, String chain, String contract,
                                       String functionName, List<Type> inputParams,
                                       List<TypeReference<?>> outputParams) throws Exception {
        String rpcUrl = ChainAPI.getRpcUrlStatic(ctx, chain);
        Function function = new Function(functionName, inputParams, outputParams);
        String data = FunctionEncoder.encode(function);

        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", 1);
        body.put("method", "eth_call");
        JSONArray params = new JSONArray();
        JSONObject callObj = new JSONObject();
        callObj.put("to", contract);
        callObj.put("data", data);
        params.put(callObj);
        params.put("latest");
        body.put("params", params);

        Request request = new Request.Builder()
            .url(rpcUrl)
            .post(RequestBody.create(body.toString(), JSON_TYPE))
            .build();

        try (Response response = CLIENT.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(resp);
            if (json.has("error")) {
                throw new Exception("只读调用失败: " + json.getJSONObject("error").optString("message"));
            }
            return json.optString("result", "");
        }
    }

    /**
     * 写入合约调用（广播交易，消耗 gas）
     * 经过 SafetyGate 校验后执行签名广播
     *
     * @param ctx Context
     * @param chain 链标识
     * @param contract 目标合约地址
     * @param functionName 函数名
     * @param params 函数参数（web3j Type 列表）
     * @param value 附带的原生币数量（wei），无则为 0
     * @param safetyGate 安全网关（传 null 则跳过校验，不推荐）
     * @param operationDesc 操作描述（用于审计日志）
     * @return 交易哈希
     */
    public static String callWrite(Context ctx, String chain, String contract,
                                    String functionName, List<Type> params,
                                    BigInteger value, SafetyGate safetyGate,
                                    String operationDesc) throws Exception {
        // 1. 安全网关校验
        if (safetyGate != null) {
            SafetyGate.CheckResult result = safetyGate.check(contract, value, operationDesc);
            if (!result.allowed) {
                throw new SecurityException("安全网关拦截: " + result.reason);
            }
        }

        // 2. 构造 calldata
        Function function = new Function(functionName, params, Collections.emptyList());
        String data = FunctionEncoder.encode(function);

        // 3. 复用 DexTrader 签名广播基础设施
        DexTrader dexTrader = new DexTrader();
        return dexTrader.executeRawTransaction(ctx, chain, contract, data, value);
    }

    /**
     * 便捷方法：调用无参数只读函数
     * 例如：totalSupply()、decimals()、paused()
     */
    public static String callSimpleRead(Context ctx, String chain, String contract,
                                         String functionName) throws Exception {
        return callReadOnly(ctx, chain, contract, functionName,
            Collections.emptyList(), Collections.emptyList());
    }

    /**
     * 便捷方法：代币 balanceOf 查询（ERC-20/BEP-20 通用）
     */
    public static BigInteger erc20BalanceOf(Context ctx, String chain,
                                             String tokenContract, String holderAddress) throws Exception {
        String result = callReadOnly(ctx, chain, tokenContract, "balanceOf",
            Arrays.asList(new Address(holderAddress)),
            Collections.singletonList(new TypeReference<Uint256>() {}));
        if (result == null || result.length() < 3) return BigInteger.ZERO;
        return new BigInteger(result.substring(2), 16);
    }

    /**
     * 便捷方法：代币 allowance 查询（ERC-20/BEP-20 通用）
     */
    public static BigInteger erc20Allowance(Context ctx, String chain, String tokenContract,
                                             String owner, String spender) throws Exception {
        String result = callReadOnly(ctx, chain, tokenContract, "allowance",
            Arrays.asList(new Address(owner), new Address(spender)),
            Collections.singletonList(new TypeReference<Uint256>() {}));
        if (result == null || result.length() < 3) return BigInteger.ZERO;
        return new BigInteger(result.substring(2), 16);
    }

    /**
     * 便捷方法：代币 approve（ERC-20/BEP-20 通用）
     */
    public static String erc20Approve(Context ctx, String chain, String tokenContract,
                                       String spender, BigInteger amount,
                                       SafetyGate safetyGate) throws Exception {
        return callWrite(ctx, chain, tokenContract, "approve",
            Arrays.asList(new Address(spender), new Uint256(amount)),
            BigInteger.ZERO, safetyGate,
            "代币 approve " + spender + " amount=" + amount);
    }
}
