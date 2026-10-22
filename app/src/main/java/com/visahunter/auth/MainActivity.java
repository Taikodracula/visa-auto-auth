package com.visahunter.auth;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private TextView tvAccessibilityStatus;
    private TextView tvServerStatus;
    private Button btnEnableAccessibility;
    private EditText etClientEmail;
    private EditText etServerUrl;
    private Button btnSaveConnect;
    private Button btnBatteryOpt;
    private Button btnTestClick;
    private TextView tvLog;

    private AppPreferences preferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preferences = new AppPreferences(this);

        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status);
        tvServerStatus = findViewById(R.id.tv_server_status);
        btnEnableAccessibility = findViewById(R.id.btn_enable_accessibility);
        etClientEmail = findViewById(R.id.et_client_email);
        etServerUrl = findViewById(R.id.et_server_url);
        btnSaveConnect = findViewById(R.id.btn_save_connect);
        btnBatteryOpt = findViewById(R.id.btn_battery_opt);
        btnTestClick = findViewById(R.id.btn_test_click);
        tvLog = findViewById(R.id.tv_log);

        // Preload saved preferences
        etClientEmail.setText(preferences.getClientEmail());
        etServerUrl.setText(preferences.getServerUrl());

        // Buttons listeners
        btnEnableAccessibility.setOnClickListener(v -> openAccessibilitySettings());

        btnSaveConnect.setOnClickListener(v -> saveAndStartService());

        btnBatteryOpt.setOnClickListener(v -> requestIgnoreBatteryOptimization());

        btnTestClick.setOnClickListener(v -> runTestClick());

        // State listener from polling service
        ClientPollService.setStateListener((connected, log) -> runOnUiThread(() -> {
            if (connected) {
                tvServerStatus.setText("🟢 متصل بالسيرفر");
                tvServerStatus.setTextColor(ContextCompat.getColor(this, R.color.success_green));
            } else {
                tvServerStatus.setText("🔴 غير متصل");
                tvServerStatus.setTextColor(ContextCompat.getColor(this, R.color.error_red));
            }
            if (log != null && !log.isEmpty()) {
                tvLog.setText(log);
            }
        }));

        // Automatically start service if config is present
        if (!preferences.getClientEmail().isEmpty() && !preferences.getServerUrl().isEmpty()) {
            startPollService();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccessibilityUI();
    }

    private void updateAccessibilityUI() {
        boolean isRunning = AlmavivaAccessibilityService.isRunning();
        if (isRunning) {
            tvAccessibilityStatus.setText("🟢 مفعل وجاهز");
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.success_green));

            btnEnableAccessibility.setText("✅ إذن النقر التلقائي مفعل بنجاح");
            btnEnableAccessibility.setBackgroundResource(R.drawable.card_bg);
            btnEnableAccessibility.setTextColor(ContextCompat.getColor(this, R.color.success_green));
            btnEnableAccessibility.setEnabled(false);
        } else {
            tvAccessibilityStatus.setText("❌ غير مفعل");
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.error_red));

            btnEnableAccessibility.setText("⚠️ اضغط هنا لتفعيل إذن النقر التلقائي");
            btnEnableAccessibility.setBackgroundResource(R.drawable.btn_primary);
            btnEnableAccessibility.setTextColor(ContextCompat.getColor(this, R.color.bg_dark));
            btnEnableAccessibility.setEnabled(true);
        }
    }

    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "ابحث عن 'مساعد تأشيرات المافيفا' واضغط تفعيل", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "تعذر فتح الإعدادات: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveAndStartService() {
        String email = etClientEmail.getText().toString().trim();
        String url = etServerUrl.getText().toString().trim();

        if (email.isEmpty()) {
            etClientEmail.setError("يرجى إدخال البريد الإلكتروني");
            etClientEmail.requestFocus();
            return;
        }

        if (url.isEmpty()) {
            etServerUrl.setError("يرجى إدخال عنوان السيرفر");
            etServerUrl.requestFocus();
            return;
        }

        preferences.setClientEmail(email);
        preferences.setServerUrl(url);

        Toast.makeText(this, "💾 تم حفظ البيانات وبدء الاتصال...", Toast.LENGTH_SHORT).show();
        startPollService();
    }

    private void startPollService() {
        Intent serviceIntent = new Intent(this, ClientPollService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "✅ استثناء البطارية مفعل بالفعل!", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                try {
                    Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    startActivity(intent);
                } catch (Exception ex) {
                    Toast.makeText(this, "افتح إعدادات البطارية وقم باستثناء التطبيق", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void runTestClick() {
        if (!AlmavivaAccessibilityService.isRunning()) {
            Toast.makeText(this, "❌ يجب تفعيل إذن النقر التلقائي أولاً", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "🚀 بدء تجربة النقر التجريبية على تطبيق المافيفا...", Toast.LENGTH_SHORT).show();
        AlmavivaAccessibilityService.getInstance().startMfaAutomation(
                "TEST_ALMAVIVA_CLICK_FLOW_DEMO",
                (success, message) -> runOnUiThread(() -> {
                    Toast.makeText(this, (success ? "✅ " : "❌ ") + message, Toast.LENGTH_LONG).show();
                    tvLog.setText("نتائج فحص النقر: " + message);
                })
        );
    }
}
