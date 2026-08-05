package com.aicryptowallet.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;

/**
 * AI 模型供应商配置页面
 * - 内置 20 家供应商（境外+国内）
 * - 用户填入 API Key 后加密存储
 * - 选择激活的供应商
 */
public class ModelConfigActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_config);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        loadProviderList();
    }

    private void loadProviderList() {
        LinearLayout list = findViewById(R.id.providerList);
        list.removeAllViews();

        String activeId = ModelProviderManager.getActiveProviderId(this);
        List<ModelProviderManager.ProviderInfo> providers = ModelProviderManager.getConfiguredProviders(this);

        for (ModelProviderManager.ProviderInfo p : providers) {
            View item = getLayoutInflater().inflate(R.layout.item_model_provider, null);

            // 名称
            TextView tvName = item.findViewById(R.id.tvProviderName);
            tvName.setText(p.name);

            // 状态标签
            TextView tvStatus = item.findViewById(R.id.tvProviderStatus);
            if (p.hasApiKey) {
                tvStatus.setText(getString(R.string.text_configured));
                tvStatus.setTextColor(0xFF4ADE80);
                if (p.id.equals(activeId)) {
                    tvStatus.setText(getString(R.string.text_activated));
                    tvStatus.setTextColor(0xFF667EEA);
                }
            } else {
                tvStatus.setText(getString(R.string.text_not_configured));
                tvStatus.setTextColor(0xFF6E6E7A);
            }

            // API Key 信息
            TextView tvKeyInfo = item.findViewById(R.id.tvKeyInfo);
            if (p.hasApiKey) {
                tvKeyInfo.setText(getString(R.string.text_key_model, p.apiKeyMasked, p.selectedModel));
                tvKeyInfo.setVisibility(View.VISIBLE);
            } else {
                tvKeyInfo.setVisibility(View.GONE);
            }

            // 获取 Key 按钮（自定义API没有固定获取地址）
            boolean isCustom = "custom".equals(p.id);
            View btnGetKey = item.findViewById(R.id.btnGetKey);
            if (isCustom) {
                btnGetKey.setVisibility(View.GONE);
            } else {
                btnGetKey.setOnClickListener(v -> {
                    String url = getProviderRegisterUrl(p.id);
                    if (url != null) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        Toast.makeText(this, getString(R.string.toast_please_register_in_your), Toast.LENGTH_LONG).show();
                    }
                });
            }

            // 配置 Key 按钮
            item.findViewById(R.id.btnConfigKey).setOnClickListener(v -> showConfigDialog(p));

            // 激活按钮
            TextView btnActivate = item.findViewById(R.id.btnActivate);
            if (p.hasApiKey) {
                btnActivate.setVisibility(View.VISIBLE);
                if (p.id.equals(activeId)) {
                    btnActivate.setText(getString(R.string.text_activated));
                    btnActivate.setTextColor(0xFF667EEA);
                    btnActivate.setOnClickListener(null);
                } else {
                    btnActivate.setText(getString(R.string.text_aktivasi));
                    btnActivate.setTextColor(0xFF4ADE80);
                    btnActivate.setOnClickListener(v -> {
                        ModelProviderManager.setActiveProvider(this, p.id);
                        Toast.makeText(this, getString(R.string.toast_activated, p.name), Toast.LENGTH_SHORT).show();
                        loadProviderList();
                    });
                }
            } else {
                btnActivate.setVisibility(View.GONE);
            }

            list.addView(item);
        }
    }

    private void showConfigDialog(ModelProviderManager.ProviderInfo p) {
        boolean isCustom = "custom".equals(p.id);

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AlertDialogCustom);
        builder.setTitle(getString(R.string.title_configuration, p.name));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 24, 32, 0);

        // 自定义API：名称输入框
        EditText etName = null;
        if (isCustom) {
            TextView labelName = new TextView(this);
            labelName.setText(getString(R.string.text_name));
            labelName.setTextColor(0xFF8892B0);
            labelName.setTextSize(12);
            layout.addView(labelName);

            etName = new EditText(this);
            etName.setHint(getString(R.string.hint_my_apis));
            etName.setText(p.name);
            etName.setTextColor(0xFFFFFFFF);
            etName.setHintTextColor(0xFF4a4a6a);
            etName.setTextSize(14);
            etName.setPadding(24, 16, 24, 16);
            etName.setBackgroundColor(0xFF1a1a2e);
            etName.setSingleLine(true);
            layout.addView(etName);
        }

        // API Key 输入框
        TextView labelKey = new TextView(this);
        labelKey.setText("API Key");
        labelKey.setTextColor(0xFF8892B0);
        labelKey.setTextSize(12);
        if (isCustom) labelKey.setPadding(0, 24, 0, 0);
        layout.addView(labelKey);

        EditText etKey = new EditText(this);
        etKey.setHint(getString(R.string.label_sk_or_your_api_key));
        etKey.setTextColor(0xFFFFFFFF);
        etKey.setHintTextColor(0xFF4a4a6a);
        etKey.setTextSize(14);
        etKey.setPadding(24, 16, 24, 16);
        etKey.setBackgroundColor(0xFF1a1a2e);
        etKey.setSingleLine(true);
        etKey.setHorizontallyScrolling(true);
        etKey.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        etKey.setMinWidth(dpToPx(280));

        if (p.hasApiKey) {
            etKey.setHint(getString(R.string.hint_configured_leave_blank_to));
        }
        layout.addView(etKey);

        // 模型名称
        TextView labelModel = new TextView(this);
        labelModel.setText(getString(R.string.text_key_model));
        labelModel.setTextColor(0xFF8892B0);
        labelModel.setTextSize(12);
        labelModel.setPadding(0, 24, 0, 0);
        layout.addView(labelModel);

        // 自定义API用输入框，其他用下拉
        EditText etModel = null;
        Spinner spinnerModel = null;
        if (isCustom) {
            etModel = new EditText(this);
            etModel.setHint("gpt-4o / deepseek-chat / ...");
            String currentModel = p.selectedModel != null && !p.selectedModel.isEmpty() ? p.selectedModel : "";
            if (!currentModel.isEmpty()) etModel.setText(currentModel);
            etModel.setTextColor(0xFFFFFFFF);
            etModel.setHintTextColor(0xFF4a4a6a);
            etModel.setTextSize(14);
            etModel.setPadding(24, 16, 24, 16);
            etModel.setBackgroundColor(0xFF1a1a2e);
            etModel.setSingleLine(true);
            layout.addView(etModel);
        } else {
            String[] modelList = ModelProviderManager.getModelList(p.id);
            spinnerModel = new Spinner(this, Spinner.MODE_DROPDOWN);
            ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, modelList);
            spinnerModel.setAdapter(modelAdapter);
            spinnerModel.setBackgroundColor(0xFF1a1a2e);
            spinnerModel.setPadding(24, 8, 24, 8);

            String currentModel = p.selectedModel != null ? p.selectedModel : p.defaultModel;
            for (int i = 0; i < modelList.length; i++) {
                if (modelList[i].equals(currentModel)) {
                    spinnerModel.setSelection(i);
                    break;
                }
            }
            layout.addView(spinnerModel);
        }

        // 接口格式 - 自定义API始终显示，其他按类型
        String[] formatList = isCustom
            ? new String[]{"openai", "anthropic"}
            : ModelProviderManager.getApiFormats(p.id);

        boolean showFormat = isCustom || formatList.length > 1;
        Spinner spinnerFormat = null;

        if (showFormat) {
            TextView labelFormat = new TextView(this);
            labelFormat.setText(getString(R.string.text_interface_format));
            labelFormat.setTextColor(0xFF8892B0);
            labelFormat.setTextSize(12);
            labelFormat.setPadding(0, 24, 0, 0);
            layout.addView(labelFormat);

            String[] formatDisplay = new String[formatList.length];
            for (int i = 0; i < formatList.length; i++) {
                formatDisplay[i] = "anthropic".equals(formatList[i])
                    ? "Anthropic 原生格式"
                    : "OpenAI 兼容格式";
            }

            spinnerFormat = new Spinner(this, Spinner.MODE_DROPDOWN);
            ArrayAdapter<String> formatAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, formatDisplay);
            spinnerFormat.setAdapter(formatAdapter);
            spinnerFormat.setBackgroundColor(0xFF1a1a2e);
            spinnerFormat.setPadding(24, 8, 24, 8);
            spinnerFormat.setTag(formatList);

            String currentFormat = p.selectedFormat != null ? p.selectedFormat : p.type;
            for (int i = 0; i < formatList.length; i++) {
                if (formatList[i].equals(currentFormat)) {
                    spinnerFormat.setSelection(i);
                    break;
                }
            }
            layout.addView(spinnerFormat);
        }

        // API URL
        String[] urlOptions = ModelProviderManager.getUrlOptions(p.id);
        boolean hasUrlOptions = urlOptions != null && urlOptions.length > 1;

        TextView labelUrl = new TextView(this);
        labelUrl.setText(isCustom ? getString(R.string.label_api_address_required) : hasUrlOptions ? getString(R.string.label_api_url) : getString(R.string.label_api_address_advanced_generally_does_not_need_to_be_changed));
        labelUrl.setTextColor(isCustom ? 0xFF4ADE80 : 0xFF8892B0);
        labelUrl.setTextSize(12);
        labelUrl.setPadding(0, 24, 0, 0);
        layout.addView(labelUrl);

        EditText etUrl = null;
        Spinner spinnerUrl = null;

        if (hasUrlOptions) {
            String[] urlDisplay = new String[urlOptions.length];
            for (int i = 0; i < urlOptions.length; i++) {
                if (urlOptions[i].contains("/anthropic")) {
                    urlDisplay[i] = "Anthropic 格式";
                } else {
                    urlDisplay[i] = "OpenAI 格式";
                }
            }

            spinnerUrl = new Spinner(this, Spinner.MODE_DROPDOWN);
            ArrayAdapter<String> urlAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, urlDisplay);
            spinnerUrl.setAdapter(urlAdapter);
            spinnerUrl.setBackgroundColor(0xFF1a1a2e);
            spinnerUrl.setPadding(24, 8, 24, 8);
            spinnerUrl.setTag(urlOptions);

            String currentUrl = ModelProviderManager.getApiUrl(this, p.id);
            for (int i = 0; i < urlOptions.length; i++) {
                if (urlOptions[i].equals(currentUrl)) {
                    spinnerUrl.setSelection(i);
                    break;
                }
            }
            layout.addView(spinnerUrl);
        } else {
            etUrl = new EditText(this);
            etUrl.setHint(isCustom ? "https://your-api.com/v1/chat/completions" : p.apiUrl);
            if (!isCustom && p.hasApiKey) {
                String savedUrl = ModelProviderManager.getApiUrl(this, p.id);
                if (!savedUrl.isEmpty() && !savedUrl.equals(p.apiUrl)) {
                    etUrl.setText(savedUrl);
                    etUrl.setHint(p.apiUrl);
                }
            }
            etUrl.setTextColor(0xFFFFFFFF);
            etUrl.setHintTextColor(0xFF4a4a6a);
            etUrl.setTextSize(11);
            etUrl.setPadding(24, 16, 24, 16);
            etUrl.setBackgroundColor(0xFF1a1a2e);
            etUrl.setSingleLine(true);
            layout.addView(etUrl);
        }

        builder.setView(layout);

        final EditText finalEtName = etName;
        final EditText finalEtModel = etModel;
        final Spinner finalSpinner = spinnerModel;
        final Spinner finalSpinnerFormat = spinnerFormat;
        final Spinner finalSpinnerUrl = spinnerUrl;
        final EditText finalEtUrl = etUrl;
        final String[] finalFormatList = formatList;
        final String[] finalUrlOptions = urlOptions;

        if (p.hasApiKey) {
            builder.setNeutralButton(getString(R.string.btn_delete_configuration), (d, w) -> {
                new AlertDialog.Builder(this, R.style.AlertDialogCustom)
                    .setTitle(getString(R.string.title_confirm_deletion))
                    .setMessage(getString(R.string.msg_delete_the_api_key, p.name))
                    .setPositiveButton(getString(R.string.text_delete), (dd, ww) -> {
                        ModelProviderManager.deleteProvider(this, p.id);
                        Toast.makeText(this, getString(R.string.toast_deleted, p.name), Toast.LENGTH_SHORT).show();
                        loadProviderList();
                    })
                    .setNegativeButton(getString(R.string.btn_s_decline), null)
                    .show();
            });
        }

        builder.setPositiveButton(getString(R.string.btn_saving), (d, w) -> {
            String key = etKey.getText().toString().trim();
            String model;
            if (isCustom && finalEtModel != null) {
                model = finalEtModel.getText().toString().trim();
            } else if (finalSpinner != null) {
                model = (String) finalSpinner.getSelectedItem();
            } else {
                model = "";
            }

            String format = "openai";
            if (finalSpinnerFormat != null && finalFormatList != null) {
                int formatIdx = finalSpinnerFormat.getSelectedItemPosition();
                format = finalFormatList[formatIdx];
            }

            String url;
            if (finalSpinnerUrl != null && finalUrlOptions != null) {
                int urlIdx = finalSpinnerUrl.getSelectedItemPosition();
                url = finalUrlOptions[urlIdx];
            } else if (finalEtUrl != null) {
                url = finalEtUrl.getText().toString().trim();
            } else {
                url = "";
            }

            // 自定义API的URL必填
            if (isCustom && url.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_custom_api_must_have), Toast.LENGTH_SHORT).show();
                return;
            }
            if (isCustom && model.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_please_fill_in_the), Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isCustom && key.isEmpty() && !p.hasApiKey) {
                Toast.makeText(this, getString(R.string.toast_please_fill_in_the), Toast.LENGTH_SHORT).show();
                return;
            }

            // 保存名称
            if (isCustom && finalEtName != null) {
                String name = finalEtName.getText().toString().trim();
                if (!name.isEmpty()) {
                    ModelProviderManager.saveName(this, p.id, name);
                }
            }

            if (!key.isEmpty()) {
                ModelProviderManager.saveApiKey(this, p.id, key);
            }
            if (!model.isEmpty()) {
                ModelProviderManager.saveModel(this, p.id, model);
            }
            ModelProviderManager.saveFormat(this, p.id, format);
            if (!url.isEmpty()) {
                ModelProviderManager.saveApiUrl(this, p.id, url);
            }

            if (!key.isEmpty() || (isCustom && !url.isEmpty())) {
                if (ModelProviderManager.getActiveProviderId(this).isEmpty()) {
                    ModelProviderManager.setActiveProvider(this, p.id);
                }
                Toast.makeText(this, (isCustom && finalEtName != null && !finalEtName.getText().toString().trim().isEmpty()
                    ? finalEtName.getText().toString().trim() : p.name) + getString(R.string.btn_configuration_saved), Toast.LENGTH_SHORT).show();
            } else if (p.hasApiKey) {
                Toast.makeText(this, getString(R.string.toast_configuration_updated), Toast.LENGTH_SHORT).show();
            }
            loadProviderList();
        });
        builder.setNegativeButton(getString(R.string.btn_s_decline), null);
        builder.show();
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    /** 获取供应商注册/API Key 获取地址 */
    private String getProviderRegisterUrl(String providerId) {
        switch (providerId) {
            case "openai": return "https://platform.openai.com/api-keys";
            case "anthropic": return "https://console.anthropic.com/settings/keys";
            case "google": return "https://aistudio.google.com/apikey";
            case "groq": return "https://console.groq.com/keys";
            case "mistral": return "https://console.mistral.ai/api-keys";
            case "openrouter": return "https://openrouter.ai/keys";
            case "together": return "https://api.together.xyz/settings/api-keys";
            case "huggingface": return "https://huggingface.co/settings/tokens";
            case "cohere": return "https://dashboard.cohere.com/api-keys";
            case "replicate": return "https://replicate.com/account/api-tokens";
            case "deepseek": return "https://platform.deepseek.com/api_keys";
            case "zhipu": return "https://open.bigmodel.cn/usercenter/apikeys";
            case "qwen": return "https://bailian.console.aliyun.com/?apiKey=1";
            case "baidu": return "https://console.bce.baidu.com/qianfan/ais/console/applicationConsole/application";
            case "bytedance": return "https://console.volcengine.com/ark/region:ark+cn-beijing/apiKey";
            case "moonshot": return "https://platform.moonshot.cn/console/api-keys";
            case "xfyun": return "https://console.xfyun.cn/services/bm3";
            case "baichuan": return "https://platform.baichuan-ai.com/console/apikey";
            case "minimax": return "https://platform.minimaxi.com/user-center/basic-information/interface-key";
            case "yi": return "https://platform.lingyiwanwu.com/apikeys";
            default: return null;
        }
    }
}