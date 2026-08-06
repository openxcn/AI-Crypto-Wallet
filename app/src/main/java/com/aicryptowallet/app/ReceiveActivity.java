package com.aicryptowallet.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.HashMap;
import java.util.Map;

public class ReceiveActivity extends BaseActivity {

    private static final String DOWNLOAD_URL = "https://github.com/openxcn/AI-Crypto-Wallet/releases/latest/download/AICryptoWallet-latest-release.apk";

    private ImageView ivQrCode;
    private ImageView ivDownloadQr;
    private Bitmap qrBitmap;
    private String address;
    private String chainName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receive);

        String chain = getIntent().getStringExtra("chain");
        if (chain == null || chain.isEmpty()) {
            chain = WalletManager.getChain(this);
        }

        String walletName = WalletManager.getWalletName(this);
        chainName = ChainAPI.getChainName(chain);
        address = WalletManager.getWalletAddress(this);

        ivQrCode = findViewById(R.id.ivQrCode);
        ivDownloadQr = findViewById(R.id.ivDownloadQr);
        TextView tvWalletName = findViewById(R.id.tvWalletName);
        TextView tvChainName = findViewById(R.id.tvChainName);
        TextView tvAddress = findViewById(R.id.tvAddress);
        TextView tvWarning = findViewById(R.id.tvWarning);
        View btnBack = findViewById(R.id.btnBack);
        View btnCopyAddress = findViewById(R.id.btnCopyAddress);
        View btnSaveQr = findViewById(R.id.btnSaveQr);

        tvWalletName.setText(walletName);
        tvChainName.setText(chainName);
        // 一排显示地址，中间省略
        tvAddress.setText(address);
        tvWarning.setText(getString(R.string.text_please_confirm_that_the, chainName));

        // 点击地址也可复制
        tvAddress.setOnClickListener(v -> copyAddress());

        generateQrCode(address);
        generateDownloadQr();

        btnBack.setOnClickListener(v -> finish());
        btnCopyAddress.setOnClickListener(v -> copyAddress());
        btnSaveQr.setOnClickListener(v -> saveQrToGallery());
    }

    private void copyAddress() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("address", address));
        Toast.makeText(this, getString(R.string.toast_copied, "地址"), Toast.LENGTH_SHORT).show();
    }

    private void generateQrCode(String content) {
        int size = dpToPx(320);
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        hints.put(EncodeHintType.MARGIN, 1);

        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap raw = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    raw.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }

            // 白色底 QR 图
            int padding = dpToPx(14);
            int total = size + padding * 2;
            Bitmap qrWithBg = Bitmap.createBitmap(total, total, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(qrWithBg);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(raw, padding, padding, null);

            ivQrCode.setImageBitmap(qrWithBg);
            qrBitmap = qrWithBg;
        } catch (WriterException e) {
            e.printStackTrace();
        }
    }

    private void generateDownloadQr() {
        try {
            int size = dpToPx(112);
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
            hints.put(EncodeHintType.MARGIN, 1);

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(DOWNLOAD_URL, BarcodeFormat.QR_CODE, size, size, hints);
            int w = bitMatrix.getWidth();
            int h = bitMatrix.getHeight();
            Bitmap raw = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    raw.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }

            int padding = dpToPx(4);
            int total = size + padding * 2;
            Bitmap result = Bitmap.createBitmap(total, total, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(raw, padding, padding, null);

            ivDownloadQr.setImageBitmap(result);
        } catch (WriterException e) {
            e.printStackTrace();
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    private void saveQrToGallery() {
        if (qrBitmap == null) {
            Toast.makeText(this, getString(R.string.toast_qr_code_generation_failed), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String result = MediaStore.Images.Media.insertImage(
                getContentResolver(), qrBitmap, "AICryptoWallet_QR_" + System.currentTimeMillis(), "收款二维码");
            if (result != null) {
                Toast.makeText(this, getString(R.string.toast_saved_to_album), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getString(R.string.toast_failed_to_save), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, getString(R.string.toast_failed_to_save_2, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }
}
