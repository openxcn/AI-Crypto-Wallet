package com.aicryptowallet.app;

import android.content.Context;
import android.content.Intent;
import android.util.Xml;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import java.io.StringReader;
import com.aicryptowallet.app.crosschain.CrossChainQuote;
import com.aicryptowallet.app.crosschain.CrossChainRequest;
import com.aicryptowallet.app.crosschain.CrossChainSwapManager;
import com.aicryptowallet.app.crosschain.CrossChainUtils;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Agent 工具注册表 - 让 AI 智能体能调用合约访问 DApp
 *
 * 产品定位：AI 中长线炒币助手的智能体钱包
 * 本类是 Agent Runtime 的能力声明层，定义 AI 可调用的工具集合：
 *
 * 只读工具（不消耗 gas，不改变链上状态）：
 *   - get_wallet_address     查询当前钱包地址
 *   - get_native_balance     查询原生币余额
 *   - get_token_balance      查询代币余额（ERC-20/BEP-20）
 *   - get_token_price        查询代币当前价格
 *   - get_position           查询当前持仓状态
 *   - get_market_data        拉取 K 线技术指标
 *   - get_safety_status      查询安全网关状态（熔断/限额）
 *   - call_contract_read     任意只读合约调用（eth_call）
 *
 * 写入工具（消耗 gas，改变链上状态，必须经过 SafetyGate 校验）：
 *   - call_contract_write    任意写入合约调用（广播交易）
 *   - swap_tokens            DEX 代币兑换
 *   - approve_token          代币授权（ERC-20/BEP-20）
 *   - send_native            原生币转账
 *
 * 安全设计：
 * 1. 所有写入工具强制经过 SafetyGate.check() 校验
 * 2. 熔断状态下所有写入工具直接拒绝（保护性操作如平仓除外）
 * 3. 每个工具调用记录到 Logger 审计日志
 * 4. 工具执行失败返回结构化错误，AI 可基于错误调整策略
 * 5. 不暴露私钥、助记词等敏感信息
 */
public class AgentToolRegistry {

    /**
     * 工具执行结果
     */
    public static class ToolResult {
        public final boolean success;
        public final String output;       // JSON 字符串，供 AI 理解
        public final String errorMessage; // 失败时的错误描述

        private ToolResult(boolean success, String output, String errorMessage) {
            this.success = success;
            this.output = output;
            this.errorMessage = errorMessage;
        }

        public static ToolResult success(String output) {
            return new ToolResult(true, output, null);
        }

        public static ToolResult error(String message) {
            return new ToolResult(false, null, message);
        }

        public String toJsonString() {
            try {
                JSONObject o = new JSONObject();
                o.put("success", success);
                if (success) {
                    o.put("output", output);
                } else {
                    o.put("error", errorMessage);
                }
                return o.toString();
            } catch (Exception e) {
                return "{\"success\":false,\"error\":\"serialize_failed\"}";
            }
        }
    }

    /**
     * 工具定义（名称 + 描述 + 参数 JSON Schema）
     */
    public static class ToolDefinition {
        public final String name;
        public final String description;
        public final String parametersSchema; // JSON Schema 字符串

        public ToolDefinition(String name, String description, String parametersSchema) {
            this.name = name;
            this.description = description;
            this.parametersSchema = parametersSchema;
        }
    }

    /**
     * 工具调用记录（用于审计）
     */
    public static class ToolCallRecord {
        public final String toolName;
        public final String arguments;       // 原始参数 JSON
        public final long timestamp;
        public final ToolResult result;

        public ToolCallRecord(String toolName, String arguments, ToolResult result) {
            this.toolName = toolName;
            this.arguments = arguments;
            this.timestamp = System.currentTimeMillis();
            this.result = result;
        }
    }

    // ============================================================
    // 工具定义
    // ============================================================

    public static final String TOOL_GET_WALLET_ADDRESS = "get_wallet_address";
    public static final String TOOL_GET_WALLET_ASSETS = "get_wallet_assets";
    public static final String TOOL_GET_NATIVE_BALANCE = "get_native_balance";
    public static final String TOOL_GET_TOKEN_BALANCE = "get_token_balance";
    public static final String TOOL_GET_TOKEN_PRICE = "get_token_price";
    public static final String TOOL_GET_POSITION = "get_position";
    public static final String TOOL_GET_MARKET_DATA = "get_market_data";
    public static final String TOOL_GET_SAFETY_STATUS = "get_safety_status";
    public static final String TOOL_CALL_CONTRACT_READ = "call_contract_read";
    public static final String TOOL_CALL_CONTRACT_WRITE = "call_contract_write";
    public static final String TOOL_SWAP_TOKENS = "swap_tokens";
    public static final String TOOL_CROSS_CHAIN_SWAP = "cross_chain_swap";
    public static final String TOOL_AUTHORIZE_CROSS_CHAIN_BUY = "authorize_cross_chain_buy";
    public static final String TOOL_APPROVE_TOKEN = "approve_token";
    public static final String TOOL_SEND_NATIVE = "send_native";
    public static final String TOOL_ASK_USER = "ask_user";
    public static final String TOOL_SEARCH_NEWS = "search_news";
    public static final String TOOL_FETCH_WEB_PAGE = "fetch_web_page";
    public static final String TOOL_BROWSER_OPEN_URL = "browser_open_url";
    public static final String TOOL_BROWSER_GET_STATE = "browser_get_state";
    public static final String TOOL_BROWSER_CLICK = "browser_click";
    public static final String TOOL_BROWSER_INPUT = "browser_input";
    public static final String TOOL_BROWSER_EVALUATE = "browser_evaluate";
    public static final String TOOL_BROWSER_CLOSE = "browser_close";
    public static final String TOOL_BROWSER_LIST_TABS = "browser_list_tabs";
    public static final String TOOL_GET_DAPP_ADDRESS = "get_dapp_address";
    public static final String TOOL_GET_FUNCTION_SIGNATURE = "get_function_signature";
    public static final String TOOL_OPEN_CREATE_WALLET = "open_create_wallet";
    public static final String TOOL_LIST_WALLETS = "list_wallets";
    public static final String TOOL_SWITCH_WALLET = "switch_wallet";
    public static final String TOOL_QUERY_DAPP_WHITELIST = "query_dapp_whitelist";
    public static final String TOOL_REQUEST_DAPP_WHITELIST = "request_dapp_whitelist";
    public static final String TOOL_REMOVE_DAPP_WHITELIST = "remove_dapp_whitelist";

