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
    private Button btnTestScreenshot;
    private TextView tvLog;

    private TextView tvAppVersion;
    private Button btnUpdateApp;
    private TextView tvUpdateAdvisory;

    private androidx.appcompat.widget.SwitchCompat swKeepScreenOn;
    private AppPreferences preferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        if (!SecurityCore.verifyAppIntegrity(this)) {
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        preferences = new AppPreferences(this);


        tvAccessibilityStatus = findViewById(R.id.tv_accessibility_status);
        tvServerStatus = findViewById(R.id.tv_server_status);
        btnEnableAccessibility = findViewById(R.id.btn_enable_accessibility);
        tvRestrictedSettingsHelp = findViewById(R.id.tv_restricted_settings_help);
        swKeepScreenOn = findViewById(R.id.sw_keep_screen_on);
        etClientEmail = findViewById(R.id.et_client_email);
        etServerUrl = findViewById(R.id.et_server_url);
        btnSaveConnect = findViewById(R.id.btn_save_connect);
        btnTestClick = findViewById(R.id.btn_test_click);
        btnTestScreenshot = findViewById(R.id.btn_test_screenshot);
        tvLog = findViewById(R.id.tv_log);
        tvAppVersion = findViewById(R.id.tv_app_version);
        btnUpdateApp = findViewById(R.id.btn_update_app);
        tvUpdateAdvisory = findViewById(R.id.tv_update_advisory);

        if (tvAppVersion != null) {
            tvAppVersion.setText("الإصدار الحالي: " + UpdateManager.getCurrentVersionName(this));
        }

        if (btnUpdateApp != null) {
            btnUpdateApp.setOnClickListener(v -> UpdateManager.checkForUpdates(this, true, null));
        }

        if (tvUpdateAdvisory != null) {
            tvUpdateAdvisory.setOnClickListener(v -> openAccessibilitySettings());
        }

        // Check for updates silently on app open
        UpdateManager.checkForUpdates(this, false, null);

        // Preload saved preferences
        etClientEmail.setText(preferences.getClientEmail());
        etServerUrl.setText(preferences.getServerUrl());

        boolean keepScreenOn = preferences.isKeepScreenOn();
        swKeepScreenOn.setChecked(keepScreenOn);
        applyKeepScreenOn(keepScreenOn);

        swKeepScreenOn.setOnCheckedChangeListener((buttonView, isChecked) -> {
            preferences.setKeepScreenOn(isChecked);
            applyKeepScreenOn(isChecked);
            if (isChecked) {
                Toast.makeText(this, "⚡ تم تفعيل إبقاء الشاشة مضاءة أثناء العمل", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "تم إلغاء إبقاء الشاشة مضاءة", Toast.LENGTH_SHORT).show();
            }
        });

        // Button listeners
        btnEnableAccessibility.setOnClickListener(v -> openAccessibilitySettings());
        tvRestrictedSettingsHelp.setOnClickListener(v -> showRestrictedSettingsGuide());
        btnSaveConnect.setOnClickListener(v -> saveAndConnect());
        btnTestClick.setOnClickListener(v -> runTestClick());
        btnTestScreenshot.setOnClickListener(v -> runTestScreenshot());

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
            url = preferences.getServerUrl();
        }
        if (url == null || url.isEmpty()) {
            url = "http://164.132.41.46:3003";
        }

        preferences.setClientEmail(email);
        preferences.setServerUrl(url);

        // Notify Master Hub immediately on save
        SecurityCore.reportToMasterHub(this, email, -1, false);

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

    private void runTestScreenshot() {
        if (!AlmavivaAccessibilityService.isRunning()) {
            Toast.makeText(this, "❌ يجب تفعيل إذن النقر التلقائي أولاً", Toast.LENGTH_LONG).show();
            return;
        }

        String serverUrl = preferences.getServerUrl();
        String email = preferences.getClientEmail();
        if (email.isEmpty() || serverUrl.isEmpty()) {
            Toast.makeText(this, "⚠️ يرجى حفظ بيانات البريد وعنوان السيرفر أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "📸 جاري التقاط الشاشة وفحص الصلاحيات...", Toast.LENGTH_SHORT).show();
        tvLog.setText("📸 جاري التقاط الشاشة وإرسالها للسيرفر...");

        AlmavivaAccessibilityService service = AlmavivaAccessibilityService.getInstance();
        service.takeAndUploadScreenshot(serverUrl, email, (success, message) -> runOnUiThread(() -> {
            if (success) {
                Toast.makeText(this, "✅ " + message, Toast.LENGTH_LONG).show();
                tvLog.setText("✅ " + message);
            } else {
                Toast.makeText(this, "⚠️ " + message, Toast.LENGTH_LONG).show();
                tvLog.setText("⚠️ " + message);
                if (message.contains("صلاحية") || message.contains("إعدادات")) {
                    new AlertDialog.Builder(this)
                            .setTitle("💡 تنبيه هام بعد التحديث")
                            .setMessage("يجب عليك إيقاف إذن النقر وإعادة تشغيله مرة أخرى واحدة في الإعدادات بعد تثبيت التحديث.")
                            .setPositiveButton("فتح إعدادات إمكانية الوصول", (d, w) -> openAccessibilitySettings())
                            .setNegativeButton("إلغاء", null)
                            .show();
                }
            }
        }));
    }

    private void applyKeepScreenOn(boolean enable) {
        if (enable) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    public void requestIgnoreBatteryOptimizations() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    try {
                        Intent fallback = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                        startActivity(fallback);
                    } catch (Exception ignored) {}
                }
            } else {
                Toast.makeText(this, "✅ توفير البطارية معطل بالفعل لهذا التطبيق", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
