package com.aicryptowallet.app;

/**
 * 交易记录统一结构。
 *
 * 历史原因：交易记录长期用 String[] 传递，不同来源的数组长度不一致
 *（7/8/9 个元素），导致 UI 和解析代码充斥 magic number，极易出错。
 * 本类用于：
 *   1. 定义统一字段常量
 *   2. 提供 String[] <-> TxRecord 转换
 *   3. 避免各处直接用 tx[0]/tx[3]/tx[7] 这种不可维护的写法
 *
 * 字段顺序固定为：
 *   0: hash          交易哈希
 *   1: from          发送方地址
 *   2: to            接收方地址
 *   3: amount        金额字符串（可为空，为空时 UI 显示 --）
 *   4: time          时间字符串
 *   5: status        状态：success / failed / pending
 *   6: type          类型：transfer / contract_call / approval
 *   7: symbol        代币符号（原生币交易也可填 BNB/ETH 等）
 *   8: contract      代币合约地址（原生币为空）
 */
public class TxRecord {

    public static final int INDEX_HASH = 0;
    public static final int INDEX_FROM = 1;
    public static final int INDEX_TO = 2;
    public static final int INDEX_AMOUNT = 3;
    public static final int INDEX_TIME = 4;
    public static final int INDEX_STATUS = 5;
    public static final int INDEX_TYPE = 6;
    public static final int INDEX_SYMBOL = 7;
    public static final int INDEX_CONTRACT = 8;

    public static final int MIN_FIELD_COUNT = 7;
    public static final int FIELD_COUNT = 9;

    public String hash = "";
    public String from = "";
    public String to = "";
    public String amount = "";
    public String time = "";
    public String status = "success";
    public String type = "transfer";
    public String symbol = "";
    public String contract = "";

    public TxRecord() {}

    public TxRecord(String hash, String from, String to, String amount,
                    String time, String status, String type, String symbol, String contract) {
        this.hash = nvl(hash);
        this.from = nvl(from);
        this.to = nvl(to);
        this.amount = nvl(amount);
        this.time = nvl(time);
        this.status = nvl(status, "success");
        this.type = nvl(type, "transfer");
        this.symbol = nvl(symbol);
        this.contract = nvl(contract);
    }

    /** 从旧 String[] 转换（兼容不同长度） */
    public static TxRecord fromArray(String[] tx) {
        TxRecord r = new TxRecord();
        if (tx == null) return r;
        r.hash = get(tx, INDEX_HASH);
        r.from = get(tx, INDEX_FROM);
        r.to = get(tx, INDEX_TO);
        r.amount = get(tx, INDEX_AMOUNT);
        r.time = get(tx, INDEX_TIME);
        r.status = get(tx, INDEX_STATUS, "success");
        r.type = get(tx, INDEX_TYPE, "transfer");
        r.symbol = get(tx, INDEX_SYMBOL);
        r.contract = get(tx, INDEX_CONTRACT);
        return r;
    }

    /** 转换为统一长度的 String[] */
    public String[] toArray() {
        return new String[]{
            hash, from, to, amount, time, status, type, symbol, contract
        };
    }

    /** 兼容性：旧代码期望 8 元素数组时使用 */
    public String[] toLegacyArray() {
        return new String[]{
            hash, from, to, amount, time, status, type, symbol
        };
    }

    public boolean isSend(String myAddress) {
        if (myAddress == null || myAddress.isEmpty()) return false;
        return myAddress.equalsIgnoreCase(from);
    }

    public boolean isReceive(String myAddress) {
        if (myAddress == null || myAddress.isEmpty()) return false;
        return myAddress.equalsIgnoreCase(to);
    }

    public boolean isApproval() {
        return "approval".equals(type);
    }

    public boolean isContractCall() {
        return "contract_call".equals(type);
    }

    public boolean isTransfer() {
        return "transfer".equals(type);
    }

    private static String get(String[] tx, int idx) {
        return get(tx, idx, "");
    }

    private static String get(String[] tx, int idx, String def) {
        if (tx == null || idx < 0 || idx >= tx.length) return def;
        return tx[idx] != null ? tx[idx] : def;
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }

    private static String nvl(String s, String def) {
        return (s != null && !s.isEmpty()) ? s : def;
    }
}
