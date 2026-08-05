package com.aicryptowallet.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.HashMap;
import java.util.Map;

public class ReceiveActivity extends BaseActivity {

    private ImageView ivQrCode;
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
        TextView tvWalletName = findViewById(R.id.tvWalletName);
        TextView tvChainName = findViewById(R.id.tvChainName);
        TextView tvAddress = findViewById(R.id.tvAddress);
        TextView tvWarning = findViewById(R.id.tvWarning);
        View btnBack = findViewById(R.id.btnBack);
        View btnCopyAddress = findViewById(R.id.btnCopyAddress);
        View btnSaveQr = findViewById(R.id.btnSaveQr);

        tvWalletName.setText(walletName);
        tvChainName.setText(chainName);
        tvAddress.setText(formatAddress(address));
        tvWarning.setText(getString(R.string.text_please_confirm_that_the, chainName));

        generateQrCode(address);

        btnBack.setOnClickListener(v -> finish());

        btnCopyAddress.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("address", address));
            Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show();
        });

        btnSaveQr.setOnClickListener(v -> saveQrToGallery());
    }

    private String formatAddress(String addr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < addr.length(); i++) {
            sb.append(addr.charAt(i));
            if ((i + 1) % 10 == 0 && i != addr.length() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private void generateQrCode(String content) {
        int size = dpToPx(240);
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        hints.put(EncodeHintType.MARGIN, 1);

        try {
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            qrBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    qrBitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }

            Bitmap whiteBg = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            whiteBg.eraseColor(Color.WHITE);
            Canvas canvas = new Canvas(whiteBg);
            int padding = dpToPx(12);
            canvas.drawBitmap(qrBitmap, padding, padding, null);
            ivQrCode.setImageBitmap(whiteBg);
            qrBitmap = whiteBg;
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
                getContentResolver(), qrBitmap, "QRCode_" + System.currentTimeMillis(), "收款二维码");
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