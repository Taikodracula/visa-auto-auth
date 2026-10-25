package com.visahunter.auth;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateManager {

    private static final String DEFAULT_UPDATE_URL = "http://15.161.39.53:3003/api/app-version";
    private static final String FALLBACK_UPDATE_URL = "http://164.132.41.46:3003/api/app-version";
    private static final String DIRECT_APK_FALLBACK = "http://15.161.39.53:3003/VisaAutoAuth-v1.0.apk";

    public interface UpdateCheckCallback {
        void onResult(boolean hasUpdate, String latestVersion, String notes);
    }

    public static void checkForUpdates(Activity activity, boolean userInitiated, UpdateCheckCallback callback) {
        if (activity == null || activity.isFinishing()) return;

        ProgressDialog checkingDialog = null;
        if (userInitiated) {
            checkingDialog = new ProgressDialog(activity);
            checkingDialog.setMessage("🔄 جاري التحقق من وجود تحديث جديد...");
            checkingDialog.setCancelable(false);
            checkingDialog.show();
        }

        final ProgressDialog fCheckingDialog = checkingDialog;
        Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            JSONObject updateData = fetchVersionInfo(DEFAULT_UPDATE_URL);
            if (updateData == null) {
                updateData = fetchVersionInfo(FALLBACK_UPDATE_URL);
            }

            final JSONObject result = updateData;
            mainHandler.post(() -> {
                if (fCheckingDialog != null && fCheckingDialog.isShowing()) {
                    try { fCheckingDialog.dismiss(); } catch (Exception ignored) {}
                }

                if (activity.isFinishing()) return;

                int currentVersionCode = getCurrentVersionCode(activity);
                String currentVersionName = getCurrentVersionName(activity);

                if (result == null) {
                    if (userInitiated) {
                        showOfflineDownloadDialog(activity, currentVersionName);
                    }
                    if (callback != null) callback.onResult(false, currentVersionName, null);
                    return;
                }

                int serverVersionCode = result.optInt("versionCode", currentVersionCode);
                String serverVersionName = result.optString("versionName", currentVersionName);
                String apkUrl = result.optString("apkUrl", DIRECT_APK_FALLBACK);
                String notes = result.optString("notes", "تحسينات عامة في الأداء وسرعة الاستجابة");

                boolean hasUpdate = serverVersionCode > currentVersionCode;

                if (hasUpdate) {
                    showNewVersionDialog(activity, serverVersionName, notes, apkUrl);
                } else {
                    if (userInitiated) {
                        showUpToDateDialog(activity, currentVersionName, apkUrl);
                    }
                }

                if (callback != null) {
                    callback.onResult(hasUpdate, serverVersionName, notes);
                }
            });
        }).start();
    }

    private static JSONObject fetchVersionInfo(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");

            if (conn.getResponseCode() == 200) {
                InputStream is = conn.getInputStream();
                java.util.Scanner s = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
                String response = s.hasNext() ? s.next() : "";
                is.close();
                return new JSONObject(response);
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static void showNewVersionDialog(Activity activity, String newVersion, String notes, String apkUrl) {
        new AlertDialog.Builder(activity)
                .setTitle("🚀 توفر تحديث جديد (" + newVersion + ")")
                .setMessage("يتوفر إصدار جديد من تطبيق مساعد تأشيرات المافيفا!\n\n"
                        + "الملاحظات:\n• " + notes + "\n\n"
                        + "💡 تنبيه هام بعد تثبيت التحديث:\n"
                        + "يجب عليك إيقاف إذن النقر وإعادة تشغيله مرة أخرى واحدة في الإعدادات بعد تثبيت التحديث.")
                .setPositiveButton("تثبيت التحديث الآن ⚡", (dialog, which) -> {
                    downloadAndInstall(activity, apkUrl, newVersion);
                })
                .setNeutralButton("تحميل عبر المتصفح 🌐", (dialog, which) -> {
                    openBrowserDownload(activity, apkUrl);
                })
                .setNegativeButton("لاحقاً", null)
                .setCancelable(true)
                .show();
    }

    private static void showUpToDateDialog(Activity activity, String currentVersion, String apkUrl) {
        new AlertDialog.Builder(activity)
                .setTitle("✅ التطبيق محدث لأحدث إصدار")
                .setMessage("أنت تستخدم أحدث إصدار بالفعل (" + currentVersion + ") ولا توجد تحديثات جديدة حالياً.\n\n"
                        + "هل تواجه مشكلة وترغب في إعادة تحميل النسخة وتثبيتها مجدداً؟\n\n"
                        + "💡 إرشادات:\nيجب عليك إيقاف إذن النقر وإعادة تشغيله مرة أخرى واحدة في الإعدادات بعد تثبيت التحديث.")
                .setPositiveButton("إعادة التثبيت الآن", (dialog, which) -> {
                    downloadAndInstall(activity, apkUrl, currentVersion);
                })
                .setNeutralButton("تحميل يدوي", (dialog, which) -> {
                    openBrowserDownload(activity, apkUrl);
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private static void showOfflineDownloadDialog(Activity activity, String currentVersion) {
        new AlertDialog.Builder(activity)
                .setTitle("📥 تحميل أحدث نسخة")
                .setMessage("الإصدار الحالي مثبت: " + currentVersion + "\n\n"
                        + "يمكنك تحميل أحدث نسخة من السيرفر مباشرة عبر المتصفح وتثبيتها فوراً.\n\n"
                        + "💡 إرشادات:\nيجب عليك إيقاف إذن النقر وإعادة تشغيله مرة أخرى واحدة في الإعدادات بعد تثبيت التحديث.")
                .setPositiveButton("فتح رابط التحميل", (d, w) -> openBrowserDownload(activity, DIRECT_APK_FALLBACK))
                .setNegativeButton("إلغاء", null)
                .show();
    }

    public static void downloadAndInstall(Activity activity, String apkUrl, String targetVersion) {
        ProgressDialog downloadDialog = new ProgressDialog(activity);
        downloadDialog.setTitle("📲 جاري تنزيل التحديث (" + targetVersion + ")");
        downloadDialog.setMessage("يرجى الانتظار، جاري تحميل ملف التثبيت...");
        downloadDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        downloadDialog.setMax(100);
        downloadDialog.setProgress(0);
        downloadDialog.setCancelable(false);
        downloadDialog.show();

        Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            File outputFile = null;
            try {
                File cacheDir = activity.getExternalCacheDir();
                if (cacheDir == null) {
                    cacheDir = activity.getCacheDir();
                }
                outputFile = new File(cacheDir, "VisaAutoAuth_Latest.apk");
                if (outputFile.exists()) {
                    outputFile.delete();
                }

                URL url = new URL(apkUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                int fileLength = conn.getContentLength();
                InputStream input = conn.getInputStream();
                FileOutputStream output = new FileOutputStream(outputFile);

                byte[] data = new byte[8192];
                long total = 0;
                int count;
                int lastPercent = 0;

                while ((count = input.read(data)) != -1) {
                    total += count;
                    output.write(data, 0, count);

                    if (fileLength > 0) {
                        int percent = (int) (total * 100 / fileLength);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            mainHandler.post(() -> downloadDialog.setProgress(percent));
                        }
                    }
                }

                output.flush();
                output.close();
                input.close();
                conn.disconnect();

                final File finalFile = outputFile;
                mainHandler.post(() -> {
                    try { downloadDialog.dismiss(); } catch (Exception ignored) {}
                    launchInstaller(activity, finalFile, apkUrl);
                });

            } catch (Exception e) {
                final String errMsg = e.getMessage();
                mainHandler.post(() -> {
                    try { downloadDialog.dismiss(); } catch (Exception ignored) {}
                    Toast.makeText(activity, "⚠️ تعذر التحميل المباشر: " + errMsg, Toast.LENGTH_LONG).show();
                    openBrowserDownload(activity, apkUrl);
                });
            }
        }).start();
    }

    private static void launchInstaller(Activity activity, File apkFile, String fallbackUrl) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!activity.getPackageManager().canRequestPackageInstalls()) {
                    new AlertDialog.Builder(activity)
                            .setTitle("⚙️ إذن تثبيت التحديثات")
                            .setMessage("يرجى تفعيل (السماح بتثبيت التطبيقات من هذا المصدر) لكي يتم تحديث التطبيق تلقائياً.")
                            .setPositiveButton("فتح الإعدادات", (dialog, which) -> {
                                try {
                                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                                    intent.setData(Uri.parse("package:" + activity.getPackageName()));
                                    activity.startActivity(intent);
                                } catch (Exception e) {
                                    startInstallIntent(activity, apkFile);
                                }
                            })
                            .setNegativeButton("إلغاء", (dialog, which) -> {
                                openBrowserDownload(activity, fallbackUrl);
                            })
                            .show();
                    return;
                }
            }

            startInstallIntent(activity, apkFile);

        } catch (Exception e) {
            Toast.makeText(activity, "خطأ في تشغيل المثبت: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            openBrowserDownload(activity, fallbackUrl);
        }
    }

    private static void startInstallIntent(Context context, File apkFile) {
        Uri apkUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", apkFile);
        } else {
            apkUri = Uri.fromFile(apkFile);
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void openBrowserDownload(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "تعذر فتح المتصفح: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public static int getCurrentVersionCode(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionCode;
        } catch (Exception e) {
            return 25;
        }
    }

    public static String getCurrentVersionName(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return "v" + pInfo.versionName;
        } catch (Exception e) {
            return "v1.7.0";
        }
    }
}
