package com.visahunter.auth;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class AlmavivaAccessibilityService extends AccessibilityService {
    private static final String TAG = "VisaAccessibility";
    public static final String ALMAVIVA_PACKAGE = "it.almaviva.visti";

    private static AlmavivaAccessibilityService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean isAutomating = false;
    private int currentStep = 0;
    private Runnable timeoutRunnable;

    public interface AutomationCallback {
        void onCompleted(boolean success, String message);
    }

    private AutomationCallback currentCallback;

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
        Log.i(TAG, "AlmavivaAccessibilityService connected successfully");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        Log.i(TAG, "AlmavivaAccessibilityService destroyed");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isAutomating) return;

        // Perform click inspection on window state or content changes
        handler.post(this::evaluateCurrentStep);
    }

    @Override
    public void onInterrupt() {
        isAutomating = false;
        Log.w(TAG, "AlmavivaAccessibilityService interrupted");
    }

    public synchronized void startMfaAutomation(String qrText, AutomationCallback callback) {
        if (isAutomating) {
            Log.w(TAG, "Automation already in progress, aborting previous");
            cancelAutomation();
        }

        isAutomating = true;
        currentStep = 1;
        currentCallback = callback;

        Log.i(TAG, "Starting MFA automation for Almaviva...");

        // 1. Copy QR text to clipboard
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("MFA_QR", qrText != null ? qrText : "");
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
                Log.i(TAG, "QR Code text copied to clipboard successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy to clipboard: " + e.getMessage());
        }

        // 2. Launch Almaviva app
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(ALMAVIVA_PACKAGE);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(launchIntent);
                Log.i(TAG, "Almaviva app launched");
            } else {
                Log.e(TAG, "Almaviva package (" + ALMAVIVA_PACKAGE + ") not found on device!");
                finishAutomation(false, "تطبيق المافيفا غير مثبت على الهاتف");
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error launching Almaviva: " + e.getMessage());
            finishAutomation(false, "فشل تشغيل تطبيق المافيفا: " + e.getMessage());
            return;
        }

        // 3. Set safety timeout (25 seconds)
        timeoutRunnable = () -> {
            if (isAutomating) {
                Log.w(TAG, "Automation timed out at step " + currentStep);
                performGlobalAction(GLOBAL_ACTION_HOME);
                finishAutomation(false, "انتهت المهلة قبل إتمام جميع النقرات");
            }
        };
        handler.postDelayed(timeoutRunnable, 25000);

        // Schedule first check after 1.5s
        handler.postDelayed(this::evaluateCurrentStep, 1500);
    }

    private synchronized void evaluateCurrentStep() {
        if (!isAutomating) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            switch (currentStep) {
                case 1:
                    // Step 1: Click "امسح رمز الاستجابة السريعة"
                    if (findAndClick(root, "امسح رمز الاستجابة السريعة", "مسح رمز الاستجابة السريعة", "Scan QR code", "امسح رمز")) {
                        Log.i(TAG, "Step 1 Completed: Clicked Scan QR Code");
                        currentStep = 2;
                        handler.postDelayed(this::evaluateCurrentStep, 1000);
                    }
                    break;

                case 2:
                    // Step 2: Click "الصق القيمة المنسوخة"
                    if (findAndClick(root, "الصق القيمة المنسوخة", "لصق القيمة المنسوخة", "Paste copied value", "الصق")) {
                        Log.i(TAG, "Step 2 Completed: Clicked Paste Copied Value");
                        currentStep = 3;
                        handler.postDelayed(this::evaluateCurrentStep, 1000);
                    }
                    break;

                case 3:
                    // Step 3: Click "تأكيد"
                    if (findAndClick(root, "تأكيد", "تاكيد", "Confirm")) {
                        Log.i(TAG, "Step 3 Completed: Clicked Confirm");
                        currentStep = 4;
                        // Wait 2.5 seconds for confirmation dialog
                        handler.postDelayed(this::evaluateCurrentStep, 2500);
                    }
                    break;

                case 4:
                    // Step 4: Click "حسناً"
                    if (findAndClick(root, "حسناً", "حسنا", "OK", "Ok", "تم")) {
                        Log.i(TAG, "Step 4 Completed: Clicked OK dialog");
                        currentStep = 5;
                        handler.postDelayed(this::evaluateCurrentStep, 1000);
                    }
                    break;

                case 5:
                    // Step 5: Return to Home
                    Log.i(TAG, "Step 5: Returning to home screen");
                    performGlobalAction(GLOBAL_ACTION_HOME);
                    finishAutomation(true, "تم تأكيد الرمز والموافقة بنجاح 100%");
                    break;
            }
        } finally {
            root.recycle();
        }
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
        if (node.isClickable()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        AccessibilityNodeInfo parent = node.getParent();
        if (parent != null) {
            boolean res = performClickHierarchy(parent);
            parent.recycle();
            return res;
        }
        return false;
    }

    private synchronized void finishAutomation(boolean success, String message) {
        isAutomating = false;
        currentStep = 0;
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
        if (currentCallback != null) {
            AutomationCallback cb = currentCallback;
            currentCallback = null;
            cb.onCompleted(success, message);
        }
    }

    public synchronized void cancelAutomation() {
        isAutomating = false;
        currentStep = 0;
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
        currentCallback = null;
    }
}
