package com.aicryptowallet.app;

import java.util.HashMap;
import java.util.Map;

/**
 * 主流币种介绍本地数据库
 *
 * 设计原则：
 *  - 不依赖 CoinGecko 等海外 API（国内常被 GFW 阻断）
 *  - 仅本地 hardcode 前 30 个主流币种，覆盖 HomeActivity.TARGET_PAIRS 所有交易对
 *  - 数据来自项目公开常识（白皮书、官网、发行年份等均为公开信息）
 *  - 非主流币种返回 null，详情页会展示"暂无介绍"
 *
 * 数据结构：每条记录包含 symbol, fullName, description, website, whitepaper, explorer, foundedYear, maxSupply
 */
public class CoinInfo {

    public static class Info {
        public final String symbol;
        public final String fullName;
        public final String description;
        public final String website;
        public final String whitepaper;
        public final String explorer;
        public final String foundedYear;
        public final String maxSupply;

        public Info(String s, String fn, String d, String w, String wp, String e, String y, String ms) {
            symbol = s; fullName = fn; description = d; website = w;
            whitepaper = wp; explorer = e; foundedYear = y; maxSupply = ms;
        }
    }

    private static final Map<String, Info> DATA = new HashMap<>();

    static {
        DATA.put("BTC", new Info("BTC", "Bitcoin 比特币",
            "比特币是世界上第一个去中心化加密货币，由中本聪（Satoshi Nakamoto）于 2008 年提出、2009 年正式上线。基于区块链技术，通过工作量证明（PoW）共识机制维护网络安全，总量恒定 2100 万枚，被誉为「数字黄金」。比特币网络无需中心化机构即可实现点对点价值传输，是全球市值最大的加密资产。",
            "https://bitcoin.org", "https://bitcoin.org/bitcoin.pdf",
            "https://blockchain.com/explorer/assets/btc", "2009", "2100万枚"));

        DATA.put("ETH", new Info("ETH", "Ethereum 以太坊",
            "以太坊是图灵完备的智能合约区块链平台，由 Vitalik Buterin 于 2013 年提出、2015 年主网上线。原生代币 ETH 用于支付 Gas 费和参与 PoS 质押。2022 年 9 月完成合并（The Merge）从 PoW 切换至 PoS，能耗降低 99.95%。以太坊是 DeFi、NFT、Layer2、RWA 等核心应用生态的发源地，是全球最大的智能合约平台。",
            "https://ethereum.org", "https://ethereum.org/en/whitepaper/",
            "https://etherscan.io", "2015", "无上限"));

        DATA.put("BNB", new Info("BNB", "BNB Chain 币安币",
            "BNB 是币安生态系统的核心代币，最初在以太坊上作为 ERC-20 代币发行，2019 年迁移至币安智能链（BSC），采用 BEP-20 标准。BSC 兼容 EVM，采用 PoSA 共识，出块速度约 3 秒，Gas 费用低廉。BNB 用于支付交易手续费、参与 Launchpad、Gas 折扣等。BNB Chain 是全球最大的 EVM 生态之一，DeFi、GameFi 应用丰富。",
            "https://www.bnbchain.org", "https://www.bnbchain.org/en/whitepaper",
            "https://bscscan.com", "2019", "2亿枚(定期销毁)"));

        DATA.put("SOL", new Info("SOL", "Solana 索拉纳",
            "Solana 是高性能区块链，采用 PoH（历史证明）+ PoS 混合共识，理论 TPS 65,000+，出块时间 400ms。原生代币 SOL 用于支付交易费、质押、治理。Solana 凭借高吞吐、低费用成为 meme 币、DePIN、NFT 主战场。2024 年推出的 Firedancer 客户端进一步提升性能。",
            "https://solana.com", "https://solana.com/solana-whitepaper.pdf",
            "https://solscan.io", "2020", "无上限"));

        DATA.put("TRX", new Info("TRX", "TRON 波场",
            "TRON 是由孙宇晨于 2017 年创立的高吞吐量区块链平台，采用 DPoS 共识，TPS 2000+。原生代币 TRX 用于支付交易费、质押获取能量与带宽。TRON 网络稳定币 USDT 流通量长期居首，是亚洲跨境支付重要通道。2021 年收购 BitTorrent，生态扩展至去中心化存储。",
            "https://tron.network", "https://tron.network/static/doc/white_paper_v_2_0.pdf",
            "https://tronscan.org", "2018", "无上限"));

        DATA.put("AVAX", new Info("AVAX", "Avalanche 雪崩",
            "Avalanche 是由 Emin Gün Sirer 团队开发的高性能区块链，采用独创的 Snowman++ 共识，TPS 4500+，最终性 <1 秒。原生代币 AVAX 用于支付交易费、质押、子网创建。Avalanche 三链架构（X/P/C 链）支持资产交易、智能合约、子网定制，是 RWA 和机构资产代币化的重要平台。",
            "https://www.avalabs.org", "https://www.avalabs.org/whitepapers",
            "https://snowtrace.io", "2020", "720亿枚"));

        DATA.put("SUI", new Info("SUI", "Sui 苏伊",
            "Sui 是由 Mysten Labs（前 Facebook Diem 团队）开发的 Move 语言 Layer1，采用 Narwhal & Bullshark 共识，并行处理使 TPS 高达 297,000。原生代币 SUI 用于支付 Gas、质押。Sui 的对象中心模型适合 GameFi、NFT 等需要复杂资产逻辑的应用。",
            "https://sui.io", "https://github.com/MystenLabs/sui/blob/main/doc/paper/sui.pdf",
            "https://suiscan.xyz", "2023", "100亿枚"));

        DATA.put("APT", new Info("APT", "Aptos",
            "Aptos 是由前 Facebook Diem 团队创立的 Move 语言 Layer1，采用 AptosBFT 共识，理论 TPS 160,000+。原生代币 APT 用于支付交易费、质押、治理。Aptos 并行执行引擎（Block-STM）显著提升吞吐，是 Move 系生态代表项目之一。",
            "https://aptosfoundation.org", "https://aptosfoundation.org/whitepaper",
            "https://aptoscan.com", "2022", "无上限"));

        DATA.put("ADA", new Info("ADA", "Cardano 卡尔达诺",
            "Cardano 是由 IOHK（Charles Hoskinson 创立）开发的 PoS 区块链，采用 Ouroboros 共识，强调学术同行评审开发模式。原生代币 ADA 用于质押、治理。Cardano 的分层架构（结算层+计算层）提升安全性，是非洲地区普惠金融重要基础设施。",
            "https://cardano.org", "https://cardano.org/whitepapers/",
            "https://cardanoscan.io", "2017", "450亿枚"));

        DATA.put("MATIC", new Info("MATIC", "Polygon 波卡",
            "Polygon 是以太坊领先的 Layer2 扩容方案，原品牌名 Matic Network。原生代币 MATIC（已更名为 POL）用于支付 Gas、质押、治理。Polygon PoS 链是 EVM 兼容侧链，2024 年升级为 zkEVM Validium，进一步降低费用。Polygon 是 Web2 企业进入 Web3 的首选平台之一（星巴克、耐克等）。",
            "https://polygon.technology", "https://polygon.technology/papers",
            "https://polygonscan.com", "2019", "100亿枚"));

        DATA.put("NEAR", new Info("NEAR", "NEAR Protocol",
            "NEAR 是由 Illia Polosukhin（前 Google AI）开发的分片 Layer1，采用 Nightshade 动态分片，理论 TPS 100,000+。原生代币 NEAR 用于支付 Gas、质押。NEAR 的 Doomslug 共识兼顾最终性与安全性，账户命名（如 alice.near）对用户友好，AI + Crypto 是其重点方向。",
            "https://near.org", "https://near.org/papers/the-official-near-white-paper",
            "https://nearblocks.io", "2020", "10亿枚"));

        DATA.put("FTM", new Info("FTM", "Fantom 索拉纳",
            "Fantom 是基于 aBFT（异步拜占庭容错）共识的高性能 Layer1，由韩国开发者 Byung Ik Ahn 创立，2019 年主网上线。原生代币 FTM 用于支付 Gas、质押。2023 年升级为 Sonic（S）网络，TPS 提升至 2000+，最终性 1.1 秒。Fantom 在 DeFi 领域以低费用、高速度著称。",
            "https://fantom.foundation", "https://fantom.foundation/whitepaper.pdf",
            "https://ftmscan.com", "2019", "31.7亿枚"));

        DATA.put("ATOM", new Info("ATOM", "Cosmos 宇宙",
            "Cosmos 是「区块链互联网」，通过 IBC（跨链通信协议）实现异构链互联互通。原生代币 ATOM 用于质押、治理。Cosmos SDK 是构建应用链的主流框架，dYdX、Celestia、Injective 等均基于 Cosmos 构建。Cosmos Hub 是生态中心，2024 年推出链间安全（Interchain Security）。",
            "https://cosmos.network", "https://cosmos.network/resources/whitepaper",
            "https://www.mintscan.io/cosmos", "2019", "无上限"));

        DATA.put("DOT", new Info("DOT", "Polkadot 波卡",
            "Polkadot 是由 Gavin Wood（以太坊联合创始人）创立的跨链协议，采用中继链+平行链架构。原生代币 DOT 用于质押、治理、平行链插槽拍卖。Polkadot 2.0 引入敏捷核心时间（Agile Coretime），降低平行链接入成本。Polkadot 是 Web3 基金会重点支持项目。",
            "https://polkadot.network", "https://polkadot.network/Polkadot-lightpaper.pdf",
            "https://polkadot.subscan.io", "2020", "无上限"));

        DATA.put("ARB", new Info("ARB", "Arbitrum 仲裁",
            "Arbitrum 是 Offchain Labs 开发的以太坊 Layer2，采用 Optimistic Rollup 技术，EVM 完全兼容。原生代币 ARB 用于治理。Arbitrum One 是 TVL 最大的 Rollup，GMX、Camelot、Pendle 等头部 DeFi 项目均部署其上。2024 年推出 Arbitrum Orbit 用于构建 L3 应用链。",
            "https://arbitrum.foundation", "https://docs.arbitrum.io",
            "https://arbiscan.io", "2021", "100亿枚"));

        DATA.put("CORE", new Info("CORE", "Core 区块链",
            "Core 是由 Core DAO 开发的 Satoshi Plus 共识 Layer1，结合比特币 PoW 算力与以太坊 EVM 兼容性。原生代币 CORE 用于支付 Gas、质押。Core 通过委托比特币算力参与网络共识，是比特币生态扩展的重要尝试。",
            "https://coredao.org", "https://docs.coredao.org",
            "https://scan.coredao.org", "2023", "21亿枚"));

        DATA.put("OP", new Info("OP", "Optimism 乐观",
            "Optimism 是以太坊领先的 Optimistic Rollup Layer2，由 OP Labs 开发。原生代币 OP 用于治理。Optimism 推出 OP Stack 开源框架，被 Base、Worldcoin 等采用，形成 Superchain 生态。2024 年引入故障证明（Fault Proof）进一步去中心化。",
            "https://www.optimism.io", "https://stack.optimism.io",
            "https://optimistic.etherscan.io", "2021", "42.8亿枚"));

        DATA.put("LINK", new Info("LINK", "Chainlink 链联",
            "Chainlink 是去中心化预言机网络龙头，由 Sergey Nazarov 创立。原生代币 LINK 用于支付节点服务费、质押。Chainlink 为智能合约提供价格、天气、事件等链下数据，CCIP（跨链互操作协议）是机构资产跨链标准。2024 年推出 BUILD 计划与 SCALE 计划赋能生态项目。",
            "https://chain.link", "https://chain.link/whitepaper",
            "https://etherscan.io/token/0x514910771af9ca656af840dff83e8264ecf986ca", "2017", "10亿枚"));

        DATA.put("DOGE", new Info("DOGE", "Dogecoin 狗狗币",
            "Dogecoin 是由 Billy Markus 和 Jackson Palmer 于 2013 年创立的迷因币，灵感来自柴犬表情包。采用 Scrypt PoW 共识，出块时间 1 分钟，总量无上限。因 Elon Musk 多次提及而名声大噪，是支付小费、打赏的常用币种。社区文化以「Do Only Good Everyday」为宗旨。",
            "https://dogecoin.com", "https://github.com/dogecoin/dogecoin",
            "https://blockchair.com/dogecoin", "2013", "无上限"));

        DATA.put("SHIB", new Info("SHIB", "Shiba Inu 柴犬币",
            "Shiba Inu 是 2020 年发行的以太坊 ERC-20 迷因币，匿名创始人 Ryoshi。从迷因起家，逐步扩展为完整生态：ShibaSwap DEX、SHIBARIUM Layer2、SHIBOSHIS NFT、Sheboshis 等。代币经济学包含销毁机制，长期通缩。社区「ShibArmy」活跃度极高。",
            "https://shibatoken.com", "https://shibatoken.com/woofpaper.pdf",
            "https://etherscan.io/token/0x95ad61b0a150d79219dcf64e1e6cc01f0b64c4ce", "2020", "1000万亿枚"));
    }

    /** 获取币种信息，不存在返回 null */
    public static Info get(String symbol) {
        if (symbol == null) return null;
        return DATA.get(symbol.toUpperCase());
    }

    /** 判断是否为已知币种 */
    public static boolean isKnown(String symbol) {
        return symbol != null && DATA.containsKey(symbol.toUpperCase());
    }
}
