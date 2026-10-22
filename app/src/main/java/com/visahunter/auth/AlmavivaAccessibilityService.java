package com.visahunter.auth;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.app.KeyguardManager;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
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
    public static final String ALMAVIVA_PACKAGE = "it.almaviva.visti";

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
        stopPolling();
        instance = null;
        updateState(false, "تم إيقاف الخدمة");
        Log.i(TAG, "AlmavivaAccessibilityService unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
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
                    String pollEndpoint = cleanUrl + "/api/client-app/poll?email=" + URLEncoder.encode(email, "UTF-8");

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
                                wakeScreenAndDismissKeyguard();
                                updateState(true, "📸 تم التقاط شاشة الهاتف للمعاينة المباشرة");
                                takeAndUploadScreenshot(cleanUrl, email);
                            } else if ("TEST_ALMAVIVA".equals(action)) {
                                Log.i(TAG, "Server requested test click sequence");
                                wakeScreenAndDismissKeyguard();
                                updateState(true, "🧪 فحص تجريبي للنقر وفتح المافيفا...");
                                startMfaAutomation("TEST_FROM_MOBILE_HUB", (success, message) -> {
                                    updateState(true, (success ? "✅ نجح الفحص: " : "⚠️ فشل الفحص: ") + message);
                                    sendAck(cleanUrl, email, "TEST", success, message);
                                    handler.postDelayed(() -> takeAndUploadScreenshot(cleanUrl, email), 1500);
                                });
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
                performGlobalAction(GLOBAL_ACTION_HOME);
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
            boolean stepSuccess = false;
            switch (currentStep) {
                case 1:
                    // Step 1: Click "امسح رمز الاستجابة السريعة"
                    stepSuccess = findAndClickSmart(root, true, "امسح رمز الاستجابة السريعة", "امسح رمز", "رمز الاستجابة");
                    if (!stepSuccess) {
                        stepSuccess = findAndClickSmart(root, false, "امسح رمز الاستجابة السريعة", "Scan QR");
                    }
                    if (stepSuccess) {
                        Log.i(TAG, "✅ [STEP 1 SUCCESS] Clicked Scan QR Code");
                        currentStep = 2;
                        handler.postDelayed(stepLoopRunnable, 1200);
                        return;
                    }
                    break;

                case 2:
                    // Step 2: Click "الصق القيمة المنسوخة" (strictly prefer exact match and bottom button to avoid top subtext)
                    stepSuccess = findAndClickSmart(root, true, "الصق القيمة المنسوخة", "لصق القيمة المنسوخة");
                    if (!stepSuccess) {
                        stepSuccess = findAndClickSmart(root, false, "الصق القيمة المنسوخة");
                    }
                    if (stepSuccess) {
                        Log.i(TAG, "✅ [STEP 2 SUCCESS] Clicked Paste Copied Value button");
                        currentStep = 3;
                        handler.postDelayed(stepLoopRunnable, 1500);
                        return;
                    }
                    break;

                case 3:
                    // Step 3: Click "تأكيد"
                    stepSuccess = findAndClickSmart(root, true, "تأكيد", "تاكيد", "Confirm");
                    if (!stepSuccess) {
                        stepSuccess = findAndClickSmart(root, false, "تأكيد", "Confirm");
                    }
                    if (stepSuccess) {
                        Log.i(TAG, "✅ [STEP 3 SUCCESS] Clicked Confirm");
                        currentStep = 4;
                        handler.postDelayed(stepLoopRunnable, 2200);
                        return;
                    }
                    break;

                case 4:
                    // Step 4: Click "حسناً"
                    stepSuccess = findAndClickSmart(root, true, "حسناً", "حسنا", "OK", "Ok", "تم");
                    if (!stepSuccess) {
                        stepSuccess = findAndClickSmart(root, false, "حسناً", "حسنا", "OK", "تم");
                    }
                    if (stepSuccess) {
                        Log.i(TAG, "✅ [STEP 4 SUCCESS] Clicked OK");
                        currentStep = 5;
                        handler.postDelayed(stepLoopRunnable, 1000);
                        return;
                    }
                    break;

                case 5:
                    // Step 5: Finished, go Home
                    Log.i(TAG, "✅ [STEP 5 SUCCESS] Returning to Home Screen");
                    performGlobalAction(GLOBAL_ACTION_HOME);
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
                        .url(serverUrl.replaceAll("/+$", "") + "/api/client-app/ack")
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
                        .url(serverUrl.replaceAll("/+$", "") + "/api/client-app/heartbeat")
                        .post(body)
                        .build();

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

    public void wakeScreenAndDismissKeyguard() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
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

        // Dismiss simple swipe keyguard if present
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                if (km != null && km.isKeyguardLocked()) {
                    performGlobalAction(GLOBAL_ACTION_DISMISS_KEYGUARD);
                    Log.i(TAG, "Dismiss keyguard action dispatched");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Dismiss keyguard error: " + e.getMessage());
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

    public void takeAndUploadScreenshot(String serverUrl, String email) {
        ScreenshotHelper.captureAndUpload(this, serverUrl, email, httpClient);
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
