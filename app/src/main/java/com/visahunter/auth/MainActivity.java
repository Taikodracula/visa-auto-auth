package com.visahunter.auth;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private TextView tvAccessibilityStatus;
    private TextView tvServerStatus;
    private Button btnEnableAccessibility;
    private TextView tvRestrictedSettingsHelp;
    private EditText etClientEmail;
    private EditText etServerUrl;
    private Button btnSaveConnect;
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
        tvRestrictedSettingsHelp = findViewById(R.id.tv_restricted_settings_help);
        etClientEmail = findViewById(R.id.et_client_email);
        etServerUrl = findViewById(R.id.et_server_url);
        btnSaveConnect = findViewById(R.id.btn_save_connect);
        btnTestClick = findViewById(R.id.btn_test_click);
        tvLog = findViewById(R.id.tv_log);

        // Preload saved preferences
        etClientEmail.setText(preferences.getClientEmail());
        etServerUrl.setText(preferences.getServerUrl());

        // Button listeners
        btnEnableAccessibility.setOnClickListener(v -> openAccessibilitySettings());
        tvRestrictedSettingsHelp.setOnClickListener(v -> showRestrictedSettingsGuide());
        btnSaveConnect.setOnClickListener(v -> saveAndConnect());
        btnTestClick.setOnClickListener(v -> runTestClick());

        // State listener from Accessibility Service
        AlmavivaAccessibilityService.setStateListener((connected, log) -> runOnUiThread(() -> {
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccessibilityUI();
    }

    private void updateAccessibilityUI() {
        boolean isRunning = AlmavivaAccessibilityService.isRunning() || isAccessibilityServiceEnabled(this, AlmavivaAccessibilityService.class);
        if (isRunning) {
            tvAccessibilityStatus.setText("🟢 مفعل وجاهز");
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.success_green));

            btnEnableAccessibility.setText("✅ إذن النقر التلقائي مفعل بنجاح");
            btnEnableAccessibility.setBackgroundResource(R.drawable.card_bg);
            btnEnableAccessibility.setTextColor(ContextCompat.getColor(this, R.color.success_green));
            btnEnableAccessibility.setEnabled(false);
            if (tvRestrictedSettingsHelp != null) {
                tvRestrictedSettingsHelp.setVisibility(View.GONE);
            }
        } else {
            tvAccessibilityStatus.setText("❌ غير مفعل");
            tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.error_red));

            btnEnableAccessibility.setText("⚠️ اضغط هنا لتفعيل إذن النقر التلقائي");
            btnEnableAccessibility.setBackgroundResource(R.drawable.btn_primary);
            btnEnableAccessibility.setTextColor(ContextCompat.getColor(this, R.color.bg_dark));
            btnEnableAccessibility.setEnabled(true);
            if (tvRestrictedSettingsHelp != null) {
                tvRestrictedSettingsHelp.setVisibility(View.VISIBLE);
            }
        }
    }

    public static boolean isAccessibilityServiceEnabled(Context context, Class<?> service) {
        ComponentName expectedComponentName = new ComponentName(context, service);
        String enabledServicesSetting = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabledServicesSetting == null) return false;
        TextUtils.SimpleStringSplitter colonSplitter = new TextUtils.SimpleStringSplitter(':');
        colonSplitter.setString(enabledServicesSetting);
        while (colonSplitter.hasNext()) {
            String componentNameString = colonSplitter.next();
            ComponentName enabledService = ComponentName.unflattenFromString(componentNameString);
            if (enabledService != null && enabledService.equals(expectedComponentName)) {
                return true;
            }
        }
        return false;
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

    private void showRestrictedSettingsGuide() {
        new AlertDialog.Builder(this)
                .setTitle("🔓 فك حظر الإعداد (أندرويد 13 و 14)")
                .setMessage("لحماية هاتفك، يطلب أندرويد إذناً يدوياً لتطبيقات الطرف الخارجي:\n\n" +
                        "1️⃣ اضغط على (فتح معلومات التطبيق) بالأسفل.\n" +
                        "2️⃣ اضغط على الـ 3 نقاط (⋮) في أعلى الزاوية.\n" +
                        "3️⃣ اختر 'السماح بالإعدادات المقيدة'.\n" +
                        "4️⃣ أكد بصمتك ثم عد للتطبيق وفعل إذن النقر بنجاح!")
                .setPositiveButton("فتح معلومات التطبيق", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(this, "تعذر فتح معلومات التطبيق: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void saveAndConnect() {
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

        Toast.makeText(this, "💾 تم حفظ البيانات!", Toast.LENGTH_SHORT).show();

        if (AlmavivaAccessibilityService.isRunning()) {
            tvServerStatus.setText("⏳ جاري فحص الاتصال...");
            tvServerStatus.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
            tvLog.setText("جاري إرسال إشارة الاتصال بالسيرفر...");
            AlmavivaAccessibilityService.getInstance().restartPolling();
        } else {
            Toast.makeText(this, "⚠️ يرجى تفعيل إذن النقر التلقائي لبدء الاتصال بالسيرفر", Toast.LENGTH_LONG).show();
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
