package com.visahunter.auth;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ClientPollService extends Service {
    private static final String TAG = "ClientPollService";
    private static final String CHANNEL_ID = "visa_auth_channel";
    private static final int NOTIF_ID = 1001;

    private static boolean isConnectedToServer = false;
    private static String lastLogMessage = "جاري بدء الخدمة...";

    public interface StateListener {
        void onStateChanged(boolean connected, String log);
    }

    private static StateListener stateListener;

    public static void setStateListener(StateListener listener) {
        stateListener = listener;
        if (listener != null) {
            listener.onStateChanged(isConnectedToServer, lastLogMessage);
        }
    }

    public static boolean isConnected() {
        return isConnectedToServer;
    }

    private OkHttpClient httpClient;
    private Thread pollThread;
    private boolean isRunning = false;
    private AppPreferences preferences;

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = new AppPreferences(this);

        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("جاري الاتصال بسيرفر المواعيد..."));

        isRunning = true;
        startPollingLoop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRunning) {
            isRunning = true;
            startPollingLoop();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (pollThread != null) {
            pollThread.interrupt();
        }
        updateState(false, "تم إيقاف الخدمة");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startPollingLoop() {
        pollThread = new Thread(() -> {
            long lastHeartbeat = 0;

            while (isRunning) {
                String serverUrl = preferences.getServerUrl();
                String email = preferences.getClientEmail();

                if (email.isEmpty() || serverUrl.isEmpty()) {
                    updateState(false, "⚠️ يرجى إدخال البريد الإلكتروني وعنوان السيرفر أولاً");
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        break;
                    }
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
                    String pollEndpoint = cleanUrl + "/api/client-app/poll?email=" + java.net.URLEncoder.encode(email, "UTF-8");

                    Request request = new Request.Builder()
                            .url(pollEndpoint)
                            .get()
                            .build();

                    try (Response response = httpClient.newCall(request).execute()) {
                        if (response.isSuccessful() && response.body() != null) {
                            String responseBody = response.body().string();
                            updateState(true, "🟢 متصل بالسيرفر (في انتظار طلبات MFA)");

                            JSONObject json = new JSONObject(responseBody);
                            String action = json.optString("action", "");

                            if ("TRIGGER_MFA".equals(action)) {
                                String qrText = json.optString("qrText", "");
                                String challengeId = json.optString("challengeId", "");
                                updateState(true, "📲 استلام طلب تأكيد MFA (ID: " + challengeId + ")...");

                                executeMfa(cleanUrl, email, qrText, challengeId);
                            }
                        } else {
                            updateState(false, "🔴 السيرفر غير مستجيب (كود " + response.code() + ")");
                            Thread.sleep(3000);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Poll error: " + e.getMessage());
                    updateState(false, "🔴 تعذر الاتصال بالسيرفر: " + e.getMessage());
                    try {
                        Thread.sleep(4000);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        });
        pollThread.start();
    }

    private void executeMfa(String serverUrl, String email, String qrText, String challengeId) {
        if (!AlmavivaAccessibilityService.isRunning()) {
            updateState(true, "❌ إذن النقر التلقائي غير مفعل! يرجى تفعيله من الإعدادات");
            sendAck(serverUrl, email, challengeId, false, "AccessibilityService is disabled on phone");
            return;
        }

        AlmavivaAccessibilityService service = AlmavivaAccessibilityService.getInstance();
        service.startMfaAutomation(qrText, (success, message) -> {
            updateState(true, success ? "✅ تم التأكيد بنجاح: " + message : "⚠️ فشل التأكيد: " + message);
            sendAck(serverUrl, email, challengeId, success, message);
        });
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
                    Log.i(TAG, "ACK sent: " + r.code());
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to send ACK: " + e.getMessage());
            }
        }).start();
    }

    private void sendHeartbeat(String serverUrl, String email) {
        new Thread(() -> {
            try {
                BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                int batteryLevel = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;

                JSONObject payload = new JSONObject();
                payload.put("email", email);
                payload.put("accessibilityEnabled", AlmavivaAccessibilityService.isRunning());
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
                    Log.d(TAG, "Heartbeat sent: " + r.code());
                }
            } catch (Exception e) {
                Log.d(TAG, "Heartbeat failed: " + e.getMessage());
            }
        }).start();
    }

    private void updateState(boolean connected, String log) {
        isConnectedToServer = connected;
        lastLogMessage = log;
        if (stateListener != null) {
            stateListener.onStateChanged(connected, log);
        }
        updateNotification(log);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "خدمة مساعد التأشيرات",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("إشعار الخلفية المستمر لضمان سرعة تأكيد المواعيد");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notifIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("مساعد تأشيرات المافيفا")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(text));
        }
    }
}
