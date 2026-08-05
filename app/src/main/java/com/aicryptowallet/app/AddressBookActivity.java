package com.aicryptowallet.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;

/**
 * 钱包通讯录 - 添加/选择/删除联系人
 *
 * 调用方式：
 *  - startActivityForResult(new Intent(this, AddressBookActivity.class), REQUEST_CODE)
 *  - 选择联系人后返回：
 *      intent.getStringExtra("address")
 *      intent.getStringExtra("name")
 */
public class AddressBookActivity extends BaseActivity {

    public static final String EXTRA_ADDRESS = "address";
    public static final String EXTRA_NAME = "name";

    private AddressBookManager manager;
    private LinearLayout contactListContainer;
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_address_book);
            manager = new AddressBookManager(this);

            findViewById(R.id.btnBack).setOnClickListener(v -> finish());
            findViewById(R.id.btnAddContact).setOnClickListener(v -> showAddContactDialog());

            contactListContainer = findViewById(R.id.contactListContainer);
            tvEmpty = findViewById(R.id.tvEmpty);

            renderContacts();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_failed_to_open_address, e.getMessage()), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void renderContacts() {
        if (contactListContainer == null) return;
        // 保留 tvEmpty，清掉其它 item
        contactListContainer.removeViews(1, Math.max(0, contactListContainer.getChildCount() - 1));

        List<AddressBookManager.Contact> list = manager.loadAll();
        if (list.isEmpty()) {
            if (tvEmpty != null) tvEmpty.setVisibility(View.VISIBLE);
            return;
        }
        if (tvEmpty != null) tvEmpty.setVisibility(View.GONE);

        String currentChain = WalletManager.getChain(this);

        for (AddressBookManager.Contact c : list) {
            View item = getLayoutInflater().inflate(R.layout.item_address_contact, null);

            TextView tvName = item.findViewById(R.id.tvContactName);
            TextView tvAddress = item.findViewById(R.id.tvContactAddress);
            TextView tvChain = item.findViewById(R.id.tvContactChain);
            TextView btnDelete = item.findViewById(R.id.btnDeleteContact);

            tvName.setText(c.name == null || c.name.isEmpty() ? getString(R.string.label_unnamed) : c.name);

            String shortAddr = c.address.length() > 14
                ? c.address.substring(0, 8) + "..." + c.address.substring(c.address.length() - 6)
                : c.address;
            tvAddress.setText(shortAddr);

            String chainLabel = (c.chain == null || c.chain.isEmpty())
                ? "通用" : c.chain;
            tvChain.setText("[" + chainLabel + "]");

            // 点击 = 选择联系人返回
            item.setOnClickListener(v -> {
                Intent data = new Intent();
                data.putExtra(EXTRA_ADDRESS, c.address);
                data.putExtra(EXTRA_NAME, c.name);
                setResult(Activity.RESULT_OK, data);
                finish();
            });

            // 长按 = 复制地址
            item.setOnLongClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("address", c.address));
                    Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show();
                }
                return true;
            });

            btnDelete.setOnClickListener(v -> {
                new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                    .setTitle(getString(R.string.title_remove_contact))
                    .setMessage(getString(R.string.msg_confirm_delete, (c.name == null || c.name.isEmpty() ? c.address : c.name)))
                    .setPositiveButton(getString(R.string.text_delete), (d, w) -> {
                        manager.delete(c.address);
                        Toast.makeText(this, getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show();
                        renderContacts();
                    })
                    .setNegativeButton(getString(R.string.btn_s_decline), null)
                    .show();
            });

            contactListContainer.addView(item);
        }
    }

    private void showAddContactDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 32, 48, 16);

        final EditText etName = new EditText(this);
        etName.setHint(getString(R.string.hint_name_ex_zhang_san));
        etName.setTextColor(0xFFFFFFFF);
        etName.setHintTextColor(0xFF4a4a6a);
        etName.setTextSize(14);
        etName.setSingleLine(true);
        root.addView(etName);

        final EditText etAddress = new EditText(this);
        etAddress.setHint(getString(R.string.hint_wallet_address_0x_adaptive));
        etAddress.setTextColor(0xFFFFFFFF);
        etAddress.setHintTextColor(0xFF4a4a6a);
        etAddress.setTextSize(14);
        etAddress.setSingleLine(true);
        LinearLayout.LayoutParams addrLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        addrLp.topMargin = 24;
        etAddress.setLayoutParams(addrLp);
        root.addView(etAddress);

        final EditText etMemo = new EditText(this);
        etMemo.setHint(getString(R.string.hint_comments_optional));
        etMemo.setTextColor(0xFFFFFFFF);
        etMemo.setHintTextColor(0xFF4a4a6a);
        etMemo.setTextSize(14);
        etMemo.setSingleLine(true);
        LinearLayout.LayoutParams memoLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        memoLp.topMargin = 24;
        etMemo.setLayoutParams(memoLp);
        root.addView(etMemo);

        // 粘贴按钮
        TextView btnPaste = new TextView(this);
        btnPaste.setText(getString(R.string.text_coller_adresse));
        btnPaste.setTextColor(0xFF667eea);
        btnPaste.setTextSize(13);
        btnPaste.setPadding(0, 24, 0, 0);
        btnPaste.setOnClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip()) {
                    String text = cm.getPrimaryClip().getItemAt(0).getText().toString().trim();
                    etAddress.setText(text);
                }
            } catch (Exception ignored) {}
        });
        root.addView(btnPaste);

        new AlertDialog.Builder(this, R.style.AlertDialogCustom)
            .setTitle(getString(R.string.title_add_contact))
            .setView(root)
            .setPositiveButton(getString(R.string.btn_saving), (d, w) -> {
                String name = etName.getText().toString().trim();
                String addr = etAddress.getText().toString().trim();
                String memo = etMemo.getText().toString().trim();
                if (addr.isEmpty()) {
                    Toast.makeText(this, getString(R.string.toast_you_must_enter_the), Toast.LENGTH_SHORT).show();
                    return;
                }
                AddressBookManager.Contact contact = new AddressBookManager.Contact(
                    name, addr, WalletManager.getChain(this), memo);
                if (manager.add(contact)) {
                    Toast.makeText(this, getString(R.string.toast_was_added), Toast.LENGTH_SHORT).show();
                    renderContacts();
                } else {
                    Toast.makeText(this, getString(R.string.toast_this_address_already_exists), Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton(getString(R.string.btn_s_decline), null)
            .show();
    }
}