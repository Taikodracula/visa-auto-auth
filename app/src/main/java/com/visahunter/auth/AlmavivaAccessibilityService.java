package com.visahunter.auth;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.List;
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
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isAutomating) return;
        handler.post(this::evaluateCurrentStep);
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

                                startMfaAutomation(qrText, (success, message) -> {
                                    updateState(true, success ? "✅ تم التأكيد بنجاح: " + message : "⚠️ فشل: " + message);
                                    sendAck(cleanUrl, email, challengeId, success, message);
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

    public synchronized void startMfaAutomation(String qrText, AutomationCallback callback) {
        if (isAutomating) {
            Log.w(TAG, "Automation already in progress, aborting previous");
            cancelAutomation();
        }

        isAutomating = true;
        currentStep = 1;

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

        // 3. Safety timeout (25 seconds)
        timeoutRunnable = () -> {
            if (isAutomating) {
                Log.w(TAG, "Automation timed out at step " + currentStep);
                performGlobalAction(GLOBAL_ACTION_HOME);
                finishAutomation(false, "انتهت المهلة قبل إتمام جميع النقرات", callback);
            }
        };
        handler.postDelayed(timeoutRunnable, 25000);

        // Schedule first check after 1.5s
        handler.postDelayed(() -> evaluateCurrentStepWithCallback(callback), 1500);
    }

    private synchronized void evaluateCurrentStepWithCallback(AutomationCallback callback) {
        if (!isAutomating) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            switch (currentStep) {
                case 1:
                    if (findAndClick(root, "امسح رمز الاستجابة السريعة", "مسح رمز الاستجابة السريعة", "Scan QR code", "امسح رمز")) {
                        Log.i(TAG, "Step 1 Completed: Clicked Scan QR Code");
                        currentStep = 2;
                        handler.postDelayed(() -> evaluateCurrentStepWithCallback(callback), 1000);
                    }
                    break;

                case 2:
                    if (findAndClick(root, "الصق القيمة المنسوخة", "لصق القيمة المنسوخة", "Paste copied value", "الصق")) {
                        Log.i(TAG, "Step 2 Completed: Clicked Paste Copied Value");
                        currentStep = 3;
                        handler.postDelayed(() -> evaluateCurrentStepWithCallback(callback), 1000);
                    }
                    break;

                case 3:
                    if (findAndClick(root, "تأكيد", "تاكيد", "Confirm")) {
                        Log.i(TAG, "Step 3 Completed: Clicked Confirm");
                        currentStep = 4;
                        handler.postDelayed(() -> evaluateCurrentStepWithCallback(callback), 2500);
                    }
                    break;

                case 4:
                    if (findAndClick(root, "حسناً", "حسنا", "OK", "Ok", "تم")) {
                        Log.i(TAG, "Step 4 Completed: Clicked OK");
                        currentStep = 5;
                        handler.postDelayed(() -> evaluateCurrentStepWithCallback(callback), 1000);
                    }
                    break;

                case 5:
                    Log.i(TAG, "Step 5: Returning to home screen");
                    performGlobalAction(GLOBAL_ACTION_HOME);
                    finishAutomation(true, "تم تأكيد الرمز والموافقة بنجاح 100%", callback);
                    break;
            }
        } finally {
            root.recycle();
        }
    }

    private void evaluateCurrentStep() {
        // Window change trigger
        evaluateCurrentStepWithCallback(null);
    }

    private boolean findAndClick(AccessibilityNodeInfo root, String... texts) {
        if (root == null) return false;
        for (String target : texts) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(target);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (performClickHierarchy(node)) {
                        node.recycle();
                        return true;
                    }
                    node.recycle();
                }
            }
        }
        return false;
    }

    private boolean performClickHierarchy(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null) {
            boolean res = performClickHierarchy(parent);
            parent.recycle();
            if (res) return true;
        }
        return clickByGesture(node);
    }

    private boolean clickByGesture(AccessibilityNodeInfo node) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Rect rect = new Rect();
            node.getBoundsInScreen(rect);
            if (!rect.isEmpty() && rect.centerX() > 0 && rect.centerY() > 0) {
                Path clickPath = new Path();
                clickPath.moveTo(rect.centerX(), rect.centerY());
                GestureDescription.StrokeDescription stroke =
                        new GestureDescription.StrokeDescription(clickPath, 0, 50);
                GestureDescription.Builder builder = new GestureDescription.Builder();
                builder.addStroke(stroke);
                Log.i(TAG, "Executing gesture click at (" + rect.centerX() + ", " + rect.centerY() + ")");
                return dispatchGesture(builder.build(), null, null);
            }
        }
        return false;
    }

    private synchronized void finishAutomation(boolean success, String message, AutomationCallback callback) {
        isAutomating = false;
        currentStep = 0;
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
        if (callback != null) {
            callback.onCompleted(success, message);
        }
    }

    public synchronized void cancelAutomation() {
        isAutomating = false;
        currentStep = 0;
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
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
                JSONObject payload = new JSONObject();
                payload.put("email", email);
                payload.put("accessibilityEnabled", true);
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
                    Log.d(TAG, "Heartbeat: " + r.code());
                }
            } catch (Exception e) {
                Log.d(TAG, "Heartbeat failed: " + e.getMessage());
            }
        }).start();
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