    private static final List<ToolDefinition> TOOLS = Collections.unmodifiableList(Arrays.asList(
        new ToolDefinition(
            TOOL_GET_WALLET_ADDRESS,
            "查询当前钱包在指定链上的地址。用于 AI 了解自己的身份。",
            "{\"type\":\"object\",\"properties\":{\"chain\":{\"type\":\"string\",\"description\":\"链标识，如 ETH/BNB/SOL/TRX\"}},\"required\":[\"chain\"]}"
        ),
        new ToolDefinition(
            TOOL_GET_WALLET_ASSETS,
            "获取钱包内所有代币/资产的完整列表，包含代币符号、名称、余额、USD价值、合约地址。用于 AI 了解主人的持仓全貌。",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}"
        ),
        new ToolDefinition(
            TOOL_GET_NATIVE_BALANCE,
            "查询当前钱包在指定链上的原生币余额（如 ETH/BNB/SOL），返回余额和折合 USD 价值。",
            "{\"type\":\"object\",\"properties\":{\"chain\":{\"type\":\"string\",\"description\":\"链标识\"}},\"required\":[\"chain\"]}"
        ),
        new ToolDefinition(
            TOOL_GET_TOKEN_BALANCE,
            "查询当前钱包持有的代币余额（ETH链为ERC-20，BNB链为BEP-20）。需要代币合约地址。",
            "{\"type\":\"object\",\"properties\":{\"chain\":{\"type\":\"string\",\"description\":\"链标识\"},\"token_contract\":{\"type\":\"string\",\"description\":\"代币合约地址（ETH链为ERC-20，BNB链为BEP-20）\"}},\"required\":[\"chain\",\"token_contract\"]}"
        ),
        new ToolDefinition(
            TOOL_GET_TOKEN_PRICE,
            "查询代币当前价格（USD）。支持主流币种如 ETH/BNB/SOL/TRX/MATIC/AVAX。",
            "{\"type\":\"object\",\"properties\":{\"symbol\":{\"type\":\"string\",\"description\":\"代币符号，如 ETH\"}},\"required\":[\"symbol\"]}"
        ),
        new ToolDefinition(
            TOOL_GET_POSITION,
            "查询当前所有持仓状态，包含代币、数量、平均成本、止损止盈价、未实现盈亏。",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}"
        ),
        new ToolDefinition(
            TOOL_GET_MARKET_DATA,
            "拉取指定链指定周期的 K 线技术指标（RSI/MACD/MA/布林带）。用于中长线分析。",
            "{\"type\":\"object\",\"properties\":{\"chain\":{\"type\":\"string\",\"description\":\"链标识\"},\"cycle\":{\"type\":\"string\",\"description\":\"K 线周期：15m/1h/4h/1d\",\"default\":\"1h\"},\"limit\":{\"type\":\"integer\",\"description\":\"K 线数量，默认 100\",\"default\":100}},\"required\":[\"chain\",\"cycle\"]}"
        ),
        new ToolDefinition(
            TOOL_GET_SAFETY_STATUS,
            "查询安全网关状态，包括是否熔断、剩余熔断时间、今日交易笔数/金额、错误率、连续亏损次数。AI 在执行写入操作前应先查询此状态。",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}"
        ),
        new ToolDefinition(
            TOOL_CALL_CONTRACT_READ,
            "只读调用任意合约函数（eth_call，不消耗 gas）。用于查询链上状态如 totalSupply/allowance/reserves/paused 等。参数列表中每个元素为 {type, value}，type 支持 address/uint256/string/bool/bytes。",
            "{\"type\":\"object\",\"properties\":{\"chain\":{\"type\":\"string\",\"description\":\"链标识\"},\"contract\":{\"type\":\"string\",\"description\":\"合约地址\"},\"function\":{\"type\":\"string\",\"description\":\"函数签名，如 balanceOf(address)\"},\"params\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}}},\"required\":[\"chain\",\"contract\",\"function\"]}"
        ),
        new ToolDefinition(
            TOOL_CALL_CONTRACT_WRITE,
            "写入调用任意合约函数（广播交易，消耗 gas）。必须经过 SafetyGate 校验。操作描述用于审计日志。AI 调用此工具前应先调用 get_safety_status 确认未熔断。",
            "{\"type\":\"object\",\"properties\":{\"chain\":{\"type\":\"string\",\"description\":\"链标识\"},\"contract\":{\"type\":\"string\",\"description\":\"合约地址\"},\"function\":{\"type\":\"string\",\"description\":\"函数签名\"},\"params\":{\"type\":\"array\",\"items\":{\"type\":\"object\"}},\"value_wei\":{\"type\":\"string\",\"description\":\"附带原生币数量（wei 字符串），可选\"},\"operation_desc\":{\"type\":\"string\",\"description\":\"操作描述（用于审计，必须清晰说明意图）\"}},\"required\":[\"chain\",\"contract\",\"function\",\"operation_desc\"]}"
        ),
        new ToolDefinition(
            TOOL_SWAP_TOKENS,
            "通过 DEX 路由兑换代币。执行前必须先调用 ask_user 工具让用户确认链、付出资产、目标资产（原生币用 'NATIVE'，代币必须给出官方合约地址）、金额和风险。必须明确目标代币所在的链和合约地址，禁止自行猜测。非白名单代币仍由 SafetyGate 弹窗让用户确认。必须经过 SafetyGate 校验。",
            "{\"type\":\"object\",\"properties\":{\"chain\":{\"type\":\"string\",\"description\":\"链标识\"},\"from_token\":{\"type\":\"string\",\"description\":\"付出代币合约地址，原生币用 'NATIVE'\"},\"to_token\":{\"type\":\"string\",\"description\":\"得到代币合约地址，原生币用 'NATIVE'；必须明确该代币所在链和合约地址\"},\"amount\":{\"type\":\"number\",\"description\":\"付出数量（人类可读单位，如 0.1 ETH）\"},\"operation_desc\":{\"type\":\"string\",\"description\":\"操作描述（用于审计）\"}},\"required\":[\"chain\",\"from_token\",\"to_token\",\"amount\",\"operation_desc\"]}"
        ),
        new ToolDefinition(
            TOOL_CROSS_CHAIN_SWAP,
            "后台自动执行跨链资产兑换：AI 直接聚合跨链桥报价、后台自动签名并广播交易，无需用户在前端页面手动确认或打开网页。当用户需要在不同链之间兑换原生币或代币时调用，例如 BNB 链 USDT 换成波场链 TRX。注意：Transit Finance（swap.transit.finance）已被系统禁止，禁止推荐/打开/连接。自动执行受跨链限额保护（单笔 $100 / 每日 $500），超出会被拦截。调用此工具需传入：付出链、目标链、付出资产、目标资产（原生币用 'NATIVE'，代币必须给出官方合约地址）、金额。destination_address 可选，为空时自动使用当前钱包在目标链的地址。",
            "{\"type\":\"object\",\"properties\":{\"from_chain\":{\"type\":\"string\",\"description\":\"付出链标识，如 ETH/BNB/SOL/TRX\"},\"to_chain\":{\"type\":\"string\",\"description\":\"目标链标识，如 ETH/BNB/SOL/TRX\"},\"from_token\":{\"type\":\"string\",\"description\":\"付出代币合约地址，原生币用 'NATIVE'\"},\"to_token\":{\"type\":\"string\",\"description\":\"得到代币合约地址，原生币用 'NATIVE'；必须明确该代币所在链和合约地址\"},\"amount\":{\"type\":\"number\",\"description\":\"付出数量（人类可读单位）\"},\"destination_address\":{\"type\":\"string\",\"description\":\"目标链收款地址；如为空则自动查找当前钱包中目标链的地址\"},\"slippage\":{\"type\":\"number\",\"description\":\"滑点百分比，默认 1.5\"},\"operation_desc\":{\"type\":\"string\",\"description\":\"操作描述（用于审计）\"}},\"required\":[\"from_chain\",\"to_chain\",\"from_token\",\"to_token\",\"amount\",\"operation_desc\"]}"
        ),
        new ToolDefinition(
            TOOL_AUTHORIZE_CROSS_CHAIN_BUY,
            "记录用户对跨链自动买入的授权。当用户同意 AI 在未来检测到某条链资产买入信号时，自动用指定目标地址跨链买入，调用此工具保存授权。之后 AI 调用 cross_chain_swap 且未提供目标地址时，会自动使用该授权地址。",
            "{\"type\":\"object\",\"properties\":{\"chain\":{\"type\":\"string\",\"description\":\"目标链标识，如 TRX/SOL\"},\"asset\":{\"type\":\"string\",\"description\":\"目标资产：原生币用 'NATIVE'，代币传合约地址\"},\"destination_address\":{\"type\":\"string\",\"description\":\"目标链收款地址\"},\"allow_auto_buy\":{\"type\":\"boolean\",\"description\":\"是否允许 AI 检测到买入信号后自动买入，默认 true\"}},\"required\":[\"chain\",\"asset\",\"destination_address\"]}"
        ),
        new ToolDefinition(
            TOOL_APPROVE_TOKEN,
            "代币授权操作（ETH链为ERC-20，BNB链为BEP-20）。允许 spender 从本钱包转移指定数量的代币。必须经过 SafetyGate 校验。",
            "{\"type\":\"object\",\"properties\":{\"chain\":{\"type\":\"string\",\"description\":\"链标识\"},\"token_contract\":{\"type\":\"string\",\"description\":\"代币合约地址（ETH链为ERC-20，BNB链为BEP-20）\"},\"spender\":{\"type\":\"string\",\"description\":\"被授权地址\"},\"amount\":{\"type\":\"string\",\"description\":\"授权数量（最小单位字符串），无限授权用 'max'\"},\"operation_desc\":{\"type\":\"string\",\"description\":\"操作描述（用于审计）\"}},\"required\":[\"chain\",\"token_contract\",\"spender\",\"amount\",\"operation_desc\"]}"
        ),
        new ToolDefinition(
            TOOL_SEND_NATIVE,
            "转账原生币到指定地址。必须经过 SafetyGate 校验。请谨慎使用，确认地址正确。",
            "{\"type\":\"object\",\"properties\":{\"chain\":{\"type\":\"string\",\"description\":\"链标识\"},\"to_address\":{\"type\":\"string\",\"description\":\"收款地址\"},\"amount\":{\"type\":\"number\",\"description\":\"转账数量（人类可读单位）\"},\"operation_desc\":{\"type\":\"string\",\"description\":\"操作描述（用于审计）\"}},\"required\":[\"chain\",\"to_address\",\"amount\",\"operation_desc\"]}"
        ),
        new ToolDefinition(
            TOOL_ASK_USER,
            "重大决策询问用户。当出现 STRONG_BUY/STRONG_SELL 信号、单笔交易金额较大、或遇到不确定的重大决策时，调用此工具向用户提问。用户回复后，AI 再决定是否执行。",
            "{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\",\"description\":\"要问用户的问题，必须清晰说明决策内容、风险和预期收益\"},\"context\":{\"type\":\"string\",\"description\":\"决策上下文，包含分析依据、技术指标等\"},\"urgency\":{\"type\":\"string\",\"enum\":[\"high\",\"medium\",\"low\"],\"description\":\"紧急程度：high=立即需用户决策，medium=本轮分析内决策，low=仅供参考\"}},\"required\":[\"question\",\"context\",\"urgency\"]}"
        ),
        new ToolDefinition(
            TOOL_SEARCH_NEWS,
            "搜索加密货币市场新闻。返回最近的相关新闻列表（标题+摘要+来源+时间）。用于定时向用户推送市场动态、重大事件提醒。",
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"搜索关键词，如 'BTC' 或 'Ethereum upgrade' 或 '市场暴跌'\"},\"limit\":{\"type\":\"integer\",\"description\":\"返回新闻条数，默认 5\",\"default\":5}},\"required\":[\"query\"]}"
        ),
        new ToolDefinition(
            TOOL_FETCH_WEB_PAGE,
            "抓取网页内容并返回纯文本。用于读取 RSS 新闻源、CoinGecko 页面、项目公告等公开网页。完全去中心化，不需要任何 API key。适用于 CryptoCompare RSS、CoinDesk RSS、CoinTelegraph RSS、PANews、BlockBeats 等加密货币新闻源，以及 CoinGecko 代币信息页面。",
            "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"要抓取的网页 URL，支持 RSS Feed 和普通网页\"},\"max_length\":{\"type\":\"integer\",\"description\":\"返回内容最大字符数，默认 3000\"，\"default\":3000}},\"required\":[\"url\"]}"
        ),
        new ToolDefinition(
            TOOL_BROWSER_OPEN_URL,
            "打开指定 URL 到 App 内置 DApp 浏览器。用于让 AI 能在网页端执行操作前的第一步。",
            "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"要打开的网页 URL\"}},\"required\":[\"url\"]}"
        ),
        new ToolDefinition(
            TOOL_BROWSER_GET_STATE,
            "获取当前 DApp 浏览器页面的结构化状态（精简格式节省 Token）：url、title、text（页面文本前300字）、inputs（输入框，含 type/ph/id/name）、buttons（可点击按钮，含文本 t/id）、links（链接，含文本 t/href）。AI 应根据返回的元素信息决定下一步点击或输入操作。",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}"
        ),
        new ToolDefinition(
            TOOL_BROWSER_CLICK,
            "在 DApp 浏览器中点击指定 CSS 选择器的元素。",
            "{\"type\":\"object\",\"properties\":{\"selector\":{\"type\":\"string\",\"description\":\"CSS 选择器，如 #swap-button 或 .btn-confirm\"}},\"required\":[\"selector\"]}"
        ),
        new ToolDefinition(
            TOOL_BROWSER_INPUT,
            "在 DApp 浏览器指定输入框中填入文本。",
            "{\"type\":\"object\",\"properties\":{\"selector\":{\"type\":\"string\",\"description\":\"CSS 选择器，如 input[name=amount]\"},\"text\":{\"type\":\"string\",\"description\":\"要填入的文本\"}},\"required\":[\"selector\",\"text\"]}"
        ),
        new ToolDefinition(
            TOOL_BROWSER_EVALUATE,
            "在 DApp 浏览器中执行任意 JavaScript 并返回结果。用于获取复杂页面状态或执行自定义操作。",
            "{\"type\":\"object\",\"properties\":{\"script\":{\"type\":\"string\",\"description\":\"要执行的 JavaScript 代码\"}},\"required\":[\"script\"]}"
        ),
        new ToolDefinition(
            TOOL_BROWSER_CLOSE,
            "关闭当前打开的 DApp 浏览器页面。可传入 url 参数关闭指定标签页。当页面打不开、无法读取内容、或用户要求关闭网页时调用。此操作不受白名单限制，任何已打开的页面都能被关闭。",
            "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"要关闭的标签页 URL，可选。指定后关闭匹配该 URL 的标签页；不指定则关闭当前标签页\"}},\"required\":[]}"
        ),
        new ToolDefinition(
            TOOL_BROWSER_LIST_TABS,
            "列出当前 DApp 浏览器中所有已打开的标签页，包含每个标签页的 URL、标题、打开时间、是否当前激活标签页。用于 AI 了解当前打开了哪些网页，决定要关闭哪个或继续操作哪个。",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}"
        ),
        new ToolDefinition(
            TOOL_GET_DAPP_ADDRESS,
            "查询 DeFi 协议在指定链上的合约地址。支持 Aave V3（借贷）、Lido（ETH 质押）、Curve（稳定币兑换）、Uniswap V3（DEX）。返回合约地址和可用函数签名列表，之后可用 call_contract_read/write 执行链上操作。完全去中心化，无需注册任何 API。",
            "{\"type\":\"object\",\"properties\":{\"protocol\":{\"type\":\"string\",\"description\":\"协议名：AAVE_V3/LIDO/CURVE/UNISWAP_V3/TOKENS\"},\"chain\":{\"type\":\"string\",\"description\":\"链标识：ETH/ARB/MATIC/BASE\"},\"contract\":{\"type\":\"string\",\"description\":\"合约名：Pool/Router/StETH/USDT/USDC/DAI/WETH/WBTC。可选，留空则列出该协议在该链上所有合约\"}},\"required\":[\"protocol\",\"chain\"]}"
        ),
        new ToolDefinition(
            TOOL_GET_FUNCTION_SIGNATURE,
            "查询 DeFi 协议的常用函数签名。返回完整的 Solidity 函数签名（含参数类型），可用于构建 call_contract_read/write 的 calldata。包括 ERC20（balanceOf/approve/transfer）、Aave（supply/borrow/repay/withdraw）、Lido（submit）、Curve（exchange/add_liquidity）、Uniswap V3（exactInputSingle）等。完全去中心化。",
            "{\"type\":\"object\",\"properties\":{\"func_key\":{\"type\":\"string\",\"description\":\"函数 key，如 erc20_balanceOf/aave_supply/uni_exactInputSingle。留空则列出所有可用函数\"}},\"required\":[]}"
        ),
        new ToolDefinition(
            TOOL_OPEN_CREATE_WALLET,
            "当用户表达想要创建新钱包时调用。AI 不能直接创建钱包，调用此工具后会在 App 界面上显示一个进入指定链钱包创建流程的按钮。",
            "{\"type\":\"object\",\"properties\":{\"chain\":{\"type\":\"string\",\"enum\":[\"ETH\",\"BNB\",\"SOL\",\"TRX\",\"AVAX\",\"MATIC\",\"ARB\",\"SUI\",\"APT\",\"ADA\",\"CORE\",\"NEAR\",\"FTM\",\"ATOM\",\"DOT\",\"BTC\"],\"description\":\"要在哪条链创建钱包，如 ETH/BNB/SOL/TRX\"}},\"required\":[\"chain\"]}"
        ),
        new ToolDefinition(
            TOOL_LIST_WALLETS,
            "列出所有可用钱包的完整列表，包含钱包ID、名称、地址（缩略）、所属链、类型（HD/观察/导入）。用于 AI 了解主人有哪些钱包和链，方便跨钱包分析。",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}"
        ),
        new ToolDefinition(
            TOOL_SWITCH_WALLET,
            "切换到指定的钱包。切换后所有后续工具调用（get_wallet_assets、get_native_balance 等）都将作用于新钱包。注意：切换钱包后需重新获取资产数据。",
            "{\"type\":\"object\",\"properties\":{\"wallet_id\":{\"type\":\"string\",\"description\":\"要切换到的钱包ID（UUID），从 list_wallets 获取\"}},\"required\":[\"wallet_id\"]}"
        ),
        new ToolDefinition(
            TOOL_QUERY_DAPP_WHITELIST,
            "查询当前已加入 AI 自动操作白名单的 DApp 列表，包含域名、允许的操作、每日/单笔额度限制。",
            "{\"type\":\"object\",\"properties\":{},\"required\":[]}"
        ),
        new ToolDefinition(
            TOOL_REQUEST_DAPP_WHITELIST,
            "申请将某个 DApp 域名加入 AI 自动操作白名单。调用后会先向用户请求确认，用户同意后才写入白名单。加入白名单后，AI 可在该 DApp 内自动点击、输入、执行 JS、并在额度内自动确认交易。",
            "{\"type\":\"object\",\"properties\":{\"domain\":{\"type\":\"string\",\"description\":\"DApp 域名或 URL，如 https://game.example.com 或 game.example.com\"},\"daily_cap_usd\":{\"type\":\"number\",\"description\":\"每日额度上限（USD），默认 100\",\"default\":100},\"per_tx_cap_usd\":{\"type\":\"number\",\"description\":\"单笔交易额度上限（USD），默认 10\",\"default\":10},\"allow_click\":{\"type\":\"boolean\",\"description\":\"允许自动点击，默认 true\",\"default\":true},\"allow_input\":{\"type\":\"boolean\",\"description\":\"允许自动输入，默认 true\",\"default\":true},\"allow_evaluate\":{\"type\":\"boolean\",\"description\":\"允许执行 JS，默认 true\",\"default\":true},\"allow_transaction\":{\"type\":\"boolean\",\"description\":\"允许自动交易，默认 true\",\"default\":true}},\"required\":[\"domain\"]}"
        ),
        new ToolDefinition(
            TOOL_REMOVE_DAPP_WHITELIST,
            "将指定域名从 AI 自动操作白名单中移除，移除后 AI 不能再自动操作该 DApp。",
            "{\"type\":\"object\",\"properties\":{\"domain\":{\"type\":\"string\",\"description\":\"要移除的 DApp 域名或 URL\"}},\"required\":[\"domain\"]}"
        )
    ));

    /**
     * 获取所有工具定义
     */
    public static List<ToolDefinition> getTools() {
        return TOOLS;
    }

    /**
     * 生成 OpenAI function calling 兼容的工具描述 JSON
     * 格式：[{type: "function", function: {name, description, parameters}}]
     */
    public static JSONArray getOpenAIToolsJson() {
        JSONArray arr = new JSONArray();
        for (ToolDefinition tool : TOOLS) {
            try {
                JSONObject entry = new JSONObject();
                entry.put("type", "function");
                JSONObject fn = new JSONObject();
                fn.put("name", tool.name);
                fn.put("description", tool.description);
                fn.put("parameters", new JSONObject(tool.parametersSchema));
                entry.put("function", fn);
                arr.put(entry);
            } catch (Exception e) {
                // schema 解析失败跳过（理论上不会发生，schema 都是硬编码合法 JSON）
            }
        }
        return arr;
    }

    /**
     * 生成 Claude tool use 兼容的工具描述 JSON
     * 格式：[{name, description, input_schema}]
     */
    public static JSONArray getClaudeToolsJson() {
        JSONArray arr = new JSONArray();
        for (ToolDefinition tool : TOOLS) {
            try {
                JSONObject entry = new JSONObject();
                entry.put("name", tool.name);
                entry.put("description", tool.description);
                entry.put("input_schema", new JSONObject(tool.parametersSchema));
                arr.put(entry);
            } catch (Exception e) {
                // 同上
            }
        }
        return arr;
    }

    // ============================================================
    // 工具执行入口
    // ============================================================

    /**
     * 工具执行器接口，用于重试包装
     */
    private interface ToolExecutor {
        ToolResult execute() throws Exception;
    }

    /**
     * 对网络型只读工具执行一次重试。
     * 仅对超时/连接/DNS/5xx/SSL 等临时错误重试；
     * 合约 revert、余额不足、权限错误等不重试。
     */
    private static ToolResult executeWithRetry(Context ctx, String toolName, ToolExecutor executor) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return executor.execute();
            } catch (SecurityException se) {
                // SafetyGate 拦截必须透传，让外层记录"安全拦截"
                throw se;
            } catch (Exception e) {
                lastException = e;
                if (attempt == 1 && isRetryableToolError(e)) {
                    Logger.warning(ctx, "Agent工具", toolName + " 临时失败，1.5s 后重试: " + e.getMessage());
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                Logger.error(ctx, "Agent工具", "工具执行失败 " + toolName + ": " + e.getMessage(), e);
                return ToolResult.error("工具执行失败: " + e.getMessage());
            }
        }
        String msg = lastException != null ? lastException.getMessage() : "未知错误";
        Logger.error(ctx, "Agent工具", "工具重试后仍失败 " + toolName + ": " + msg, lastException);
        return ToolResult.error("工具执行失败: " + msg);
    }

    /**
     * 判断工具异常是否属于可重试的临时网络错误。
     */
    private static boolean isRetryableToolError(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) return true;
        if (e instanceof java.net.ConnectException) return true;
        if (e instanceof java.net.UnknownHostException) return true;
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("timeout") || msg.contains("connect") || msg.contains("socket")) return true;
        if (msg.contains("ssl") || msg.contains("handshake")) return true;
        if (msg.contains("http") || msg.contains("code")) {
            // 包含 5xx 或 429 时重试
            if (msg.matches(".*\\b5\\d{2}\\b.*")) return true;
            if (msg.contains("429")) return true;
        }
        // 不重试合约/业务错误
        if (msg.contains("revert") || msg.contains("insufficient") || msg.contains("slippage") ||
            msg.contains("denied") || msg.contains("not allowed") || msg.contains("invalid")) {
            return false;
        }
        return false;
    }

    /**
     * 将工具调用转换为自然语言描述
     */
    private static String getToolActionDescription(String toolName, JSONObject args) {
        if (args == null) args = new JSONObject();
        switch (toolName) {
            case TOOL_GET_WALLET_ADDRESS: return "查询钱包地址";
            case TOOL_GET_WALLET_ASSETS: return "获取钱包资产列表";
            case TOOL_GET_NATIVE_BALANCE: return "查询 " + args.optString("chain", "当前链") + " 原生币余额";
            case TOOL_GET_TOKEN_BALANCE: return "查询代币余额";
            case TOOL_GET_TOKEN_PRICE: return "查询 " + args.optString("symbol", "代币") + " 价格";
            case TOOL_GET_POSITION: return "查询当前持仓";
            case TOOL_GET_MARKET_DATA: return "获取行情数据";
            case TOOL_GET_SAFETY_STATUS: return "查询安全网关状态";
            case TOOL_CALL_CONTRACT_READ: return "只读调用合约 " + args.optString("contract", "");
            case TOOL_CALL_CONTRACT_WRITE: return "写入合约 " + args.optString("contract", "");
            case TOOL_SWAP_TOKENS: return "兑换代币";
            case TOOL_CROSS_CHAIN_SWAP: return "跨链兑换资产";
            case TOOL_AUTHORIZE_CROSS_CHAIN_BUY: return "授权跨链自动买入";
            case TOOL_APPROVE_TOKEN: return "授权代币 " + args.optString("token_contract", "");
            case TOOL_SEND_NATIVE: return "转账 " + args.optString("chain", "") + " 原生币";
            case TOOL_SEARCH_NEWS: return "搜索新闻：" + args.optString("query", "");
            case TOOL_FETCH_WEB_PAGE: return "获取网页内容：" + args.optString("url", "");
            case TOOL_BROWSER_OPEN_URL: return "打开浏览器链接：" + args.optString("url", "");
            case TOOL_BROWSER_GET_STATE: return "获取浏览器页面状态";
            case TOOL_BROWSER_CLICK: {
                String target = args.optString("text", args.optString("selector", ""));
                return "点击页面元素：" + target;
            }
            case TOOL_BROWSER_INPUT: return "在页面输入：" + args.optString("text", "");
            case TOOL_BROWSER_EVALUATE: return "执行浏览器脚本";
            case TOOL_BROWSER_CLOSE: {
                String u = args.optString("url", "");
                return u.isEmpty() ? "关闭 DApp 浏览器页面" : "关闭 DApp 标签页：" + u;
            }
            case TOOL_BROWSER_LIST_TABS: return "列出 DApp 浏览器标签页";
            case TOOL_GET_DAPP_ADDRESS: return "获取 DApp 地址";
            case TOOL_GET_FUNCTION_SIGNATURE: return "获取函数签名";
            case TOOL_OPEN_CREATE_WALLET: return "创建新钱包";
            case TOOL_LIST_WALLETS: return "列出所有钱包";
            case TOOL_SWITCH_WALLET: return "切换钱包";
            case TOOL_QUERY_DAPP_WHITELIST: return "查询 DApp 白名单";
            case TOOL_REQUEST_DAPP_WHITELIST: return "申请添加 DApp 白名单：" + args.optString("domain", "");
            case TOOL_REMOVE_DAPP_WHITELIST: return "移除 DApp 白名单：" + args.optString("domain", "");
            default: return "执行 " + toolName;
        }
    }

    private static String getOperationDescription(String toolName, JSONObject args, boolean success) {
        String action = getToolActionDescription(toolName, args);
        if (action == null || action.isEmpty()) {
            action = "执行 " + toolName;
        }
        return action + (success ? "（成功）" : "（失败）");
    }

    /**
     * 执行工具调用
     *
     * @param ctx        Context
     * @param toolName   工具名称
     * @param args       参数 JSON 对象
     * @param chain      当前链上下文
     * @param safetyGate 安全网关（写入工具必需，传 null 时写入工具将拒绝执行）
     * @return ToolResult
     */
    public static ToolResult execute(Context ctx, String toolName, JSONObject args,
                                      String chain, SafetyGate safetyGate) {
        ToolResult result = executeInternal(ctx, toolName, args, chain, safetyGate);

        // 记录 AI 操作日志（排除 ask_user 等权限请求类工具）
        if (!shouldSkipOperationLog(toolName)) {
            String status = result.success ? "success" : "failed";
            String desc = getOperationDescription(toolName, args, result.success);
            AIOperationLogManager.logToolOperation(ctx, toolName,
                args != null ? args.toString() : "{}",
                result.output != null ? result.output : "", status, desc);
        }

        return result;
    }

    private static boolean shouldSkipOperationLog(String toolName) {
        // ask_user 只是权限申请，不记录为 AI 操作
        return TOOL_ASK_USER.equals(toolName);
    }

    private static ToolResult executeInternal(Context ctx, String toolName, JSONObject args,
                                               String chain, SafetyGate safetyGate) {
        try {
            switch (toolName) {
                case TOOL_GET_WALLET_ADDRESS:
                    return executeGetWalletAddress(ctx, args, chain);
                case TOOL_GET_WALLET_ASSETS:
                    return executeGetWalletAssets(ctx, args, chain);
                case TOOL_GET_NATIVE_BALANCE:
                    return executeGetNativeBalance(ctx, args, chain);
                case TOOL_GET_TOKEN_BALANCE:
                    return executeGetTokenBalance(ctx, args, chain);
                case TOOL_GET_TOKEN_PRICE:
                    return executeWithRetry(ctx, toolName, () -> executeGetTokenPrice(ctx, args, chain));
                case TOOL_GET_POSITION:
                    return executeGetPosition(ctx, args, chain);
                case TOOL_GET_MARKET_DATA:
                    return executeWithRetry(ctx, toolName, () -> executeGetMarketData(ctx, args, chain));
                case TOOL_GET_SAFETY_STATUS:
                    return executeGetSafetyStatus(ctx, args, chain, safetyGate);
                case TOOL_CALL_CONTRACT_READ:
                    return executeWithRetry(ctx, toolName, () -> executeCallContractRead(ctx, args, chain));
                case TOOL_CALL_CONTRACT_WRITE:
                    return executeCallContractWrite(ctx, args, chain, safetyGate);
                case TOOL_SWAP_TOKENS:
                    return executeSwapTokens(ctx, args, chain, safetyGate);
                case TOOL_CROSS_CHAIN_SWAP:
                    return executeCrossChainSwap(ctx, args, chain, safetyGate);
                case TOOL_AUTHORIZE_CROSS_CHAIN_BUY:
                    return executeAuthorizeCrossChainBuy(ctx, args);
                case TOOL_APPROVE_TOKEN:
                    return executeApproveToken(ctx, args, chain, safetyGate);
                case TOOL_SEND_NATIVE:
                    return executeSendNative(ctx, args, chain, safetyGate);
                case TOOL_ASK_USER:
                    return executeAskUser(ctx, args);
                case TOOL_SEARCH_NEWS:
                    return executeWithRetry(ctx, toolName, () -> executeSearchNews(ctx, args));
                case TOOL_FETCH_WEB_PAGE:
                    return executeWithRetry(ctx, toolName, () -> executeFetchWebPage(ctx, args));
                case TOOL_BROWSER_OPEN_URL:
                    return executeBrowserOpenUrl(ctx, args);
                case TOOL_BROWSER_GET_STATE:
                    return executeWithRetry(ctx, toolName, () -> executeBrowserGetState(ctx, args));
                case TOOL_BROWSER_CLICK:
                    return executeWithRetry(ctx, toolName, () -> executeBrowserClick(ctx, args));
                case TOOL_BROWSER_INPUT:
                    return executeWithRetry(ctx, toolName, () -> executeBrowserInput(ctx, args));
                case TOOL_BROWSER_EVALUATE:
                    return executeWithRetry(ctx, toolName, () -> executeBrowserEvaluate(ctx, args));
                case TOOL_BROWSER_CLOSE:
                    return executeBrowserClose(ctx, args);
                case TOOL_BROWSER_LIST_TABS:
                    return executeBrowserListTabs(ctx);
                case TOOL_GET_DAPP_ADDRESS:
                    return executeGetDappAddress(ctx, args);
                case TOOL_GET_FUNCTION_SIGNATURE:
                    return executeGetFunctionSignature(ctx, args);
                case TOOL_LIST_WALLETS:
                    return executeListWallets(ctx);
                case TOOL_SWITCH_WALLET:
                    return executeSwitchWallet(ctx, args);
                case TOOL_OPEN_CREATE_WALLET:
                    return executeOpenCreateWallet(ctx, args);
                case TOOL_QUERY_DAPP_WHITELIST:
                    return executeQueryDAppWhitelist(ctx);
                case TOOL_REQUEST_DAPP_WHITELIST:
                    return executeRequestDAppWhitelist(ctx, args);
                case TOOL_REMOVE_DAPP_WHITELIST:
                    return executeRemoveDAppWhitelist(ctx, args);
                default:
                    return ToolResult.error("未知工具: " + toolName);
            }
        } catch (SecurityException se) {
            // SafetyGate 拦截
            Logger.warning(ctx, "Agent工具", "安全拦截 " + toolName + ": " + se.getMessage());
            return ToolResult.error("安全拦截: " + se.getMessage());
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "工具执行失败 " + toolName + ": " + e.getMessage(), e);
            return ToolResult.error("工具执行失败: " + e.getMessage());
        }
    }

    // ============================================================
    // 只读工具实现
    // ============================================================

    private static ToolResult executeGetWalletAddress(Context ctx, JSONObject args, String defaultChain) throws Exception {
        String chain = args.optString("chain", defaultChain);
        String address = WalletManager.getWalletAddress(ctx);
        JSONObject out = new JSONObject();
        out.put("chain", chain);
        out.put("address", address);
        return ToolResult.success(out.toString());
    }

    private static ToolResult executeGetWalletAssets(Context ctx, JSONObject args, String defaultChain) throws Exception {
        DataCache cache = new DataCache(ctx);
        String address = WalletManager.getWalletAddress(ctx);
        cache.setCurrentWallet(address);
        if (!cache.hasValidCache(address)) {
            return ToolResult.error("暂无缓存的钱包资产数据，请稍后重试或刷新首页");
        }

        List<String[]> tokens = cache.getCachedTokens();
        double totalValue = cache.getCachedTotalValue();

        JSONObject out = new JSONObject();
        out.put("total_value_usd", totalValue);
        out.put("token_count", tokens != null ? tokens.size() : 0);

        JSONArray assets = new JSONArray();
        if (tokens != null) {
            for (String[] token : tokens) {
                if (token.length < 4) continue;
                JSONObject asset = new JSONObject();
                asset.put("symbol", token[0] != null ? token[0] : "");
                asset.put("name", token.length > 1 && token[1] != null ? token[1] : "");
                asset.put("balance", token.length > 2 && token[2] != null ? token[2] : "0");
                asset.put("value_usd", token.length > 3 && token[3] != null ? token[3] : "$0");
                asset.put("contract", token.length > 4 && token[4] != null ? token[4] : "");
                asset.put("is_native", token.length > 5 && "true".equals(token[5]));
                assets.put(asset);
            }
        }
        out.put("assets", assets);
        return ToolResult.success(out.toString());
    }

    private static ToolResult executeGetNativeBalance(Context ctx, JSONObject args, String defaultChain) throws Exception {
        String chain = args.optString("chain", defaultChain);
        String address = WalletManager.getWalletAddress(ctx);
        double balance = ChainAPI.getNativeBalance(ctx, chain, address);
        java.util.Map<String, Double> prices = ChainAPI.getPrices(ctx);
        double price = prices.getOrDefault(chain, 0.0);
        JSONObject out = new JSONObject();
        out.put("chain", chain);
        out.put("balance", balance);
        out.put("price_usd", price);
        out.put("value_usd", balance * price);
        return ToolResult.success(out.toString());
    }

    private static ToolResult executeGetTokenBalance(Context ctx, JSONObject args, String defaultChain) throws Exception {
        String chain = args.optString("chain", defaultChain);
        String tokenContract = args.getString("token_contract");
        String address = WalletManager.getWalletAddress(ctx);
        // 先获取代币精度
        String[] tokenInfo = ChainAPI.getTokenInfo(ctx, chain, tokenContract);
        int decimals = 18;
        String symbol = "UNKNOWN";
        if (tokenInfo != null && tokenInfo.length >= 3) {
            symbol = tokenInfo[0];
            try { decimals = Integer.parseInt(tokenInfo[2]); } catch (Exception ignore) {}
        }
        double balance = ChainAPI.getERC20Balance(ctx, chain, address, tokenContract, decimals);
        JSONObject out = new JSONObject();
        out.put("chain", chain);
        out.put("token_contract", tokenContract);
        out.put("symbol", symbol);
        out.put("decimals", decimals);
        out.put("balance", balance);
        return ToolResult.success(out.toString());
    }

    private static ToolResult executeGetTokenPrice(Context ctx, JSONObject args, String defaultChain) throws Exception {
        String symbol = args.getString("symbol").toUpperCase();
        java.util.Map<String, Double> prices = ChainAPI.getPrices(ctx);
        Double price = prices.get(symbol);
        JSONObject out = new JSONObject();
        out.put("symbol", symbol);
        if (price != null) {
            out.put("price_usd", price);
            return ToolResult.success(out.toString());
        } else {
            out.put("price_usd", "null");
            out.put("note", "未找到该代币价格，仅支持主流币");
            return ToolResult.success(out.toString());
        }
    }

    private static ToolResult executeGetPosition(Context ctx, JSONObject args, String defaultChain) throws Exception {
        String summary = PositionManager.getPositionSummary(ctx);
        JSONObject out = new JSONObject();
        out.put("positions", summary);
        return ToolResult.success(out.toString());
    }

    private static ToolResult executeGetMarketData(Context ctx, JSONObject args, String defaultChain) throws Exception {
        String chain = args.optString("chain", defaultChain);
        String cycle = args.optString("cycle", "1h");
        int limit = args.optInt("limit", 100);
        MarketData data = MultiChainMarketData.getKlines(chain, cycle, limit);
        if (data == null || data.prices == null || data.prices.length == 0) {
            return ToolResult.error("无法获取 " + chain + " " + cycle + " K线数据");
        }
        TechnicalIndicators.IndicatorValues indicators =
            TechnicalIndicators.getLatest(data.prices, data.volumes);
        JSONObject out = new JSONObject();
        out.put("chain", chain);
        out.put("cycle", cycle);
        out.put("current_price", data.currentPrice);
        out.put("rsi", indicators.rsi);
        out.put("macd", indicators.macd);
        out.put("macd_signal", indicators.macdSignal);
        out.put("sma20", indicators.sma20);
        out.put("sma50", indicators.sma50);
        out.put("bb_lower", indicators.bbLower);
        out.put("bb_upper", indicators.bbUpper);
        // 最近 5 根收盘价
        JSONArray recent = new JSONArray();
        int start = Math.max(0, data.prices.length - 5);
        for (int i = start; i < data.prices.length; i++) recent.put(data.prices[i]);
        out.put("recent_closes", recent);
        return ToolResult.success(out.toString());
    }

    private static ToolResult executeGetSafetyStatus(Context ctx, JSONObject args, String defaultChain, SafetyGate safetyGate) throws Exception {
        JSONObject out = new JSONObject();
        if (safetyGate == null) {
            out.put("status", "safety_gate_not_initialized");
            out.put("warning", "安全网关未初始化，所有写入工具将被拒绝");
            return ToolResult.success(out.toString());
        }
        out.put("status", safetyGate.isCircuitBroken() ? "CIRCUIT_BROKEN" : "OK");
        if (safetyGate.isCircuitBroken()) {
            out.put("circuit_breaker_remaining_minutes", safetyGate.getCircuitBreakerRemainingMinutes());
            out.put("warning", "已熔断，所有写入操作被拒绝，请等待熔断解除");
        }
        out.put("detail", safetyGate.getSafetyStatusSummary());
        return ToolResult.success(out.toString());
    }

    private static ToolResult executeCallContractRead(Context ctx, JSONObject args, String defaultChain) throws Exception {
        String chain = args.optString("chain", defaultChain);
        String contract = args.getString("contract");
        String functionSig = args.getString("function");
        // 解析函数名（去掉括号后的部分）
        String functionName = functionSig;
        int parenIdx = functionSig.indexOf('(');
        if (parenIdx > 0) functionName = functionSig.substring(0, parenIdx);

        // 解析参数（简单实现：支持 address/uint256/string/bool）
        JSONArray paramsArr = args.optJSONArray("params");
        List<org.web3j.abi.datatypes.Type> inputParams = new ArrayList<>();
        if (paramsArr != null) {
            for (int i = 0; i < paramsArr.length(); i++) {
                JSONObject p = paramsArr.getJSONObject(i);
                String type = p.optString("type", "uint256");
                String value = p.optString("value", "");
                inputParams.add(buildWeb3jType(type, value));
            }
        }

        String resultHex = ContractCaller.callReadOnly(ctx, chain, contract, functionName,
            inputParams, Collections.emptyList());
        JSONObject out = new JSONObject();
        out.put("chain", chain);
        out.put("contract", contract);
        out.put("function", functionName);
        out.put("result_hex", resultHex);
        out.put("note", "结果为 hex 编码，AI 可按 ABI 解读");
        return ToolResult.success(out.toString());
    }

    // ============================================================
    // 写入工具实现（均经过 SafetyGate 校验）
    // ============================================================

    private static ToolResult executeCallContractWrite(Context ctx, JSONObject args, String defaultChain, SafetyGate safetyGate) throws Exception {
        if (safetyGate == null) {
            return ToolResult.error("安全网关未初始化，拒绝执行写入操作");
        }
        String chain = args.optString("chain", defaultChain);
        String contract = args.getString("contract");
        String functionSig = args.getString("function");
        String operationDesc = args.getString("operation_desc");
        String valueWeiStr = args.optString("value_wei", "0");
        BigInteger valueWei = new BigInteger(valueWeiStr);

        // SafetyGate 校验
        SafetyGate.CheckResult check = safetyGate.check(contract, valueWei, operationDesc);
        if (!check.allowed) {
            return ToolResult.error("安全网关拦截: " + check.reason);
        }

        String functionName = functionSig;
        int parenIdx = functionSig.indexOf('(');
        if (parenIdx > 0) functionName = functionSig.substring(0, parenIdx);

        JSONArray paramsArr = args.optJSONArray("params");
        List<org.web3j.abi.datatypes.Type> inputParams = new ArrayList<>();
        if (paramsArr != null) {
            for (int i = 0; i < paramsArr.length(); i++) {
                JSONObject p = paramsArr.getJSONObject(i);
                String type = p.optString("type", "uint256");
                String value = p.optString("value", "");
                inputParams.add(buildWeb3jType(type, value));
            }
        }

        Logger.action(ctx, "Agent写入", toolAuditLine(TOOL_CALL_CONTRACT_WRITE, chain, contract, operationDesc), null);

        String txHash = ContractCaller.callWrite(ctx, chain, contract, functionName,
            inputParams, valueWei, null, operationDesc); // safetyGate 已经在上面校验过，传 null 避免重复

        // 交易成功，通知 SafetyGate
        safetyGate.onTradeSuccess(0);

        JSONObject out = new JSONObject();
        out.put("chain", chain);
        out.put("contract", contract);
        out.put("function", functionName);
        out.put("tx_hash", txHash);
        out.put("status", "broadcast");
        return ToolResult.success(out.toString());
    }

    private static ToolResult executeSwapTokens(Context ctx, JSONObject args, String defaultChain, SafetyGate safetyGate) throws Exception {
        if (safetyGate == null) {
            return ToolResult.error("安全网关未初始化，拒绝执行兑换");
        }
        String chain = args.optString("chain", defaultChain);
        String fromToken = args.getString("from_token");
        String toToken = args.getString("to_token");
        double amount = args.getDouble("amount");
        String operationDesc = args.getString("operation_desc");

        // SafetyGate 校验（用预估 USD 价值，按 from_token 自身价格估算，避免误用链原生价）
        double approxUsd = estimateUsdValue(ctx, chain, fromToken, amount);
        // 修复：之前传固定字符串 "DEX_ROUTER" 导致白名单校验完全失效
        // 现在传真实的目标代币合约地址 toToken（若买原生币则传 "NATIVE" 豁免白名单）
        // 启用白名单确认弹窗：用户决策"加入白名单 / 仅本次允许 / 拒绝"
        String whitelistCheckTarget = "NATIVE".equalsIgnoreCase(toToken) ? "NATIVE" : toToken;
        String tokenSymbolForDialog = "NATIVE".equalsIgnoreCase(toToken)
            ? ChainAPI.getChainSymbol(chain)
            : resolveTokenSymbol(ctx, chain, toToken);
        SafetyGate.CheckResult check = safetyGate.checkWithWhitelistConfirm(
            whitelistCheckTarget,
            BigInteger.ZERO,
            operationDesc + " (估算 $" + approxUsd + ")",
            tokenSymbolForDialog,
            approxUsd
        );
        if (!check.allowed) {
            return ToolResult.error("安全网关拦截: " + check.reason);
        }

        Logger.action(ctx, "AgentSwap", toolAuditLine(TOOL_SWAP_TOKENS, chain, fromToken + "->" + toToken, operationDesc), null);

        DexTrader trader = new DexTrader();
        String txHash;
        // 简化路由：原生币→USDT、USDT→原生币、其他组合暂不支持（DexTrader 当前能力）
        boolean fromNative = "NATIVE".equalsIgnoreCase(fromToken);
        boolean toNative = "NATIVE".equalsIgnoreCase(toToken);
        if (fromNative && !toNative) {
            // 原生币 → 代币
            txHash = trader.swapNativeToken(ctx, chain, amount, 0.03);
        } else if (!fromNative && toNative) {
            // 代币 → 原生币
            txHash = trader.swapTokensForNative(ctx, chain, fromToken, amount, 0.03);
        } else if (!fromNative && !toNative) {
            // 代币 → 代币
            txHash = trader.swapTokens(ctx, fromToken, toToken, amount, 0.03);
        } else {
            return ToolResult.error("不支持原生币→原生币兑换");
        }

        safetyGate.onTradeSuccess(approxUsd);

        // 保存交易记录到本地（让用户在交易历史中看到 AI 操作）
        try {
            String fromSymbol = "NATIVE".equalsIgnoreCase(fromToken)
                ? ChainAPI.getChainSymbol(chain) : resolveTokenSymbol(ctx, chain, fromToken);
            String toSymbol = "NATIVE".equalsIgnoreCase(toToken)
                ? ChainAPI.getChainSymbol(chain) : resolveTokenSymbol(ctx, chain, toToken);
            String pair = fromSymbol + "/" + toSymbol;
            // side 判定：付出原生币/USDT 买代币=BUY；付出代币换原生币/USDT=SELL
            String side = toNative ? "SELL" : "BUY";
            TradeRecord record = new TradeRecord(
                System.currentTimeMillis(),
                chain,
                pair,
                side,
                amount,
                0, // price 由后续估算填充
                approxUsd,
                txHash,
                "AI_AGENT",
                0,
                "SUCCESS",
                "AI_AUTO"
            );
            TradeRecord.append(ctx, record);
            Logger.info(ctx, "AgentSwap", "已记录交易: " + pair + " " + side + " " + amount + " txHash=" + txHash);
        } catch (Exception e) {
            Logger.error(ctx, "AgentSwap", "保存交易记录失败: " + e.getMessage(), e);
        }

        JSONObject out = new JSONObject();
        out.put("chain", chain);
        out.put("from_token", fromToken);
        out.put("to_token", toToken);
        out.put("amount", amount);
        out.put("tx_hash", txHash);
        out.put("status", "broadcast");
        return ToolResult.success(out.toString());
    }

    /**
     * 执行跨链兑换：已放开前端显示限制，由 AI 在后台自动签名并广播交易，
     * 无需用户在前端页面手动确认。复用 CrossChainSwapManager 完成聚合报价、限额检查、签名广播全流程。
     */
    private static ToolResult executeCrossChainSwap(Context ctx, JSONObject args, String defaultChain, SafetyGate safetyGate) throws Exception {
        String fromChain = args.optString("from_chain", defaultChain).toUpperCase();
        String toChain = args.optString("to_chain", defaultChain).toUpperCase();
        String fromToken = args.optString("from_token", "NATIVE");
        String toToken = args.optString("to_token", "NATIVE");
        double amount = args.optDouble("amount", 0);
        String operationDesc = args.optString("operation_desc", "跨链兑换");
        double slippage = args.optDouble("slippage", 1.5);

        // 保留基础校验与审计日志
        if (amount <= 0) {
            return ToolResult.error("兑换数量必须大于 0");
        }

        Logger.action(ctx, "Agent跨链兑换",
            toolAuditLine(TOOL_CROSS_CHAIN_SWAP, fromChain + "->" + toChain,
                fromToken + "->" + toToken + " amount=" + amount, operationDesc), null);

        // 自动推导收款地址与源地址
        String fromAddress = CrossChainUtils.getWalletAddress(ctx, fromChain);
        if (fromAddress == null || fromAddress.isEmpty()) {
            return ToolResult.error("未找到源链(" + fromChain + ")钱包地址，请先创建或选择对应链钱包");
        }
        String toAddress = args.optString("destination_address", "");
        if (toAddress == null || toAddress.isEmpty()) {
            toAddress = CrossChainUtils.getWalletAddress(ctx, toChain);
        }
        if (toAddress == null || toAddress.isEmpty()) {
            toAddress = fromAddress; // 目标链无独立钱包地址时退化为同一地址
        }

        // 获取代币精度并换算为最小单位
        int decimals = CrossChainUtils.getTokenDecimals(ctx, fromChain, fromToken);
        String amountSmallest = CrossChainUtils.amountToSmallestUnit(amount, decimals);

        // 估算 USD 价值用于跨链限额（单笔 $100 / 每日 $500）
        double approxUsd = estimateCrossChainUsdValue(ctx, fromChain, amount);

        CrossChainRequest request = new CrossChainRequest(
            fromChain, toChain, fromToken, toToken,
            amountSmallest, fromAddress, toAddress, slippage);

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Boolean> successHolder = new AtomicReference<>(null);
        final AtomicReference<Object> dataHolder = new AtomicReference<>();
        final AtomicReference<String> errorHolder = new AtomicReference<>();

        CrossChainSwapManager manager = new CrossChainSwapManager(ctx);
        manager.execute(request, approxUsd, new CrossChainSwapManager.ExecutionCallback() {
            @Override
            public void onResult(boolean success, Object data, String error) {
                successHolder.set(success);
                dataHolder.set(data);
                errorHolder.set(error);
                latch.countDown();
            }
        });

        // 等待后台执行完成（聚合报价 + 签名广播），超时 180 秒
        if (!latch.await(180, TimeUnit.SECONDS)) {
            return ToolResult.error("跨链兑换执行超时（180 秒），请稍后重试或检查链上 RPC 状态");
        }

        if (Boolean.TRUE.equals(successHolder.get())) {
            Object data = dataHolder.get();
            JSONObject out = new JSONObject();
            out.put("from_chain", fromChain);
            out.put("to_chain", toChain);
            out.put("from_token", fromToken);
            out.put("to_token", toToken);
            out.put("amount", amount);
            out.put("from_address", fromAddress);
            out.put("to_address", toAddress);
            if (data instanceof String) {
                out.put("tx_hash", data);
            } else {
                out.put("result", data);
            }
            out.put("status", "auto_executed");
            out.put("note", "跨链交易已由 AI 在后台自动签名并广播，无需前端手动确认。");
            return ToolResult.success(out.toString());
        }

        return ToolResult.error("跨链兑换执行失败: " + errorHolder.get());
    }

    /**
     * 记录跨链自动买入授权。
     */
    private static ToolResult executeAuthorizeCrossChainBuy(Context ctx, JSONObject args) {
        try {
            String chain = args.getString("chain").toUpperCase();
            String asset = args.getString("asset");
            String destinationAddress = args.getString("destination_address");
            boolean allowAutoBuy = args.optBoolean("allow_auto_buy", true);

            if (chain.isEmpty() || asset.isEmpty() || destinationAddress.isEmpty()) {
                return ToolResult.error("参数不完整：chain、asset、destination_address 均不能为空");
            }

            CrossChainAutoBuyManager.authorize(ctx, chain, asset, destinationAddress, allowAutoBuy);

            JSONObject out = new JSONObject();
            out.put("chain", chain);
            out.put("asset", asset);
            out.put("destination_address", destinationAddress);
            out.put("allow_auto_buy", allowAutoBuy);
            out.put("status", "authorized");
            out.put("note", "已记录跨链自动买入授权，后续 AI 可在检测到买入信号时自动执行 cross_chain_swap");
            return ToolResult.success(out.toString());
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "authorize_cross_chain_buy 失败: " + e.getMessage(), e);
            return ToolResult.error("授权失败: " + e.getMessage());
        }
    }

    /**
     * 在钱包列表中查找指定链的收款地址。
     * 优先返回活跃钱包；若活跃钱包不匹配，则返回该链第一个有钱包地址的钱包。
     */
    private static String findWalletAddressForChain(Context ctx, String chain) {
        try {
            List<WalletManager.WalletInfo> wallets = WalletManager.getAllWallets(ctx);
            String activeId = WalletManager.getActiveWalletId(ctx);
            WalletManager.WalletInfo active = null;
            for (WalletManager.WalletInfo w : wallets) {
                if (w.id.equals(activeId)) {
                    active = w;
                    break;
                }
            }
            if (active != null && chain.equalsIgnoreCase(active.chain) && active.address != null && !active.address.isEmpty()) {
                return active.address;
            }
            for (WalletManager.WalletInfo w : wallets) {
                if (chain.equalsIgnoreCase(w.chain) && w.address != null && !w.address.isEmpty()) {
                    return w.address;
                }
            }
        } catch (Exception e) {
            Logger.warning(ctx, "Agent跨链兑换", "查找 " + chain + " 钱包地址失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 跨链兑换金额粗略估算为 USD（用于 SafetyGate 限额/熔断）。
     */
    private static double estimateCrossChainUsdValue(Context ctx, String chain, double amount) {
        try {
            java.util.Map<String, Double> prices = ChainAPI.getPrices(ctx);
            double price = prices.getOrDefault(chain.toUpperCase(), 0.0);
            return amount * price;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 通过 RPC 查询代币 symbol（ERC-20/BEP-20），用于白名单确认弹窗的展示
     * 查询失败时返回空字符串（不阻断流程）
     */
    private static String resolveTokenSymbol(Context ctx, String chain, String contract) {
        try {
            String rpcUrl = ChainAPI.getRpcUrlStatic(ctx, chain);
            String[] meta = ChainAPI.getERC20Metadata(rpcUrl, contract);
            if (meta != null && meta.length > 0 && meta[0] != null && !meta[0].isEmpty()) {
                return meta[0];
            }
        } catch (Exception e) {
            // 查询失败不影响主流程
        }
        return "";
    }

    private static ToolResult executeApproveToken(Context ctx, JSONObject args, String defaultChain, SafetyGate safetyGate) throws Exception {
        if (safetyGate == null) {
            return ToolResult.error("安全网关未初始化，拒绝执行授权");
        }
        String chain = args.optString("chain", defaultChain);
        String tokenContract = args.getString("token_contract");
        String spender = args.getString("spender");
        String amountStr = args.getString("amount");
        String operationDesc = args.getString("operation_desc");

        SafetyGate.CheckResult check = safetyGate.check(tokenContract, BigInteger.ZERO, operationDesc);
        if (!check.allowed) {
            return ToolResult.error("安全网关拦截: " + check.reason);
        }

        BigInteger amount;
        if ("max".equalsIgnoreCase(amountStr)) {
            amount = new BigInteger("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16);
        } else {
            amount = new BigInteger(amountStr);
        }

        Logger.action(ctx, "AgentApprove", toolAuditLine(TOOL_APPROVE_TOKEN, chain, tokenContract + "→" + spender, operationDesc), null);

        String txHash = ContractCaller.erc20Approve(ctx, chain, tokenContract, spender, amount, null);
        // approve 不计入交易笔数（不是真实交易）
        JSONObject out = new JSONObject();
        out.put("chain", chain);
        out.put("token_contract", tokenContract);
        out.put("spender", spender);
        out.put("amount", amount.toString());
        out.put("tx_hash", txHash);
        out.put("status", "broadcast");
        return ToolResult.success(out.toString());
    }

    private static ToolResult executeSendNative(Context ctx, JSONObject args, String defaultChain, SafetyGate safetyGate) throws Exception {
        if (safetyGate == null) {
            return ToolResult.error("安全网关未初始化，拒绝执行转账");
        }
        String chain = args.optString("chain", defaultChain);
        String toAddress = args.getString("to_address");
        double amount = args.getDouble("amount");
        String operationDesc = args.getString("operation_desc");

        int decimals = ChainAPI.getChainDecimals(chain);
        BigInteger valueWei = BigInteger.valueOf((long) (amount * Math.pow(10, decimals)));

        SafetyGate.CheckResult check = safetyGate.check(toAddress, valueWei, operationDesc);
        if (!check.allowed) {
            return ToolResult.error("安全网关拦截: " + check.reason);
        }

        Logger.action(ctx, "AgentSend", toolAuditLine(TOOL_SEND_NATIVE, chain, toAddress, operationDesc), null);

        DexTrader trader = new DexTrader();
        // 用空 data 转账原生币
        String txHash = trader.executeRawTransaction(ctx, chain, toAddress, "0x", valueWei);

        double approxUsd = estimateUsdValue(ctx, chain, "NATIVE", amount);
        safetyGate.onTradeSuccess(approxUsd);

        // 保存转账记录到本地（让用户在交易历史中看到 AI 操作）
        try {
            String chainSymbol = ChainAPI.getChainSymbol(chain);
            TradeRecord record = new TradeRecord(
                System.currentTimeMillis(),
                chain,
                chainSymbol + "→" + toAddress.substring(0, Math.min(6, toAddress.length())),
                "SEND",
                amount,
                0,
                approxUsd,
                txHash,
                "AI_AGENT",
                0,
                "SUCCESS",
                "AI_TRANSFER"
            );
            TradeRecord.append(ctx, record);
            Logger.info(ctx, "AgentSend", "已记录转账: " + amount + " " + chainSymbol + " -> " + toAddress);
        } catch (Exception e) {
            Logger.error(ctx, "AgentSend", "保存转账记录失败: " + e.getMessage(), e);
        }

        JSONObject out = new JSONObject();
        out.put("chain", chain);
        out.put("to_address", toAddress);
        out.put("amount", amount);
        out.put("tx_hash", txHash);
        out.put("status", "broadcast");
        return ToolResult.success(out.toString());
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /**
     * 根据 type 字符串构造 web3j Type 实例
     */
    private static org.web3j.abi.datatypes.Type buildWeb3jType(String type, String value) {
        switch (type.toLowerCase()) {
            case "address":
                return new org.web3j.abi.datatypes.Address(value);
            case "uint256":
            case "uint":
                return new org.web3j.abi.datatypes.generated.Uint256(new BigInteger(value));
            case "string":
                return new org.web3j.abi.datatypes.Utf8String(value);
            case "bool":
                return new org.web3j.abi.datatypes.Bool(Boolean.parseBoolean(value));
            case "bytes":
                byte[] b = org.web3j.utils.Numeric.hexStringToByteArray(value);
                return new org.web3j.abi.datatypes.DynamicBytes(b);
            default:
                throw new IllegalArgumentException("不支持的参数类型: " + type);
        }
    }

    /**
     * 估算 USD 价值。
     * @param tokenRef "NATIVE"=链原生币，否则为代币合约地址（解析其自身 symbol 价格）
     */
    private static double estimateUsdValue(Context ctx, String chain, String tokenRef, double amount) {
        try {
            java.util.Map<String, Double> prices = ChainAPI.getPrices(ctx);
            String symbol;
            if (tokenRef == null || "NATIVE".equalsIgnoreCase(tokenRef)
                    || "DEX_ROUTER".equalsIgnoreCase(tokenRef)) {
                // 原生币：用链原生 symbol 价格
                symbol = ChainAPI.getChainSymbol(chain);
            } else {
                // 代币：用代币自身 symbol 价格（避免误用链原生价导致 USD 估算失真）
                String s = resolveTokenSymbol(ctx, chain, tokenRef);
                symbol = (s != null && !s.isEmpty()) ? s : ChainAPI.getChainSymbol(chain);
            }
            double price = prices.getOrDefault(symbol, 0.0);
            if (price <= 0) {
                // 回退到链原生价
                price = prices.getOrDefault(chain, 0.0);
            }
            return amount * price;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String toolAuditLine(String tool, String chain, String target, String desc) {
        return tool + " chain=" + chain + " target=" + target + " desc=" + desc;
    }

    // ============================================================
    // 用户交互工具
    // ============================================================

    /**
     * 询问用户回调接口。
     * Activity 实现此接口以接收 AI 的提问，并展示给用户。
     */
    public interface AskUserCallback {
        /**
         * AI 向用户提问。实现方应将问题展示在聊天界面，并等待用户回复。
         * 本方法在后台线程被调用，实现方需自行切回主线程更新 UI。
         *
         * @param question AI 提出的问题
         * @param context  决策上下文（分析依据、技术指标等）
         * @param urgency  紧急程度：high/medium/low
         * @return 用户的回复文本；若用户未在超时内回复，返回空字符串
         */
        String onAskUser(String question, String context, String urgency);
    }

    /** 当前注册的询问用户回调（全局静态，由 AIAgentActivity 在 onResume 注册） */
    private static volatile AskUserCallback askUserCallback;

    /** 注册询问用户回调（Activity 在 onResume 时调用） */
    public static void setAskUserCallback(AskUserCallback cb) {
        askUserCallback = cb;
    }

    /** 注销询问用户回调（Activity 在 onPause/onDestroy 时调用） */
    public static void clearAskUserCallback() {
        askUserCallback = null;
    }

    /**
     * 执行 ask_user 工具：向用户提问并阻塞等待回复。
     * 如果当前没有 Activity 注册回调（如后台服务运行时），返回"用户未在线，请稍后再问"。
     */
    private static ToolResult executeAskUser(Context ctx, JSONObject args) {
        try {
            String question = args.getString("question");
            String contextDesc = args.optString("context", "");
            String urgency = args.optString("urgency", "medium");
            String userReply = askUserSync(ctx, question, contextDesc, urgency);
            if (userReply == null || userReply.trim().isEmpty()) {
                userReply = "用户未回复（超时），视为不同意";
            }
            JSONObject out = new JSONObject();
            out.put("user_reply", userReply);
            out.put("asked_question", question);
            return ToolResult.success(out.toString());
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "ask_user 执行失败: " + e.getMessage(), e);
            return ToolResult.error("询问用户失败: " + e.getMessage());
        }
    }

    /**
     * 同步询问用户并返回回复。若用户未在线或超时，返回 null。
     */
    private static String askUserSync(Context ctx, String question, String contextDesc, String urgency) {
        Logger.info(ctx, "Agent工具", "ask_user 调用: urgency=" + urgency + " question=" + question);
        String permTitle = "AI 申请权限";
        String permContent = question;
        if (contextDesc != null && !contextDesc.isEmpty()) {
            permContent += "\n" + contextDesc;
        }
        AINotificationHelper.notifyPermissionRequest(ctx, permTitle, permContent);

        AskUserCallback cb = askUserCallback;
        if (cb == null) {
            Logger.warning(ctx, "Agent工具", "ask_user 失败：无 Activity 回调（用户未在线）");
            return null;
        }
        return cb.onAskUser(question, contextDesc, urgency);
    }

    // ============================================================
    // 新闻搜索工具
    // ============================================================

    /**
     * 执行 search_news 工具：多源冗余获取加密货币新闻。
     * 1) CryptoCompare News API
     * 2) 多个 RSS 源轮询（Cointelegraph / CoinDesk / CryptoSlate / Decrypt / Odaily）
     * 3) CoinGecko 全局市场状态降级
     * 全部失败时返回降级提示，不阻断 Agent 流程。
     */
    private static ToolResult executeSearchNews(Context ctx, JSONObject args) {
        try {
            String query = args.getString("query");
            int limit = args.optInt("limit", 5);
            if (limit <= 0 || limit > 20) limit = 5;

            Logger.info(ctx, "Agent工具", "search_news 调用: query=" + query + " limit=" + limit);

            // 1) CryptoCompare News API（免费，无需 key）
            String newsJson = fetchCryptoCompareNews(query, limit);
            if (newsJson != null) {
                return ToolResult.success(newsJson);
            }

            // 2) RSS 源轮询（无需注册，去中心化）
            String[][] rssSources = {
                {"cointelegraph", "https://cointelegraph.com/rss"},
                {"coindesk", "https://www.coindesk.com/arc/outboundfeeds/rss/"},
                {"cryptoslate", "https://cryptoslate.com/feed/"},
                {"decrypt", "https://decrypt.co/feed"},
                {"odaily", "https://www.odaily.news/feed"}   // 中文源
            };
            for (String[] src : rssSources) {
                String rss = fetchRssNews(src[1], src[0], limit);
                if (rss != null) {
                    Logger.info(ctx, "Agent工具", "search_news 从 RSS 源获取成功: " + src[0]);
                    return ToolResult.success(rss);
                }
            }

            // 3) 降级：CoinGecko 全局市场状态
            String fallback = fetchCoinGeckoStatus();
            if (fallback != null) {
                JSONObject out = new JSONObject();
                out.put("source", "coingecko_fallback");
                out.put("note", "新闻搜索服务暂不可用，以下为市场整体状态");
                out.put("market_status", new JSONObject(fallback));
                return ToolResult.success(out.toString());
            }

            // 全部失败
            return ToolResult.error("新闻搜索服务暂不可用，请稍后再试。");
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "search_news 执行失败: " + e.getMessage(), e);
            return ToolResult.error("搜索新闻失败: " + e.getMessage());
        }
    }

    /** 调用 CryptoCompare News API */
    private static String fetchCryptoCompareNews(String query, int limit) {
        OkHttpClient client = createNewsClient();
        try {
            // CryptoCompare News API: https://min-api.cryptocompare.com/data/v2/news/?categories=BTC&lang=EN
            String url = "https://min-api.cryptocompare.com/data/v2/news/?lang=EN";
            if (query != null && !query.trim().isEmpty()) {
                // 简单关键词匹配：将 query 映射到 categories
                String q = query.trim().toUpperCase();
                if (q.contains("BTC") || q.contains("BITCOIN")) url += "&categories=BTC";
                else if (q.contains("ETH") || q.contains("ETHEREUM")) url += "&categories=ETH";
                else if (q.contains("BNB")) url += "&categories=BNB";
                else if (q.contains("SOL") || q.contains("SOLANA")) url += "&categories=SOL";
                else url += "&categories=" + java.net.URLEncoder.encode(query, "UTF-8");
            }

            Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AICryptoWallet")
                .get()
                .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) return null;
                String body = response.body() != null ? response.body().string() : "";
                if (body.isEmpty()) return null;

                JSONObject json = new JSONObject(body);
                JSONArray data = json.optJSONArray("Data");
                if (data == null || data.length() == 0) return null;

                // 提取关键字段，限制条数
                JSONArray out = new JSONArray();
                int count = Math.min(data.length(), limit);
                for (int i = 0; i < count; i++) {
                    JSONObject item = data.getJSONObject(i);
                    JSONObject n = new JSONObject();
                    n.put("title", item.optString("title", ""));
                    n.put("source", item.optString("source_info", item.optString("source", "")));
                    n.put("published_at", item.optString("published_on", ""));
                    n.put("url", item.optString("guid", ""));
                    n.put("categories", item.optString("categories", ""));
                    // body 字段可能包含 HTML，简单清理
                    String bodyText = item.optString("body", "");
                    if (bodyText.length() > 300) bodyText = bodyText.substring(0, 300) + "...";
                    n.put("summary", bodyText);
                    out.put(n);
                }

                JSONObject result = new JSONObject();
                result.put("source", "cryptocompare");
                result.put("query", query);
                result.put("count", out.length());
                result.put("news", out);
                return result.toString();
            }
        } catch (Exception e) {
            Logger.warning(null, "Agent工具", "CryptoCompare 新闻获取失败: " + e.getMessage());
            return null;
        }
    }

    /** 降级方案：调用 CoinGecko 全局市场状态 */
    private static String fetchCoinGeckoStatus() {
        OkHttpClient client = createNewsClient();
        try {
            Request request = new Request.Builder()
                .url("https://api.coingecko.com/api/v3/global")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AICryptoWallet")
                .get()
                .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) return null;
                String body = response.body() != null ? response.body().string() : "";
                if (body.isEmpty()) return null;
                JSONObject json = new JSONObject(body);
                JSONObject data = json.optJSONObject("data");
                if (data == null) return null;
                JSONObject out = new JSONObject();
                out.put("total_market_cap_usd", data.optJSONObject("total_market_cap").optString("usd", "0"));
                out.put("total_volume_usd", data.optJSONObject("total_volume").optString("usd", "0"));
                out.put("market_cap_change_24h_pct", data.optDouble("market_cap_change_percentage_24h_usd", 0));
                out.put("btc_dominance_pct", data.optJSONObject("market_cap_percentage").optDouble("btc", 0));
                out.put("eth_dominance_pct", data.optJSONObject("market_cap_percentage").optDouble("eth", 0));
                return out.toString();
            }
        } catch (Exception e) {
            Logger.warning(null, "Agent工具", "CoinGecko 市场状态获取失败: " + e.getMessage());
            return null;
        }
    }

    /** 调用 RSS 源获取新闻 */
    private static String fetchRssNews(String rssUrl, String sourceName, int limit) {
        OkHttpClient client = createNewsClient();
        try {
            Request request = new Request.Builder()
                .url(rssUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AICryptoWallet/1.0")
                .header("Accept", "application/rss+xml,application/xml,text/xml;q=0.9,*/*;q=0.8")
                .get()
                .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) return null;
                String body = response.body() != null ? response.body().string() : "";
                if (body.isEmpty()) return null;
                JSONArray items = parseRssItems(body, limit);
                if (items == null || items.length() == 0) return null;

                JSONObject result = new JSONObject();
                result.put("source", sourceName);
                result.put("query", "");
                result.put("count", items.length());
                result.put("news", items);
                return result.toString();
            }
        } catch (Exception e) {
            Logger.warning(null, "Agent工具", sourceName + " RSS 获取失败: " + e.getMessage());
            return null;
        }
    }

    /** 解析 RSS XML，提取 <item> 的标题/摘要/链接/发布时间 */
    private static JSONArray parseRssItems(String xml, int limit) {
        JSONArray items = new JSONArray();
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new StringReader(xml));
            int eventType = parser.getEventType();
            JSONObject current = null;
            String text = null;
            while (eventType != XmlPullParser.END_DOCUMENT && items.length() < limit) {
                String tag = parser.getName();
                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        if ("item".equals(tag) || "entry".equals(tag)) current = new JSONObject();
                        break;
                    case XmlPullParser.TEXT:
                    case XmlPullParser.CDSECT:
                        String t = parser.getText();
                        if (t != null) {
                            text = text == null ? t : text + t;
                        }
                        break;
                    case XmlPullParser.END_TAG:
                        if (current != null && text != null) {
                            String val = text.trim();
                            if ("title".equals(tag)) {
                                current.put("title", stripHtml(val));
                            } else if ("description".equals(tag) || "summary".equals(tag) || "content".equals(tag) || "content:encoded".equals(tag)) {
                                if (!current.has("summary") || current.optString("summary").isEmpty()) {
                                    String desc = stripHtml(val);
                                    if (desc.length() > 300) desc = desc.substring(0, 300) + "...";
                                    current.put("summary", desc);
                                }
                            } else if ("link".equals(tag)) {
                                current.put("url", val);
                            } else if ("pubDate".equals(tag) || "published".equals(tag) || "updated".equals(tag)) {
                                current.put("published_at", val);
                            }
                        }
                        if (("item".equals(tag) || "entry".equals(tag)) && current != null) {
                            if (!current.has("title")) current.put("title", "");
                            if (!current.has("summary")) current.put("summary", "");
                            if (!current.has("url")) current.put("url", "");
                            if (!current.has("published_at")) current.put("published_at", "");
                            current.put("source", "RSS");
                            // 过滤掉空标题条目
                            if (!current.optString("title").isEmpty() || !current.optString("summary").isEmpty()) {
                                items.put(current);
                            }
                            current = null;
                        }
                        text = null;
                        break;
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Logger.warning(null, "Agent工具", "RSS 解析失败: " + e.getMessage());
        }
        return items;
    }

    /** 创建信任所有证书的 OkHttpClient（绕过国内 SSL 干扰，与 ChainAPI 保持一致） */
    private static OkHttpClient createNewsClient() {
        try {
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                }
            };
            javax.net.ssl.SSLContext sslCtx = javax.net.ssl.SSLContext.getInstance("TLS");
            sslCtx.init(null, trustAll, new java.security.SecureRandom());
            return new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .sslSocketFactory(sslCtx.getSocketFactory(), (javax.net.ssl.X509TrustManager) trustAll[0])
                .hostnameVerifier((hostname, session) -> true)
                .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0")
                    .build()))
                .build();
        } catch (Exception e) {
            return new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        }
    }

    // ============================================================
    // DApp 协议工具（完全去中心化，纯链上合约地址，无需注册任何 API）
    // ============================================================

    // ============================================================
    // fetch_web_page 工具：抓取网页内容，用于读取 RSS 新闻源、CoinGecko 等
    // 完全去中心化，不需要任何 API key，直接 HTTP GET
    // ============================================================

    /**
     * 执行 fetch_web_page 工具：抓取网页内容并返回纯文本。
     * 支持 RSS Feed 和普通 HTML 页面，自动剥离 HTML 标签。
     */
    private static ToolResult executeFetchWebPage(Context ctx, JSONObject args) {
        try {
            String url = args.getString("url");
            int maxLength = args.optInt("max_length", 3000);
            if (maxLength <= 0 || maxLength > 15000) maxLength = 3000;

            Logger.info(ctx, "Agent工具", "fetch_web_page 调用: url=" + url);

            // 验证 URL 协议
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return ToolResult.error("不支持的 URL 协议，仅支持 http/https: " + url);
            }

            OkHttpClient client = createNewsClient();
            Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AICryptoWallet/1.0")
                .header("Accept", "text/html,application/xhtml+xml,application/xml,application/rss+xml;q=0.9,*/*;q=0.8")
                .get()
                .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return ToolResult.error("HTTP " + response.code() + ": 无法获取页面内容");
                }
                String body = response.body() != null ? response.body().string() : "";
                if (body.isEmpty()) {
                    return ToolResult.error("页面内容为空");
                }

                // 剥离 HTML 标签，提取纯文本
                String text = stripHtml(body);

                // 去除多余空白
                text = text.replaceAll("\\s+", " ").trim();

                // 截断到 maxLength
                if (text.length() > maxLength) {
                    text = text.substring(0, maxLength) + "...(内容已截断)";
                }

                JSONObject result = new JSONObject();
                result.put("url", url);
                result.put("content_type", response.header("Content-Type", "unknown"));
                result.put("text_length", text.length());
                result.put("text", text);
                return ToolResult.success(result.toString());
            }
        } catch (java.net.SocketTimeoutException e) {
            Logger.warning(ctx, "Agent工具", "fetch_web_page 超时: " + e.getMessage());
            return ToolResult.error("网页抓取超时，请稍后再试");
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "fetch_web_page 失败: " + e.getMessage(), e);
            return ToolResult.error("网页抓取失败: " + e.getMessage());
        }
    }

    // ============================================================
    // AI 浏览器控制工具
    // ============================================================

    private static ToolResult executeBrowserOpenUrl(Context ctx, JSONObject args) {
        try {
            String url = args.getString("url");
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return ToolResult.error("不支持的 URL 协议，仅支持 http/https");
            }
            Logger.info(ctx, "Agent工具", "browser_open_url 调用: " + url);
            DAppBrowserActivity.openUrl(ctx, url);
            JSONObject out = new JSONObject();
            out.put("success", true);
            out.put("message", "已打开 DApp 浏览器: " + url);
            return ToolResult.success(out.toString());
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "browser_open_url 失败: " + e.getMessage(), e);
            return ToolResult.error("打开浏览器失败: " + e.getMessage());
        }
    }

    private static ToolResult executeBrowserGetState(Context ctx, JSONObject args) {
        try {
            Logger.info(ctx, "Agent工具", "browser_get_state 调用");
            String state = DAppBrowserActivity.getPageState();
            return ToolResult.success(state);
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "browser_get_state 失败: " + e.getMessage(), e);
            return ToolResult.error("获取页面状态失败: " + e.getMessage());
        }
    }

    private static ToolResult executeBrowserClick(Context ctx, JSONObject args) {
        try {
            String selector = args.getString("selector");
            Logger.info(ctx, "Agent工具", "browser_click 调用: " + selector);
            String result = DAppBrowserActivity.clickElement(selector);
            return ToolResult.success(result);
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "browser_click 失败: " + e.getMessage(), e);
            return ToolResult.error("点击元素失败: " + e.getMessage());
        }
    }

    private static ToolResult executeBrowserInput(Context ctx, JSONObject args) {
        try {
            String selector = args.getString("selector");
            String text = args.getString("text");
            Logger.info(ctx, "Agent工具", "browser_input 调用: " + selector + " text=" + text);
            String result = DAppBrowserActivity.inputText(selector, text);
            return ToolResult.success(result);
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "browser_input 失败: " + e.getMessage(), e);
            return ToolResult.error("输入文本失败: " + e.getMessage());
        }
    }

    private static ToolResult executeBrowserEvaluate(Context ctx, JSONObject args) {
        try {
            String script = args.getString("script");
            Logger.info(ctx, "Agent工具", "browser_evaluate 调用");
            String result = DAppBrowserActivity.evaluateJs(script);
            return ToolResult.success(result);
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "browser_evaluate 失败: " + e.getMessage(), e);
            return ToolResult.error("执行 JS 失败: " + e.getMessage());
        }
    }

    private static ToolResult executeBrowserClose(Context ctx, JSONObject args) {
        try {
            String url = args != null ? args.optString("url", "") : "";
            Logger.info(ctx, "Agent工具", "browser_close 调用 url=" + url);
            String result = DAppBrowserActivity.closePage(url);
            return ToolResult.success(result);
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "browser_close 失败: " + e.getMessage(), e);
            return ToolResult.error("关闭 DApp 浏览器失败: " + e.getMessage());
        }
    }

    private static ToolResult executeBrowserListTabs(Context ctx) {
        try {
            Logger.info(ctx, "Agent工具", "browser_list_tabs 调用");
            String result = DAppBrowserActivity.listTabs();
            return ToolResult.success(result);
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "browser_list_tabs 失败: " + e.getMessage(), e);
            return ToolResult.error("列出 DApp 标签页失败: " + e.getMessage());
        }
    }

    /**
     * 剥离 HTML 标签，提取纯文本。
     * 处理常见 HTML 实体，保留换行结构。
     */
    private static String stripHtml(String html) {
        if (html == null || html.isEmpty()) return "";

        // 替换常见块级标签为换行
        String text = html
            .replaceAll("(?i)<br\\s*/?>", "\n")
            .replaceAll("(?i)</?p[^>]*>", "\n")
            .replaceAll("(?i)</?div[^>]*>", "\n")
            .replaceAll("(?i)</?li[^>]*>", "\n")
            .replaceAll("(?i)</?h[1-6][^>]*>", "\n")
            .replaceAll("(?i)</?tr[^>]*>", "\n")
            .replaceAll("(?i)</?td[^>]*>", " ")
            .replaceAll("(?i)<title[^>]*>([^<]*)</title>", "[标题: $1]\n");

        // 移除所有 HTML 标签
        text = text.replaceAll("<[^>]+>", "");

        // 解码常见 HTML 实体
        text = text.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&#x2F;", "/")
            .replace("&#x27;", "'");

        // 解码数字实体
        text = text.replaceAll("&#(\\d+);", "");

        return text;
    }

    // ============================================================

    private static ToolResult executeGetDappAddress(Context ctx, JSONObject args) {
        try {
            String protocol = args.getString("protocol");
            String chain = args.optString("chain", "");
            String contract = args.optString("contract", "");

            Logger.info(ctx, "Agent工具", "get_dapp_address: protocol=" + protocol + " chain=" + chain + " contract=" + contract);

            JSONObject out = new JSONObject();
            out.put("protocol", protocol);
            out.put("chain", chain);

            if (contract.isEmpty()) {
                String list = DAppProtocolAdapter.listContracts(protocol, chain);
                out.put("contracts", list);
            } else {
                String address = DAppProtocolAdapter.getAddress(protocol, chain, contract);
                if (address != null) {
                    out.put("contract", contract);
                    out.put("address", address);
                    // 附带该合约可能用到的函数签名
                    JSONArray funcs = new JSONArray();
                    for (String[] f : DAppProtocolAdapter.COMMON_FUNCTIONS) {
                        if (f[0].startsWith(protocol.toLowerCase().replace("_v3","")) ||
                            f[0].startsWith("erc20")) {
                            JSONObject fn = new JSONObject();
                            fn.put("key", f[0]);
                            fn.put("signature", f[1]);
                            funcs.put(fn);
                        }
                    }
                    out.put("suggested_functions", funcs);
                } else {
                    out.put("error", "未找到合约 " + contract + "，请检查协议/链/合约名是否正确");
                }
            }
            return ToolResult.success(out.toString());
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "get_dapp_address 失败: " + e.getMessage(), e);
            return ToolResult.error("查询失败: " + e.getMessage());
        }
    }

    private static ToolResult executeGetFunctionSignature(Context ctx, JSONObject args) {
        try {
            String funcKey = args.optString("func_key", "");

            Logger.info(ctx, "Agent工具", "get_function_signature: func_key=" + funcKey);

            JSONObject out = new JSONObject();
            if (funcKey.isEmpty()) {
                JSONArray all = new JSONArray();
                for (String[] f : DAppProtocolAdapter.COMMON_FUNCTIONS) {
                    JSONObject fn = new JSONObject();
                    fn.put("key", f[0]);
                    fn.put("signature", f[1]);
                    all.put(fn);
                }
                out.put("functions", all);
            } else {
                String sig = DAppProtocolAdapter.getFunctionSignature(funcKey);
                if (sig != null) {
                    out.put("key", funcKey);
                    out.put("signature", sig);
                } else {
                    out.put("error", "未找到函数 " + funcKey);
                }
            }
            return ToolResult.success(out.toString());
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "get_function_signature 失败: " + e.getMessage(), e);
            return ToolResult.error("查询失败: " + e.getMessage());
        }
    }

    // ============================================================
    // 多钱包工具
    // ============================================================

    private static ToolResult executeListWallets(Context ctx) {
        try {
            java.util.List<WalletManager.WalletInfo> wallets = WalletManager.getAllWallets(ctx);
            String activeId = WalletManager.getActiveWalletId(ctx);

            JSONObject out = new JSONObject();
            out.put("total_wallets", wallets.size());
            out.put("active_wallet_id", activeId);

            JSONArray arr = new JSONArray();
            for (WalletManager.WalletInfo w : wallets) {
                JSONObject item = new JSONObject();
                item.put("id", w.id);
                item.put("name", w.name);
                item.put("address", w.address);
                item.put("short_address", w.getShortAddress());
                item.put("chain", w.chain);
                item.put("type", w.type);
                item.put("type_label", w.getTypeLabel());
                item.put("is_watch_only", w.isWatchOnly());
                item.put("has_private_key", w.hasPrivateKey());
                item.put("is_active", w.id.equals(activeId));
                arr.put(item);
            }
            out.put("wallets", arr);

            Logger.info(ctx, "Agent工具", "list_wallets: 共 " + wallets.size() + " 个钱包，活跃=" + activeId);
            return ToolResult.success(out.toString());
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "list_wallets 失败: " + e.getMessage(), e);
            return ToolResult.error("列出钱包失败: " + e.getMessage());
        }
    }

    private static ToolResult executeSwitchWallet(Context ctx, JSONObject args) {
        try {
            String walletId = args.getString("wallet_id");

            WalletManager.WalletInfo target = WalletManager.getWalletById(ctx, walletId);
            if (target == null) {
                return ToolResult.error("未找到钱包 ID: " + walletId + "，请先调用 list_wallets 获取有效钱包列表");
            }

            String oldId = WalletManager.getActiveWalletId(ctx);
            if (walletId.equals(oldId)) {
                JSONObject out = new JSONObject();
                out.put("wallet_id", walletId);
                out.put("wallet_name", target.name);
                out.put("chain", target.chain);
                out.put("address", target.getShortAddress());
                out.put("note", "已经是当前活跃钱包，无需切换");
                return ToolResult.success(out.toString());
            }

            WalletManager.setActiveWalletId(ctx, walletId);
            WalletManager.setChain(ctx, target.chain);

            Logger.info(ctx, "Agent工具", "switch_wallet: " + oldId + " → " + walletId + " (chain=" + target.chain + ")");

            JSONObject out = new JSONObject();
            out.put("switched", true);
            out.put("previous_wallet_id", oldId);
            out.put("wallet_id", walletId);
            out.put("wallet_name", target.name);
            out.put("chain", target.chain);
            out.put("address", target.getShortAddress());
            out.put("type", target.getTypeLabel());
            out.put("is_watch_only", target.isWatchOnly());
            out.put("note", "已切换钱包，请调用 get_wallet_assets 获取新钱包的资产数据");
            return ToolResult.success(out.toString());
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "switch_wallet 失败: " + e.getMessage(), e);
            return ToolResult.error("切换钱包失败: " + e.getMessage());
        }
    }

    private static ToolResult executeOpenCreateWallet(Context ctx, JSONObject args) {
        try {
            String chain = args.optString("chain", "ETH").toUpperCase();
            // 兜底：如果传入的链不在内置链，也允许用户尝试创建，由 MainActivity 处理
            boolean supported = true;
            String color = ChainAPI.getChainColor(chain);
            if (color == null || color.isEmpty()) {
                supported = false;
            }

            JSONObject out = new JSONObject();
            out.put("chain", chain);
            out.put("supported", supported);
            out.put("action", "OPEN_CREATE_WALLET");
            out.put("note", "AI 无法直接创建钱包，请在 App 弹出的创建页面完成操作");
            Logger.info(ctx, "Agent工具", "open_create_wallet: chain=" + chain + " supported=" + supported);
            return ToolResult.success(out.toString());
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "open_create_wallet 失败: " + e.getMessage(), e);
            return ToolResult.error("打开创建钱包失败: " + e.getMessage());
        }
    }

    // ============================================================
    // DApp 白名单管理工具
    // ============================================================

    private static ToolResult executeQueryDAppWhitelist(Context ctx) {
        try {
            DAppWhitelistManager manager = new DAppWhitelistManager(ctx);
            JSONObject out = new JSONObject();
            out.put("entries", manager.getAllEntriesJson());
            out.put("count", manager.getAllEntriesJson().length());
            return ToolResult.success(out.toString());
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "query_dapp_whitelist 失败: " + e.getMessage(), e);
            return ToolResult.error("查询 DApp 白名单失败: " + e.getMessage());
        }
    }

    private static ToolResult executeRequestDAppWhitelist(Context ctx, JSONObject args) {
        try {
            String domain = args.getString("domain");
            double dailyCap = args.optDouble("daily_cap_usd", 100);
            double perTxCap = args.optDouble("per_tx_cap_usd", 10);
            boolean allowClick = args.optBoolean("allow_click", true);
            boolean allowInput = args.optBoolean("allow_input", true);
            boolean allowEvaluate = args.optBoolean("allow_evaluate", true);
            boolean allowTransaction = args.optBoolean("allow_transaction", true);

            domain = DAppWhitelistManager.normalizeDomain(domain);
            if (domain.isEmpty()) {
                return ToolResult.error("域名无效");
            }

            String question = "AI 申请将以下 DApp 加入自动操作白名单：\n\n" +
                "域名：" + domain + "\n" +
                "允许操作：自动点击、自动输入、执行 JS" +
                (allowTransaction ? "、自动交易" : "") + "\n" +
                "每日额度上限：$" + dailyCap + "\n" +
                "单笔交易额度上限：$" + perTxCap + "\n\n" +
                "确认后，AI 可在该 DApp 内自动执行上述操作（交易受额度限制）。是否同意？\n\n" +
                "【免责声明】将链接加入白名单即视为您自主承担全部风险。AI 无法保证该链接、DApp 或其智能合约的安全性，因您主动将该链接加入白名单、绕过 AI 安全检测而导致的任何资产损失，红魔团队概不负责。";

            // 优先尝试在 DApp 浏览器页面弹出授权按钮（已授权悬浮窗时后台也能弹）
            DAppBrowserActivity.WhitelistDialogResult uiResult =
                DAppBrowserActivity.requestWhitelistFromUI(ctx, domain, question);

            boolean agreed;
            if (uiResult != null && uiResult.responded) {
                agreed = uiResult.allowed;
                Logger.info(ctx, "Agent工具", "DApp浏览器弹窗授权结果: domain=" + domain
                    + " allowed=" + agreed);
            } else {
                // DApp 浏览器不在前台，回退到 AI 聊天 ask_user
                Logger.info(ctx, "Agent工具", "DApp浏览器未打开，回退到 ask_user: domain=" + domain);
                String userReply = askUserSync(ctx, question, "DApp 白名单授权申请", "high");
                if (userReply == null) {
                    return ToolResult.error("用户未在线，无法申请白名单");
                }
                String replyLower = userReply.trim().toLowerCase();
                agreed = replyLower.contains("同意") || replyLower.contains("确认")
                    || replyLower.contains("是的") || replyLower.contains("允许")
                    || replyLower.contains("好") || replyLower.equals("是");
            }

            if (!agreed) {
                return ToolResult.error("用户拒绝将 " + domain + " 加入白名单");
            }

            DAppWhitelistManager.Entry entry = new DAppWhitelistManager.Entry();
            entry.domain = domain;
            entry.allowClick = allowClick;
            entry.allowInput = allowInput;
            entry.allowEvaluate = allowEvaluate;
            entry.allowTransaction = allowTransaction;
            entry.dailyCapUsd = new java.math.BigDecimal(String.valueOf(dailyCap));
            entry.perTxCapUsd = new java.math.BigDecimal(String.valueOf(perTxCap));
            entry.addedAt = System.currentTimeMillis();
            entry.riskConfirmed = "用户已确认将 " + domain + " 加入 AI 自动操作白名单";

            DAppWhitelistManager manager = new DAppWhitelistManager(ctx);
            manager.putEntry(entry);

            Logger.action(ctx, "DApp白名单", "AI申请授权 domain=" + domain
                + " daily=" + dailyCap + " perTx=" + perTxCap, null);

            JSONObject out = new JSONObject();
            out.put("domain", domain);
            out.put("daily_cap_usd", dailyCap);
            out.put("per_tx_cap_usd", perTxCap);
            out.put("allow_click", allowClick);
            out.put("allow_input", allowInput);
            out.put("allow_evaluate", allowEvaluate);
            out.put("allow_transaction", allowTransaction);
            out.put("status", "whitelisted");
            return ToolResult.success(out.toString());
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "request_dapp_whitelist 失败: " + e.getMessage(), e);
            return ToolResult.error("申请 DApp 白名单失败: " + e.getMessage());
        }
    }

    private static ToolResult executeRemoveDAppWhitelist(Context ctx, JSONObject args) {
        try {
            String domain = args.getString("domain");
            domain = DAppWhitelistManager.normalizeDomain(domain);
            if (domain.isEmpty()) {
                return ToolResult.error("域名无效");
            }

            DAppWhitelistManager manager = new DAppWhitelistManager(ctx);
            if (!manager.isWhitelisted(domain)) {
                return ToolResult.error("该域名不在白名单中");
            }
            manager.remove(domain);

            Logger.action(ctx, "DApp白名单", "移除 domain=" + domain, null);
            AINotificationHelper.notifyOperation(ctx, "AI 操作记录",
                "已将 " + domain + " 从 DApp 白名单中移除");

            JSONObject out = new JSONObject();
            out.put("domain", domain);
            out.put("status", "removed");
            return ToolResult.success(out.toString());
        } catch (Exception e) {
            Logger.error(ctx, "Agent工具", "remove_dapp_whitelist 失败: " + e.getMessage(), e);
            return ToolResult.error("移除 DApp 白名单失败: " + e.getMessage());
        }
    }
}
