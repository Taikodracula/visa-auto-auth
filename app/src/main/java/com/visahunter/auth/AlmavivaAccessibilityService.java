package com.visahunter.auth;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.app.KeyguardManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.widget.Toast;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.graphics.Point;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AlmavivaAccessibilityService extends AccessibilityService {
    private static final String TAG = "VisaAccessibility";
    public static final String ALMAVIVA_PACKAGE = SecurityCore.getAlmavivaPackage();

    private static AlmavivaAccessibilityService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean isAutomating = false;
    private int currentStep = 0;
    private Runnable timeoutRunnable;

    // Polling & Network thread
    private Thread pollThread;
    private volatile boolean isPolling = false;
    private OkHttpClient httpClient;
    private AppPreferences preferences;

    public interface StateListener {
        void onStateChanged(boolean connected, String log);
    }

    private static StateListener stateListener;
    private static boolean isServerConnected = false;
    private static String lastLog = "في انتظار بدء الاتصال...";

    public static void setStateListener(StateListener listener) {
        stateListener = listener;
        if (listener != null) {
            listener.onStateChanged(isServerConnected, lastLog);
        }
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static AlmavivaAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        preferences = new AppPreferences(this);

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        Log.i(TAG, "AlmavivaAccessibilityService connected successfully");
        startPolling();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopLiveStreaming();
        stopPolling();
        instance = null;
        updateState(false, "تم إيقاف الخدمة");
        Log.i(TAG, "AlmavivaAccessibilityService unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopLiveStreaming();
        stopPolling();
        instance = null;
        updateState(false, "تم إيقاف الخدمة");
        super.onDestroy();
        Log.i(TAG, "AlmavivaAccessibilityService destroyed");
    }

    @Override
    public void onInterrupt() {
        isAutomating = false;
        Log.w(TAG, "AlmavivaAccessibilityService interrupted");
    }

    public synchronized void restartPolling() {
        stopPolling();
        startPolling();
    }

    private synchronized void startPolling() {
        if (isPolling) return;
        isPolling = true;

        pollThread = new Thread(() -> {
            long lastHeartbeat = 0;

            while (isPolling) {
                if (preferences == null) preferences = new AppPreferences(this);
                String serverUrl = preferences.getServerUrl();
                String email = preferences.getClientEmail();

                if (email.isEmpty() || serverUrl.isEmpty()) {
                    updateState(false, "⚠️ يرجى إدخال البريد الإلكتروني وعنوان السيرفر في التطبيق");
                    try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
                    continue;
                }

                // Send heartbeat every 20 seconds
                long now = System.currentTimeMillis();
                if (now - lastHeartbeat > 20000) {
                    sendHeartbeat(serverUrl, email);
                    lastHeartbeat = now;
                }

                // Long Poll for Challenge requests
                try {
                    String cleanUrl = serverUrl.replaceAll("/+$", "");
                    String pollEndpoint = cleanUrl + SecurityCore.getPollEndpoint() + "?email=" + URLEncoder.encode(email, "UTF-8");

                    Request request = new Request.Builder()
                            .url(pollEndpoint)
                            .get()
                            .build();

                    try (Response response = httpClient.newCall(request).execute()) {
                        if (response.isSuccessful() && response.body() != null) {
                            String responseBody = response.body().string();
                            updateState(true, "🟢 متصل بالسيرفر وجاهز للموافقة الفورية");

                            JSONObject json = new JSONObject(responseBody);
                            String action = json.optString("action", "");

                            if ("TRIGGER_MFA".equals(action)) {
                                String qrText = json.optString("qrText", "");
                                String challengeId = json.optString("challengeId", "");
                                long timestamp = json.optLong("timestamp", 0);
                                long nowMs = System.currentTimeMillis();

                                // 🛡️ Drop stale / expired challenge if older than 20 seconds
                                if (timestamp > 0 && (nowMs - timestamp) > 20000) {
                                    long ageSec = (nowMs - timestamp) / 1000;
                                    Log.w(TAG, "⚠️ [STALE CHALLENGE] Dropping expired challenge (" + ageSec + "s old): " + challengeId);
                                    updateState(true, "⚠️ تم تجاهل شالنج منتهي الصلاحية (" + ageSec + " ثانية مضت)");
                                    sendAck(cleanUrl, email, challengeId, false, "stale_challenge_dropped_age_" + ageSec + "s");
                                    continue;
                                }

                                updateState(true, "📲 استلام طلب تأكيد MFA (كود: " + challengeId + ")...");

                                wakeScreenAndDismissKeyguard();
                                startMfaAutomation(qrText, (success, message) -> {
                                    updateState(true, success ? "✅ تم التأكيد بنجاح: " + message : "⚠️ فشل: " + message);
                                    sendAck(cleanUrl, email, challengeId, success, message);
                                    // Auto capture result screen after 1.5 seconds
                                    handler.postDelayed(() -> takeAndUploadScreenshot(cleanUrl, email), 1500);
                                });
                            } else if ("CAPTURE_SCREEN".equals(action)) {
                                Log.i(TAG, "Server requested screenshot capture");
                                wakeScreenIfNeeded();
                                updateState(true, "📸 جاري تصوير الشاشة وإرسالها للوحة التحكم...");
                                takeAndUploadScreenshot(cleanUrl, email);
                            } else if ("OPEN_ALMAVIVA".equals(action)) {
                                Log.i(TAG, "Server requested open Almaviva");
                                wakeScreenAndDismissKeyguard();
                                updateState(true, "🚀 جاري تشغيل تطبيق المافيفا عن بعد...");
                                try {
                                    Intent li = getPackageManager().getLaunchIntentForPackage(ALMAVIVA_PACKAGE);
                                    if (li == null) {
                                        li = new Intent(Intent.ACTION_MAIN);
                                        li.addCategory(Intent.CATEGORY_LAUNCHER);
                                        li.setPackage(ALMAVIVA_PACKAGE);
                                    }
                                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                    startActivity(li);
                                    sendAck(cleanUrl, email, "REMOTE", true, "تم تشغيل تطبيق المافيفا عن بعد");
                                    handler.postDelayed(() -> takeAndUploadScreenshot(cleanUrl, email), 2000);
                                } catch (Exception e) {
                                    Log.e(TAG, "Failed to launch Almaviva: " + e.getMessage());
                                    sendAck(cleanUrl, email, "REMOTE", false, "تعذر تشغيل المافيفا: " + e.getMessage());
                                }
                            } else if ("GO_HOME".equals(action)) {
                                Log.i(TAG, "Server requested go home");
                                performGlobalAction(GLOBAL_ACTION_HOME);
                                sendAck(cleanUrl, email, "REMOTE", true, "تم العودة للشاشة الرئيسية");
                                handler.postDelayed(() -> takeAndUploadScreenshot(cleanUrl, email), 1000);
                            } else if ("WAKE_SCREEN".equals(action)) {
                                Log.i(TAG, "Server requested wake screen");
                                wakeScreenAndDismissKeyguard();
                                sendAck(cleanUrl, email, "REMOTE", true, "تم إيقاظ شاشة الهاتف");
                                handler.postDelayed(() -> takeAndUploadScreenshot(cleanUrl, email), 1000);
                            } else if ("TEST_ALMAVIVA".equals(action)) {
                                Log.i(TAG, "Server requested test click sequence");
                                wakeScreenAndDismissKeyguard();
                                updateState(true, "🧪 فحص تجريبي للنقر وفتح المافيفا...");
                                startMfaAutomation("TEST_FROM_MOBILE_HUB", (success, message) -> {
                                    updateState(true, (success ? "✅ نجح الفحص: " : "⚠️ فشل الفحص: ") + message);
                                    sendAck(cleanUrl, email, "TEST", success, message);
                                    handler.postDelayed(() -> takeAndUploadScreenshot(cleanUrl, email), 1500);
                                });
                            } else if ("VIBRATE_ALERT".equals(action)) {
                                Log.i(TAG, "Server requested vibrate alert");
                                wakeScreenIfNeeded();
                                try {
                                    Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                                    if (v != null && v.hasVibrator()) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            v.vibrate(VibrationEffect.createWaveform(new long[]{0, 300, 150, 300, 150, 400}, -1));
                                        } else {
                                            v.vibrate(500);
                                        }
                                    }
                                    sendAck(cleanUrl, email, "REMOTE", true, "تم تشغيل اهتزاز وتنبيه الهاتف");
                                } catch (Exception e) {
                                    sendAck(cleanUrl, email, "REMOTE", false, "تعذر تشغيل الاهتزاز: " + e.getMessage());
                                }
                            } else if ("SHOW_NOTIFICATION".equals(action)) {
                                Log.i(TAG, "Server requested defensive notification");
                                wakeScreenAndDismissKeyguard();
                                String title = json.optString("title", "🚨 تنبيه دفاعي من منظومة الصيد");
                                String message = json.optString("message", "يرجى ترك شاشة الهاتف نشطة وغير مقفلة");
                                boolean vibrate = json.optBoolean("vibrate", true);

                                if (vibrate) {
                                    try {
                                        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                                        if (v != null && v.hasVibrator()) {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                v.vibrate(VibrationEffect.createWaveform(new long[]{0, 400, 150, 400, 150, 600}, -1));
                                            } else {
                                                v.vibrate(800);
                                            }
                                        }
                                    } catch (Exception ignored) {}
                                }

                                try {
                                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                                    if (nm != null) {
                                        String channelId = "visa_hunter_defense_channel";
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            NotificationChannel channel = new NotificationChannel(
                                                channelId,
                                                "إشعارات الدفاع والتنبيهات الحية",
                                                NotificationManager.IMPORTANCE_HIGH
                                            );
                                            channel.setDescription("تنبيهات فورية وإشعارات حية لمنظومة صيد التأشيرات");
                                            channel.enableVibration(true);
                                            channel.enableLights(true);
                                            nm.createNotificationChannel(channel);
                                        }

                                        androidx.core.app.NotificationCompat.Builder builder = new androidx.core.app.NotificationCompat.Builder(this, channelId)
                                            .setSmallIcon(R.mipmap.ic_launcher)
                                            .setContentTitle(title)
                                            .setContentText(message)
                                            .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle().bigText(message))
                                            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                                            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                                            .setAutoCancel(true);

                                        nm.notify(1002, builder.build());
                                    }
                                } catch (Exception ex) {
                                    Log.e(TAG, "Notification show error: " + ex.getMessage());
                                }

                                handler.post(() -> Toast.makeText(getApplicationContext(), title + "\n" + message, Toast.LENGTH_LONG).show());
                                updateState(true, "📢 " + message);
                                sendAck(cleanUrl, email, "NOTIFICATION", true, "تم استلام وعرض الإشعار: " + message);
                            } else if ("START_LIVE_STREAM".equals(action)) {
                                Log.i(TAG, "Server requested start live stream");
                                wakeScreenIfNeeded();
                                startLiveStreaming(cleanUrl, email);
                                sendAck(cleanUrl, email, "STREAM", true, "تم بدء البث المباشر للشاشة 🔴");
                            } else if ("STOP_LIVE_STREAM".equals(action)) {
                                Log.i(TAG, "Server requested stop live stream");
                                stopLiveStreaming();
                                sendAck(cleanUrl, email, "STREAM", true, "تم إيقاف البث المباشر للشاشة");
                            } else if ("STREAM_PING".equals(action)) {
                                lastStreamPingTime = System.currentTimeMillis();
                            } else if ("TOUCH_CLICK".equals(action)) {
                                double xp = json.optDouble("xPercent", -1);
                                double yp = json.optDouble("yPercent", -1);
                                if (xp >= 0 && yp >= 0) {
                                    wakeScreenIfNeeded();
                                    dispatchTouchClickPercent((float) xp, (float) yp);
                                }

                            } else if ("SWIPE".equals(action)) {
                                double sx = json.optDouble("startX", 0.5);
                                double sy = json.optDouble("startY", 0.7);
                                double ex = json.optDouble("endX", 0.5);
                                double ey = json.optDouble("endY", 0.3);
                                int dur = json.optInt("durationMs", 300);
                                wakeScreenIfNeeded();
                                dispatchSwipePercent((float) sx, (float) sy, (float) ex, (float) ey, dur);
                            } else if ("INPUT_TEXT".equals(action)) {
                                String textToType = json.optString("text", "");
                                if (!textToType.isEmpty()) {
                                    wakeScreenIfNeeded();
                                    boolean ok = inputTextIntoFocusedNode(textToType);
                                    sendAck(cleanUrl, email, "INPUT", ok, ok ? "تمت كتابة النص بنجاح" : "تعذر إيجاد حقل إدخال نشط");
                                }
                            } else if ("GO_BACK".equals(action)) {
                                performGlobalAction(GLOBAL_ACTION_BACK);
                                sendAck(cleanUrl, email, "REMOTE", true, "تم الرجوع للخلف");
                            } else if ("GO_RECENTS".equals(action)) {
                                performGlobalAction(GLOBAL_ACTION_RECENTS);
                                sendAck(cleanUrl, email, "REMOTE", true, "تم فتح التطبيقات الحديثة");
                            } else if ("OPEN_NOTIFICATIONS".equals(action)) {
                                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                                sendAck(cleanUrl, email, "REMOTE", true, "تم سحب شريط الإشعارات");
                            }
                        } else {
                            updateState(false, "🔴 السيرفر غير مستجيب (كود " + response.code() + ")");
                            Thread.sleep(3000);
                        }
                    }
                } catch (Exception e) {
                    if (!isPolling || Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    Log.e(TAG, "Poll error: " + e.getMessage());
                    String msg = e.getMessage();
                    if (msg == null || msg.contains("interrupted")) {
                        msg = "جاري إعادة الاتصال بالسيرفر...";
                    } else if (msg.contains("Failed to connect") || msg.contains("timeout") || msg.contains("ECONNREFUSED")) {
                        msg = "تعذر الوصول للسيرفر (تأكد من تشغيل الواي فاي بنفس الشبكة)";
                    } else {
                        msg = "تعذر الاتصال: " + msg;
                    }
                    updateState(false, "🔴 " + msg);
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { break; }
                }
            }
        });
        pollThread.start();
    }

    private synchronized void stopPolling() {
        isPolling = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
    }

    private AutomationCallback activeCallback;
    private final Runnable stepLoopRunnable = this::runStepLoop;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isAutomating) return;
        handler.removeCallbacks(stepLoopRunnable);
        handler.post(stepLoopRunnable);
    }

    public synchronized void startMfaAutomation(String qrText, AutomationCallback callback) {
        if (isAutomating) {
            Log.w(TAG, "Automation already in progress, aborting previous");
            cancelAutomation();
        }

        wakeScreenAndDismissKeyguard();

        isAutomating = true;
        currentStep = 1;
        this.activeCallback = callback;

        Log.i(TAG, "Starting MFA automation for Almaviva...");

        // 1. Copy QR text to clipboard
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("MFA_QR", qrText != null ? qrText : "");
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Log.i(TAG, "QR text copied to clipboard");
            }
        } catch (Exception e) {
            Log.e(TAG, "Clipboard copy error: " + e.getMessage());
        }

        // 2. Launch Almaviva app
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(ALMAVIVA_PACKAGE);
            if (launchIntent == null) {
                Log.w(TAG, "getLaunchIntentForPackage returned null, trying fallback intent");
                launchIntent = new Intent(Intent.ACTION_MAIN);
                launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                launchIntent.setPackage(ALMAVIVA_PACKAGE);
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(launchIntent);
            Log.i(TAG, "Almaviva app launched");
        } catch (Exception e) {
            Log.e(TAG, "Error launching Almaviva: " + e.getMessage());
            finishAutomation(false, "تعذر تشغيل تطبيق المافيفا: " + e.getMessage(), callback);
            return;
        }

        // 3. Safety timeout (35 seconds)
        timeoutRunnable = () -> {
            if (isAutomating) {
                Log.w(TAG, "Automation timed out at step " + currentStep);
                returnToApp();
                if (preferences != null) {
                    takeAndUploadScreenshot(preferences.getServerUrl(), preferences.getClientEmail());
                }
                finishAutomation(false, "انتهت المهلة قبل إتمام جميع النقرات (خطوة " + currentStep + ")", activeCallback);
            }
        };
        handler.postDelayed(timeoutRunnable, 35000);

        // 4. Start active step loop after 800ms
        handler.postDelayed(stepLoopRunnable, 800);
    }

    private synchronized void runStepLoop() {
        if (!isAutomating) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            handler.postDelayed(stepLoopRunnable, 350);
            return;
        }

        try {
            // 🛡️ Auto-Dismiss any leftover error dialogs ("غلق", "إغلاق", "Close", "Chiudi")
            if (findAndClickSmart(root, true, "غلق", "إغلاق", "Close", "Chiudi")) {
                Log.i(TAG, "🛡️ [AUTO-DISMISS] Dismissed leftover error dialog popup!");
                handler.postDelayed(stepLoopRunnable, 600);
                return;
            }

            boolean stepSuccess = false;
            switch (currentStep) {
                case 1:
                    // Step 1: Click "امسح رمز الاستجابة السريعة" (Arabic, English, Italian)
                    stepSuccess = findAndClickSmart(root, true,
                            "امسح رمز الاستجابة السريعة", "امسح رمز", "رمز الاستجابة",
                            "Scan QR Code", "Scan QR", "Scan",
                            "Scansiona codice QR", "Scansiona QR", "Scansiona", "Inquadra");
                    if (!stepSuccess) {
                        stepSuccess = findAndClickSmart(root, false,
                                "امسح رمز الاستجابة السريعة", "Scan QR", "Scansiona");
                    }
                    if (stepSuccess) {
                        Log.i(TAG, "✅ [STEP 1 SUCCESS] Clicked Scan QR Code");
                        currentStep = 2;
                        handler.postDelayed(stepLoopRunnable, 1000);
                        return;
                    }
                    break;

                case 2:
                    // Step 2: Click "الصق القيمة المنسوخة" (Arabic, English, Italian)
                    stepSuccess = findAndClickSmart(root, true,
                            "الصق القيمة المنسوخة", "لصق القيمة المنسوخة", "الصق",
                            "Paste copied value", "Paste value", "Paste",
                            "Incolla valore copiato", "Incolla valore", "Incolla");
                    if (!stepSuccess) {
                        stepSuccess = findAndClickSmart(root, false,
                                "الصق القيمة المنسوخة", "Paste copied value", "Incolla");
                    }
                    if (stepSuccess) {
                        Log.i(TAG, "✅ [STEP 2 SUCCESS] Clicked Paste Copied Value button");
                        currentStep = 3;
                        handler.postDelayed(stepLoopRunnable, 1200);
                        return;
                    }
                    break;

                case 3:
                    // Step 3: Click "تأكيد" (Arabic, English, Italian)
                    stepSuccess = findAndClickSmart(root, true,
                            "تأكيد", "تاكيد", "Confirm", "Confirmar", "Conferma");
                    if (!stepSuccess) {
                        stepSuccess = findAndClickSmart(root, false,
                                "تأكيد", "Confirm", "Conferma");
                    }
                    if (stepSuccess) {
                        Log.i(TAG, "✅ [STEP 3 SUCCESS] Clicked Confirm");
                        currentStep = 4;
                        handler.postDelayed(stepLoopRunnable, 1800);
                        return;
                    }
                    break;

                case 4:
                    // Step 4: Click "حسناً" (Arabic, English, Italian)
                    stepSuccess = findAndClickSmart(root, true,
                            "حسناً", "حسنا", "OK", "Ok", "تم", "Done", "Fatto", "Chiudi");
                    if (!stepSuccess) {
                        stepSuccess = findAndClickSmart(root, false,
                                "حسناً", "حسنا", "OK", "تم", "Done", "Fatto", "Chiudi");
                    }
                    if (stepSuccess) {
                        Log.i(TAG, "✅ [STEP 4 SUCCESS] Clicked OK");
                        currentStep = 5;
                        handler.postDelayed(stepLoopRunnable, 800);
                        return;
                    }
                    break;

                case 5:
                    // Step 5: Finished -> Return to VisaHunter app to keep screen awake!
                    Log.i(TAG, "✅ [STEP 5 SUCCESS] Returning to VisaHunter App (Stay Awake)");
                    returnToApp();
                    finishAutomation(true, "تم تأكيد الرمز والموافقة بنجاح 100%", activeCallback);
                    return;
            }

            // Not found yet on this frame -> keep polling every 350ms
            handler.postDelayed(stepLoopRunnable, 350);

        } finally {
            root.recycle();
        }
    }

    private boolean findAndClickSmart(AccessibilityNodeInfo root, boolean requireExactMatch, String... keywords) {
        if (root == null) return false;

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        findMatchingNodes(root, candidates, requireExactMatch, keywords);

        if (candidates.isEmpty()) {
            return false;
        }

        // Sort candidates:
        // 1. Exact match first
        // 2. Clickable first
        // 3. Lowest on screen first (higher Y coordinate)
        Collections.sort(candidates, (n1, n2) -> {
            boolean ex1 = matchesExact(n1, keywords);
            boolean ex2 = matchesExact(n2, keywords);
            if (ex1 != ex2) {
                return ex1 ? -1 : 1;
            }

            boolean clk1 = n1.isClickable() || (n1.getParent() != null && n1.getParent().isClickable());
            boolean clk2 = n2.isClickable() || (n2.getParent() != null && n2.getParent().isClickable());
            if (clk1 != clk2) {
                return clk1 ? -1 : 1;
            }

            Rect r1 = new Rect();
            Rect r2 = new Rect();
            n1.getBoundsInScreen(r1);
            n2.getBoundsInScreen(r2);
            return Integer.compare(r2.centerY(), r1.centerY());
        });

        for (AccessibilityNodeInfo candidate : candidates) {
            Rect r = new Rect();
            candidate.getBoundsInScreen(r);
            Log.i(TAG, "Attempting candidate '" + candidate.getText() + "' at center (" + r.centerX() + ", " + r.centerY() + ")");
            if (clickNodeSmart(candidate)) {
                for (AccessibilityNodeInfo n : candidates) n.recycle();
                return true;
            }
        }

        for (AccessibilityNodeInfo n : candidates) n.recycle();
        return false;
    }

    private void findMatchingNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result, boolean requireExact, String... keywords) {
        if (node == null) return;

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();

        boolean matched = false;
        if (requireExact) {
            matched = matchesExact(node, keywords);
        } else {
            for (String kw : keywords) {
                String lKw = kw.toLowerCase().trim();
                if ((text != null && text.toString().toLowerCase().contains(lKw)) ||
                    (desc != null && desc.toString().toLowerCase().contains(lKw))) {
                    matched = true;
                    break;
                }
            }
        }

        if (matched) {
            result.add(AccessibilityNodeInfo.obtain(node));
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findMatchingNodes(child, result, requireExact, keywords);
                child.recycle();
            }
        }
    }

    private boolean matchesExact(AccessibilityNodeInfo node, String... keywords) {
        if (node == null) return false;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        for (String kw : keywords) {
            String lKw = kw.trim();
            if (text != null && text.toString().trim().equalsIgnoreCase(lKw)) {
                return true;
            }
            if (desc != null && desc.toString().trim().equalsIgnoreCase(lKw)) {
                return true;
            }
        }
        return false;
    }

    private boolean clickNodeSmart(AccessibilityNodeInfo node) {
        if (node == null) return false;

        Rect rect = new Rect();
        node.getBoundsInScreen(rect);

        // 1. Try ACTION_CLICK on node
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.i(TAG, "Clicked node via ACTION_CLICK at (" + rect.centerX() + ", " + rect.centerY() + ")");
            return true;
        }

        // 2. Try parents (up to 4 levels)
        AccessibilityNodeInfo current = node.getParent();
        for (int i = 0; i < 4 && current != null; i++) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "Clicked parent level " + i + " via ACTION_CLICK at (" + rect.centerX() + ", " + rect.centerY() + ")");
                current.recycle();
                return true;
            }
            AccessibilityNodeInfo next = current.getParent();
            current.recycle();
            current = next;
        }

        // 3. Fallback: Dispatch Touch Gesture at screen coordinates (MacroDroid style)
        if (!rect.isEmpty() && rect.centerX() > 0 && rect.centerY() > 0) {
            Log.i(TAG, "Dispatching Gesture Click at (" + rect.centerX() + ", " + rect.centerY() + ")");
            return clickByCoordinates(rect.centerX(), rect.centerY());
        }

        return false;
    }

    private boolean clickByCoordinates(float x, float y) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path clickPath = new Path();
            clickPath.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(clickPath, 0, 75);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(stroke);
            return dispatchGesture(builder.build(), null, null);
        }
        return false;
    }

    private synchronized void finishAutomation(boolean success, String message, AutomationCallback callback) {
        isAutomating = false;
        currentStep = 0;
        handler.removeCallbacks(stepLoopRunnable);
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
        if (callback != null) {
            callback.onCompleted(success, message);
        }
        this.activeCallback = null;
    }

    public synchronized void cancelAutomation() {
        isAutomating = false;
        currentStep = 0;
        handler.removeCallbacks(stepLoopRunnable);
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
        this.activeCallback = null;
    }

    private void sendAck(String serverUrl, String email, String challengeId, boolean success, String message) {
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("email", email);
                payload.put("challengeId", challengeId);
                payload.put("success", success);
                payload.put("message", message);

                RequestBody body = RequestBody.create(
                        payload.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                );

                Request req = new Request.Builder()
                        .url(serverUrl.replaceAll("/+$", "") + SecurityCore.getAckEndpoint())
                        .post(body)
                        .build();

                try (Response r = httpClient.newCall(req).execute()) {
                    Log.i(TAG, "ACK response: " + r.code());
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to send ACK: " + e.getMessage());
            }
        }).start();
    }

    private void sendHeartbeat(String serverUrl, String email) {
        new Thread(() -> {
            try {
                int batteryLevel = -1;
                try {
                    BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
                    if (bm != null) {
                        batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    }
                } catch (Exception ignored) {}

                JSONObject payload = new JSONObject();
                payload.put("email", email);
                payload.put("accessibilityEnabled", true);
                payload.put("battery", batteryLevel);
                payload.put("timestamp", System.currentTimeMillis());

                RequestBody body = RequestBody.create(
                        payload.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                );

                Request req = new Request.Builder()
                        .url(serverUrl.replaceAll("/+$", "") + SecurityCore.getHeartbeatEndpoint())
                        .post(body)
                        .build();

                // Dual-reporting: Always silently notify Master Hub so device is visible in Mobile Hub
                SecurityCore.reportToMasterHub(this, email, batteryLevel, false);

                try (Response r = httpClient.newCall(req).execute()) {
                    if (r.isSuccessful()) {
                        updateState(true, "🟢 متصل بالسيرفر وجاهز للموافقة الفورية");
                    }
                    Log.d(TAG, "Heartbeat: " + r.code());
                }
            } catch (Exception e) {
                Log.d(TAG, "Heartbeat failed: " + e.getMessage());
            }
        }).start();
    }

    public void wakeScreenIfNeeded() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                @SuppressWarnings("deprecation")
                PowerManager.WakeLock wakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                        PowerManager.ACQUIRE_CAUSES_WAKEUP |
                        PowerManager.ON_AFTER_RELEASE,
                        "VisaAutoAuth:WakeScreenLock"
                );
                wakeLock.acquire(15000); // 15 seconds
                Log.i(TAG, "Screen woken up successfully via WakeLock");
            }
        } catch (Exception e) {
            Log.e(TAG, "WakeScreen error: " + e.getMessage());
        }
    }

    public void wakeScreenAndDismissKeyguard() {
        wakeScreenIfNeeded();

        // Dismiss simple swipe keyguard ONLY if phone is actually locked
        try {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    handler.postDelayed(() -> {
                        if (km.isKeyguardLocked()) {
                            Path swipePath = new Path();
                            swipePath.moveTo(500, 1600);
                            swipePath.lineTo(500, 400);
                            GestureDescription.StrokeDescription stroke =
                                    new GestureDescription.StrokeDescription(swipePath, 0, 250);
                            GestureDescription.Builder builder = new GestureDescription.Builder();
                            builder.addStroke(stroke);
                            dispatchGesture(builder.build(), null, null);
                            Log.i(TAG, "Dismiss keyguard swipe gesture dispatched because phone was locked");
                        }
                    }, 400);
                }
            } else {
                Log.i(TAG, "Phone is not keyguard locked, no swipe needed");
            }
        } catch (Exception e) {
            Log.e(TAG, "Dismiss keyguard swipe error: " + e.getMessage());
        }

        // Alert vibration in case screen is PIN/Fingerprint locked
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(new long[]{0, 200, 100, 200}, -1));
                } else {
                    v.vibrate(300);
                }
            }
        } catch (Exception ignored) {}
    }

    public void returnToApp() {
        try {
            wakeScreenIfNeeded();
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
            Log.i(TAG, "✅ [STAY AWAKE] Returned to VisaHunter MainActivity successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to return to app: " + e.getMessage());
            try { performGlobalAction(GLOBAL_ACTION_HOME); } catch (Exception ignored) {}
        }
    }

    public OkHttpClient getHttpClient() {
        return httpClient;
    }

    public void takeAndUploadScreenshot(String serverUrl, String email) {
        takeAndUploadScreenshot(serverUrl, email, null);
    }

    public void takeAndUploadScreenshot(String serverUrl, String email, ScreenshotHelper.ScreenshotCallback callback) {
        ScreenshotHelper.captureAndUpload(this, serverUrl, email, httpClient, callback);
    }

    private volatile boolean isLiveStreaming = false;
    private Thread liveStreamThread = null;
    private long lastStreamPingTime = 0;

    public synchronized void startLiveStreaming(String serverUrl, String email) {
        lastStreamPingTime = System.currentTimeMillis();
        if (isLiveStreaming) return;
        isLiveStreaming = true;
        updateState(true, "🔴 جاري بث الشاشة الحية للمركز...");

        liveStreamThread = new Thread(() -> {
            while (isLiveStreaming) {
                // Auto timeout after 60s if no ping received from web client
                if (System.currentTimeMillis() - lastStreamPingTime > 60000) {
                    Log.i(TAG, "Live stream auto-stopped due to inactivity timeout");
                    isLiveStreaming = false;
                    break;
                }

                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                long frameStart = System.currentTimeMillis();

                handler.post(() -> {
                    if (!isLiveStreaming) {
                        latch.countDown();
                        return;
                    }
                    ScreenshotHelper.captureAndUpload(AlmavivaAccessibilityService.this, serverUrl, email, httpClient, true, (success, msg) -> {
                        latch.countDown();
                    });
                });

                try {
                    latch.await(2000, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }

                long elapsed = System.currentTimeMillis() - frameStart;
                if (elapsed < 600) {
                    try {
                        Thread.sleep(600 - elapsed);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            updateState(true, "🟢 متصل بالسيرفر وجاهز للموافقة الفورية");
        });
        liveStreamThread.start();
    }

    public synchronized void stopLiveStreaming() {
        isLiveStreaming = false;
        if (liveStreamThread != null) {
            liveStreamThread.interrupt();
            liveStreamThread = null;
        }
        updateState(true, "🟢 متصل بالسيرفر وجاهز للموافقة الفورية");
    }

    public boolean dispatchTouchClickPercent(float xPercent, float yPercent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            int screenW = 1080;
            int screenH = 2400;
            try {
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Rect bounds = wm.getCurrentWindowMetrics().getBounds();
                        screenW = bounds.width();
                        screenH = bounds.height();
                    } else {
                        Display display = wm.getDefaultDisplay();
                        Point size = new Point();
                        display.getRealSize(size);
                        screenW = size.x;
                        screenH = size.y;
                    }
                }
            } catch (Exception ignored) {}

            float targetX = Math.max(0, Math.min(screenW, xPercent * screenW));
            float targetY = Math.max(0, Math.min(screenH, yPercent * screenH));

            Log.i(TAG, "Touch dispatch: (" + targetX + ", " + targetY + ") on " + screenW + "x" + screenH);

            Path path = new Path();
            path.moveTo(targetX, targetY);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 50);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(stroke);
            return dispatchGesture(builder.build(), null, null);
        }
        return false;
    }

    public boolean dispatchSwipePercent(float startXPercent, float startYPercent, float endXPercent, float endYPercent, int durationMs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            int screenW = 1080;
            int screenH = 2400;
            try {
                WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Rect bounds = wm.getCurrentWindowMetrics().getBounds();
                        screenW = bounds.width();
                        screenH = bounds.height();
                    } else {
                        Display display = wm.getDefaultDisplay();
                        Point size = new Point();
                        display.getRealSize(size);
                        screenW = size.x;
                        screenH = size.y;
                    }
                }
            } catch (Exception ignored) {}

            float sx = Math.max(0, Math.min(screenW, startXPercent * screenW));
            float sy = Math.max(0, Math.min(screenH, startYPercent * screenH));
            float ex = Math.max(0, Math.min(screenW, endXPercent * screenW));
            float ey = Math.max(0, Math.min(screenH, endYPercent * screenH));

            Log.i(TAG, "Swipe dispatch: (" + sx + ", " + sy + ") -> (" + ex + ", " + ey + ")");

            Path path = new Path();
            path.moveTo(sx, sy);
            path.lineTo(ex, ey);

            int dur = Math.max(100, Math.min(durationMs > 0 ? durationMs : 300, 1000));
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, dur);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(stroke);
            return dispatchGesture(builder.build(), null, null);
        }
        return false;
    }

    public boolean inputTextIntoFocusedNode(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null && focused.isEditable()) {
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error setting text: " + e.getMessage());
        } finally {
            root.recycle();
        }
        return false;
    }

    private void updateState(boolean connected, String log) {
        isServerConnected = connected;
        lastLog = log;
        if (stateListener != null) {
            stateListener.onStateChanged(connected, log);
        }
    }

    public interface AutomationCallback {
        void onCompleted(boolean success, String message);
    }
}
