package cn.fudan.danzhi;

import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.journeyapps.barcodescanner.CaptureManager;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

public class ScanActivity extends AppCompatActivity {
    private CaptureManager capture;
    private DecoratedBarcodeView barcode;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_scan);
        TextView back = findViewById(R.id.scan_back);
        android.view.View bar = findViewById(R.id.scan_bar);
        android.view.View.OnClickListener leave = v -> {
            setResult(RESULT_CANCELED);
            finish();
        };
        if (back != null) back.setOnClickListener(leave);
        if (bar != null) bar.setOnClickListener(leave);
        barcode = findViewById(R.id.zxing_barcode_scanner);
        capture = new CaptureManager(this, barcode);
        capture.initializeFromIntent(getIntent(), state);
        capture.decode();
    }

    @Override protected void onResume() { super.onResume(); if (capture != null) capture.onResume(); }
    @Override protected void onPause() { if (capture != null) capture.onPause(); super.onPause(); }
    @Override protected void onDestroy() { if (capture != null) capture.onDestroy(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); if (capture != null) capture.onSaveInstanceState(out); }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            setResult(RESULT_CANCELED);
            finish();
            return true;
        }
        return (barcode != null && barcode.onKeyDown(keyCode, event)) || super.onKeyDown(keyCode, event);
    }
    @Override public void onBackPressed() { setResult(RESULT_CANCELED); super.onBackPressed(); }
}
