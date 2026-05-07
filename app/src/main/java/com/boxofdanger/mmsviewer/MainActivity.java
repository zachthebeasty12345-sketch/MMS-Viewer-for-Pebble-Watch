package com.boxofdanger.mmsviewer;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MMSViewer";

    private TextView statusText;
    private Button   permButton;
    private Button   listenerButton;
    private Button   testButton;
    private ImageView previewImage;

    private PebbleBridge pebbleBridge;

    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    results -> updateStatus()
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText     = findViewById(R.id.status_text);
        permButton     = findViewById(R.id.btn_permissions);
        listenerButton = findViewById(R.id.btn_listener);
        testButton     = findViewById(R.id.btn_test);
        previewImage   = findViewById(R.id.preview_image);

        pebbleBridge = new PebbleBridge(this);

        permButton.setOnClickListener(v -> requestPermissions());

        listenerButton.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        });

        testButton.setOnClickListener(v -> sendTestImage());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pebbleBridge != null) {
            pebbleBridge.destroy();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();

        boolean hasSms = ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
        boolean hasContacts = ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;

        sb.append("SMS Permission: ").append(hasSms ? "✓" : "✗").append("\n");
        sb.append("Contacts Permission: ").append(hasContacts ? "✓" : "✗").append("\n");

        boolean listenerEnabled = isListenerEnabled();
        sb.append("Notification Listener: ").append(listenerEnabled ? "✓" : "✗").append("\n");
        sb.append("PebbleKit: v2 (Core Devices)\n");

        statusText.setText(sb.toString());

        // Test button always enabled — PebbleKit2 handles connection internally
        testButton.setEnabled(true);

        permButton.setEnabled(!hasSms || !hasContacts);
        listenerButton.setEnabled(!listenerEnabled);
    }

    private void requestPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.READ_SMS);
        perms.add(Manifest.permission.READ_CONTACTS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        permLauncher.launch(perms.toArray(new String[0]));
    }

    private boolean isListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (TextUtils.isEmpty(flat)) return false;
        ComponentName cn = new ComponentName(this, MmsListenerService.class);
        return flat.contains(cn.flattenToString());
    }

    private void sendTestImage() {
        Toast.makeText(this, "Sending test image...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            int w = 200, h = 200;
            Bitmap testBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[w * h];

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int r = (x * 255) / w;
                    int g = (y * 255) / h;
                    int b = ((x + y) * 255) / (w + h);
                    pixels[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
            testBmp.setPixels(pixels, 0, w, 0, 0, w, h);

            runOnUiThread(() -> previewImage.setImageBitmap(testBmp));

            try {
                ImageProcessor.Result result = ImageProcessor.process(testBmp);
                pebbleBridge.sendImage(result, "Test Image");
                runOnUiThread(() ->
                    Toast.makeText(this, "Sending " + result.width + "x" + result.height
                            + " (" + result.gcolorData.length + " bytes)",
                            Toast.LENGTH_LONG).show()
                );
            } catch (Exception e) {
                Log.e(TAG, "Test send failed", e);
                runOnUiThread(() ->
                    Toast.makeText(this, "Failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show()
                );
            }
        }).start();
    }
}
