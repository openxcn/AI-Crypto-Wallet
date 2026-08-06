package com.aicryptowallet.app;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VerifyMnemonicActivity extends BaseActivity {

    private String[] originalWords;
    private String walletName;
    private String password;
    private String address;
    private String chain;

    private LinearLayout inputGrid;
    private LinearLayout candidateGrid;
    private TextView[] inputSlots;
    private TextView[] candidateButtons;
    private String[] selectedWords;
    private boolean[] candidateUsed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_verify_mnemonic);

        String mnemonic = getIntent().getStringExtra("mnemonic");
        walletName = getIntent().getStringExtra("walletName");
        password = getIntent().getStringExtra("password");
        address = getIntent().getStringExtra("address");
        chain = getIntent().getStringExtra("chain");

        if (mnemonic == null) {
            Toast.makeText(this, getString(R.string.toast_mnemonic_data_missing), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        originalWords = mnemonic.trim().split("\\s+");
        int count = originalWords.length;
        selectedWords = new String[count];
        candidateUsed = new boolean[count];

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        inputGrid = findViewById(R.id.inputGrid);
        candidateGrid = findViewById(R.id.candidateGrid);

        renderInputSlots(count);
        renderCandidateButtons(count);

        findViewById(R.id.btnVerifyConfirm).setOnClickListener(v -> doVerify());
    }

    private void renderInputSlots(int count) {
        int cols = 3;
        int rows = (count + cols - 1) / cols;
        inputSlots = new TextView[count];

        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(8);
            row.setLayoutParams(rowParams);

            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                if (idx >= count) break;

                final int pos = idx;

                TextView slot = new TextView(this);
                slot.setText((idx + 1) + "");
                slot.setTextColor(Color.parseColor("#6b7280"));
                slot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                slot.setGravity(Gravity.CENTER_VERTICAL);
                slot.setTypeface(Typeface.DEFAULT);
                slot.setBackground(getDrawable(R.drawable.mnemonic_word_bg));
                slot.setPadding(dp(12), 0, dp(12), 0);
                LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(0, dp(48), 1);
                if (c < cols - 1) slotParams.rightMargin = dp(8);
                slot.setLayoutParams(slotParams);
                slot.setTag(pos);

                slot.setOnClickListener(view -> {
                    int p = (int) view.getTag();
                    if (selectedWords[p] != null) {
                        selectedWords[p] = null;
                        TextView tv = (TextView) view;
                        tv.setText((p + 1) + "");
                        tv.setTextColor(Color.parseColor("#6b7280"));
                        for (int i = 0; i < candidateButtons.length; i++) {
                            if (candidateUsed[i] && candidateButtons[i].getText().toString().equals(originalWords[p])) {
                                candidateUsed[i] = false;
                                candidateButtons[i].setAlpha(1.0f);
                                candidateButtons[i].setEnabled(true);
                                break;
                            }
                        }
                    }
                });

                row.addView(slot);
                inputSlots[idx] = slot;
            }
            inputGrid.addView(row);
        }
    }

    private void renderCandidateButtons(int count) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < count; i++) indices.add(i);
        Collections.shuffle(indices);

        int cols = 4;
        int rows = (count + cols - 1) / cols;
        candidateButtons = new TextView[count];

        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(8);
            row.setLayoutParams(rowParams);

            for (int c = 0; c < cols; c++) {
                int slotIdx = r * cols + c;
                if (slotIdx >= count) break;

                final int wordIdx = indices.get(slotIdx);
                TextView btn = new TextView(this);
                btn.setText(originalWords[wordIdx]);
                btn.setTextColor(Color.parseColor("#ffffff"));
                btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                btn.setGravity(Gravity.CENTER);
                btn.setBackground(getDrawable(R.drawable.mnemonic_word_bg));
                btn.setPadding(dp(8), dp(10), dp(8), dp(10));
                LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(0, dp(40), 1);
                if (c < cols - 1) btnParams.rightMargin = dp(8);
                btn.setLayoutParams(btnParams);
                btn.setTag(wordIdx);

                btn.setOnClickListener(view -> {
                    int wi = (int) view.getTag();
                    if (candidateUsed[wi]) return;

                    int nextEmpty = -1;
                    for (int i = 0; i < selectedWords.length; i++) {
                        if (selectedWords[i] == null) {
                            nextEmpty = i;
                            break;
                        }
                    }
                    if (nextEmpty == -1) return;

                    selectedWords[nextEmpty] = originalWords[wi];
                    TextView target = inputSlots[nextEmpty];
                    target.setText(originalWords[wi]);
                    target.setTextColor(Color.parseColor("#ffffff"));
                    candidateUsed[wi] = true;
                    btn.setAlpha(0.3f);
                    btn.setEnabled(false);
                });

                row.addView(btn);
                candidateButtons[slotIdx] = btn;
            }
            candidateGrid.addView(row);
        }
    }

    private void doVerify() {
        for (int i = 0; i < selectedWords.length; i++) {
            if (selectedWords[i] == null) {
                Toast.makeText(this, getString(R.string.toast_please_complete_mnemonic), Toast.LENGTH_SHORT).show();
                return;
            }
        }

        boolean correct = true;
        for (int i = 0; i < originalWords.length; i++) {
            if (!originalWords[i].equals(selectedWords[i])) {
                correct = false;
                break;
            }
        }

        if (correct) {
            // 标记钱包为已备份
            WalletManager.markWalletBackedUp(this, address);
            Toast.makeText(this, getString(R.string.toast_mnemonic_verified_success), Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(this, HomeActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        } else {
            Toast.makeText(this, getString(R.string.toast_mnemonic_verify_failed), Toast.LENGTH_SHORT).show();
            for (int i = 0; i < selectedWords.length; i++) {
                selectedWords[i] = null;
                inputSlots[i].setText((i + 1) + "");
                inputSlots[i].setTextColor(Color.parseColor("#6b7280"));
            }
            for (int i = 0; i < candidateUsed.length; i++) {
                candidateUsed[i] = false;
            }
            for (TextView btn : candidateButtons) {
                if (btn != null) {
                    btn.setAlpha(1.0f);
                    btn.setEnabled(true);
                }
            }
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
            getResources().getDisplayMetrics());
    }
}
