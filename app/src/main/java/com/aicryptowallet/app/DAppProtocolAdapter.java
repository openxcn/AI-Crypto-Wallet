package com.aicryptowallet.app;

import java.util.HashMap;
import java.util.Map;

/**
 * DApp 协议适配器 - 主流 DeFi 协议合约地址注册表
 *
 * 设计理念：
 * - 不重复实现通用合约调用（ContractCaller 已支持任意 ABI）
 * - 仅提供 AI 需要的"入口地址 + 常用函数签名"
 * - AI 拿到地址后用 call_contract_read/write 执行实际操作
 * - 所有写入仍经过 SafetyGate 校验
 *
 * 当前覆盖协议：
 *   - Aave V3：借贷（supply/borrow/repay/withdraw）
 *   - Lido：ETH 质押（stETH）
 *   - Curve：稳定币兑换（3Pool）
 *   - Uniswap V3：DEX（swap）
 *
 * 注意：地址可能随协议升级变化，建议 AI 在使用前用 call_contract_read 验证
 */
public class DAppProtocolAdapter {

    // ============================================================
    // 协议地址注册表
    // 结构：protocol -> chain -> contractName -> address
    // ============================================================

    private static final Map<String, Map<String, Map<String, String>>> REGISTRY = new HashMap<>();

    static {
        // ---------- Aave V3 ----------
        Map<String, Map<String, String>> aave = new HashMap<>();

        Map<String, String> aaveEth = new HashMap<>();
        aaveEth.put("Pool", "0x87870Bca3F3fD6335C3F4ce8392D69350B4fA4E2");
        aaveEth.put("PoolDataProvider", "0xFc21d6d146E6086C849b6276085eA3534650aF3C");
        aaveEth.put("UiPoolDataProvider", "0x91c0eA31b49B69Ea18607702d544A6407B83f2c7");
        aaveEth.put("AaveOracle", "0x54586bE62E3c3580375aE3723C145253060Ca0C2");
        aave.put("ETH", aaveEth);

        Map<String, String> aaveArb = new HashMap<>();
        aaveArb.put("Pool", "0x794a61358D6845594F94dc1DB02A252b5b4814aD");
        aaveArb.put("PoolDataProvider", "0x2218A117083f596e6F6a23a8E21d2Cf695f68D50");
        aave.put("ARB", aaveArb);

        Map<String, String> aaveBase = new HashMap<>();
        aaveBase.put("Pool", "0x6Ae43d3270ff4d249c96f7D0886442F6BB2D67C2");
        aave.put("BASE", aaveBase);

        Map<String, String> aavePoly = new HashMap<>();
        aavePoly.put("Pool", "0x794a61358D6845594F94dc1DB02A252b5b4814aD");
        aave.put("MATIC", aavePoly);

        REGISTRY.put("AAVE_V3", aave);

        // ---------- Lido ----------
        Map<String, Map<String, String>> lido = new HashMap<>();

        Map<String, String> lidoEth = new HashMap<>();
        lidoEth.put("StETH", "0xae7ab56511A5C2b46E54DCc2b43595Ba73c5e9C7");
        lidoEth.put("WstETH", "0x7f39C581F595B53c5cb19bD0b3f8dA6c935E2Ca0");
        lidoEth.put("LidoExecutionLayerRewardsVault", "0x388C818CA8B9251b393131C08a736A67ccB19297");
        lido.put("ETH", lidoEth);

        REGISTRY.put("LIDO", lido);

        // ---------- Curve ----------
        Map<String, Map<String, String>> curve = new HashMap<>();

        Map<String, String> curveEth = new HashMap<>();
        curveEth.put("3Pool", "0xbEbc44782C7dB0a1A60Cb6fe97d0b483032FF1C7");
        curveEth.put("Registry", "0x90E00ACe148ca3b23Ac1bC8C240C2a7Dd9c2d7f5");
        curveEth.put("TriCRV", "0x4ebdf703948ddCEA3B11f675B4D1F33949d97D6C");
        curve.put("ETH", curveEth);

        Map<String, String> curvePoly = new HashMap<>();
        curvePoly.put("am3Pool", "0x445FE580eF8d70FF569aB4805F7DC57f3134dfc5");
        curve.put("MATIC", curvePoly);

        REGISTRY.put("CURVE", curve);

        // ---------- Uniswap V3 ----------
        Map<String, Map<String, String>> uniswap = new HashMap<>();

        Map<String, String> uniEth = new HashMap<>();
        uniEth.put("Router", "0xE592427A0AEce92De3Edee1F18E0157C05861564");
        uniEth.put("Quoter", "0xb27308f9F90D607463bb33eA1BeBb41C27CE5AB6");
        uniEth.put("Factory", "0x1F98431c8aD98523631AE4a59f267346ea31F984");
        uniEth.put("NonfungiblePositionManager", "0xC36442b4a4522E871399CD717aBDD847Ab11FE88");
        uniswap.put("ETH", uniEth);

        Map<String, String> uniArb = new HashMap<>();
        uniArb.put("Router", "0xE592427A0AEce92De3Edee1F18E0157C05861564");
        uniArb.put("Quoter", "0xb27308f9F90D607463bb33eA1BeBb41C27CE5AB6");
        uniswap.put("ARB", uniArb);

        Map<String, String> uniPoly = new HashMap<>();
        uniPoly.put("Router", "0xE592427A0AEce92De3Edee1F18E0157C05861564");
        uniswap.put("MATIC", uniPoly);

        REGISTRY.put("UNISWAP_V3", uniswap);

        // ---------- EVM 代币常用地址（ETH 链为 ERC-20）----------
        Map<String, Map<String, String>> tokens = new HashMap<>();
        Map<String, String> tokensEth = new HashMap<>();
        tokensEth.put("USDT", "0xdAC17F958D2ee523a2206206994597C13D831ec7");
        tokensEth.put("USDC", "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48");
        tokensEth.put("DAI", "0x6B175474E89094C44Da98b954EedeAC495271d0F");
        tokensEth.put("WETH", "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2");
        tokensEth.put("WBTC", "0x2260FAC5E5542a773Aa44fBCfeDf7C193bc2C599");
        tokens.put("ETH", tokensEth);
        REGISTRY.put("TOKENS", tokens);
    }

