package com.aicryptowallet.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * 钱包通讯录管理 - SharedPreferences 持久化
 *
 * 数据结构（JSON 数组）：
 * [{name:"张三", address:"0x...", chain:"ETH", memo:"备注"}, ...]
 *
 * 设计原则：
 *  - 按钱包地址隔离（不同钱包有各自的通讯录）
 *  - chain 可空，表示通用联系人；非空表示该链专用
 *  - 同名/同地址不重复添加
 */
public class AddressBookManager {

    private static final String PREFS_NAME = "address_book_prefs";
    private final SharedPreferences prefs;
    private final String walletKey;

    public AddressBookManager(Context ctx) {
        this.prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // 按钱包地址隔离通讯录，切换钱包后看到的是各自的联系人
        String addr = WalletManager.getWalletAddress(ctx);
        this.walletKey = "contacts_" + (addr != null ? addr : "default");
    }

    public static class Contact {
        public String name;
        public String address;
        public String chain; // 可空
        public String memo;  // 可空

        public Contact(String name, String address, String chain, String memo) {
            this.name = name;
            this.address = address;
            this.chain = chain;
            this.memo = memo;
        }
    }

    /** 加载所有联系人 */
    public List<Contact> loadAll() {
        List<Contact> list = new ArrayList<>();
        try {
            String json = prefs.getString(walletKey, "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Contact(
                    o.optString("name", ""),
                    o.optString("address", ""),
                    o.optString("chain", ""),
                    o.optString("memo", "")
                ));
            }
        } catch (Exception e) {
            Logger.error(null, "通讯录", "加载失败: " + e.getMessage(), e);
        }
        return list;
    }

    /** 添加联系人；返回 true 表示成功，false 表示已存在 */
    public boolean add(Contact c) {
        if (c == null || c.address == null || c.address.isEmpty()) return false;
        List<Contact> list = loadAll();
        for (Contact existing : list) {
            if (c.address.equalsIgnoreCase(existing.address)) {
                return false;
            }
        }
        list.add(c);
        save(list);
        return true;
    }

    /** 删除指定地址的联系人 */
    public boolean delete(String address) {
        if (address == null) return false;
        List<Contact> list = loadAll();
        boolean removed = false;
        List<Contact> newList = new ArrayList<>();
        for (Contact c : list) {
            if (c.address.equalsIgnoreCase(address)) {
                removed = true;
            } else {
                newList.add(c);
            }
        }
        if (removed) save(newList);
        return removed;
    }

    private void save(List<Contact> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Contact c : list) {
                JSONObject o = new JSONObject();
                o.put("name", c.name == null ? "" : c.name);
                o.put("address", c.address == null ? "" : c.address);
                o.put("chain", c.chain == null ? "" : c.chain);
                o.put("memo", c.memo == null ? "" : c.memo);
                arr.put(o);
            }
            prefs.edit().putString(walletKey, arr.toString()).apply();
        } catch (Exception e) {
            Logger.error(null, "通讯录", "保存失败: " + e.getMessage(), e);
        }
    }
}