    // ============================================================
    // 常用函数签名（供 AI 构建 calldata 时参考）
    // ============================================================

    public static final String[][] COMMON_FUNCTIONS = {
        // ERC20
        {"erc20_balanceOf", "balanceOf(address)"},
        {"erc20_allowance", "allowance(address,address)"},
        {"erc20_approve", "approve(address,uint256)"},
        {"erc20_transfer", "transfer(address,uint256)"},
        {"erc20_decimals", "decimals()"},
        {"erc20_symbol", "symbol()"},
        // Aave V3 Pool
        {"aave_supply", "supply(address,uint256,address,uint16)"},
        {"aave_withdraw", "withdraw(address,uint256,address)"},
        {"aave_borrow", "borrow(address,uint256,uint256,uint16,address)"},
        {"aave_repay", "repay(address,uint256,uint256,address)"},
        {"aave_getUserAccountData", "getUserAccountData(address)"},
        // Lido
        {"lido_submit", "submit(address)"},
        {"lido_stEthPerToken", "stEthPerToken()"},
        // Curve
        {"curve_exchange", "exchange(int128,int128,uint256,uint256)"},
        {"curve_add_liquidity", "add_liquidity(uint256[3],uint256)"},
        {"curve_remove_liquidity", "remove_liquidity(uint256,uint256[3])"},
        // Uniswap V3
        {"uni_exactInputSingle", "exactInputSingle((address,address,uint24,address,uint256,uint256,uint256,uint160))"},
        {"uni_quoteExactInputSingle", "quoteExactInputSingle(address,address,uint24,uint256,uint160)"},
    };

    // ============================================================
    // API
    // ============================================================

    /**
     * 查询协议合约地址
     * @param protocol 协议名（AAVE_V3/LIDO/CURVE/UNISWAP_V3/TOKENS）
     * @param chain    链标识（ETH/ARB/MATIC/BASE...）
     * @param contractName 合约名（Pool/Router/StETH/USDT...）
     * @return 合约地址，未找到返回 null
     */
    public static String getAddress(String protocol, String chain, String contractName) {
        Map<String, Map<String, String>> protocolMap = REGISTRY.get(protocol.toUpperCase());
        if (protocolMap == null) return null;
        Map<String, String> chainMap = protocolMap.get(chain.toUpperCase());
        if (chainMap == null) return null;
        return chainMap.get(contractName);
    }

    /**
     * 列出某协议在某链上所有已知合约
     */
    public static String listContracts(String protocol, String chain) {
        Map<String, Map<String, String>> protocolMap = REGISTRY.get(protocol.toUpperCase());
        if (protocolMap == null) return "未知协议: " + protocol;
        Map<String, String> chainMap = protocolMap.get(chain.toUpperCase());
        if (chainMap == null) return "协议 " + protocol + " 在链 " + chain + " 上无已知合约";
        StringBuilder sb = new StringBuilder();
        sb.append(protocol).append(" @ ").append(chain).append(":\n");
        for (Map.Entry<String, String> e : chainMap.entrySet()) {
            sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }
        return sb.toString();
    }

    /**
     * 列出所有支持的协议
     */
    public static String listProtocols() {
        StringBuilder sb = new StringBuilder("已知协议:\n");
        for (String p : REGISTRY.keySet()) {
            sb.append("- ").append(p).append(" (链: ");
            sb.append(String.join("/", REGISTRY.get(p).keySet())).append(")\n");
        }
        return sb.toString();
    }

    /**
     * 列出所有常用函数签名
     */
    public static String listFunctions() {
        StringBuilder sb = new StringBuilder("常用函数签名:\n");
        for (String[] f : COMMON_FUNCTIONS) {
            sb.append("- ").append(f[0]).append(" → ").append(f[1]).append("\n");
        }
        return sb.toString();
    }

    /**
     * 查询函数签名
     * @param funcKey 函数 key（如 aave_supply）
     * @return 函数签名，未找到返回 null
     */
    public static String getFunctionSignature(String funcKey) {
        for (String[] f : COMMON_FUNCTIONS) {
            if (f[0].equalsIgnoreCase(funcKey)) return f[1];
        }
        return null;
    }

    /**
     * 生成完整的协议参考手册（供 AI 上下文注入）
     */
    public static String getReferenceManual() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== DApp 协议参考手册 ===\n\n");
        sb.append("支持的协议:\n");
        sb.append(listProtocols()).append("\n");
        sb.append("常用函数签名:\n");
        sb.append(listFunctions()).append("\n");
        sb.append("使用方式:\n");
        sb.append("1. 用 get_dapp_address 查询合约地址\n");
        sb.append("2. 用 call_contract_read 查询状态（如 getUserAccountData）\n");
        sb.append("3. 用 call_contract_write 执行操作（如 supply）\n");
        sb.append("4. 所有写入必须经过 SafetyGate\n");
        return sb.toString();
    }
}
