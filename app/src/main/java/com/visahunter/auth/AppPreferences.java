package com.visahunter.auth;

import android.content.Context;
import android.content.SharedPreferences;

public class AppPreferences {
    private static final String PREF_NAME = "visa_auto_auth_prefs";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_CLIENT_EMAIL = "client_email";
    private static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";

    private final SharedPreferences prefs;

    public AppPreferences(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void setServerUrl(String url) {
        prefs.edit().putString(KEY_SERVER_URL, url.trim()).apply();
    }

    public String getServerUrl() {
        return prefs.getString(KEY_SERVER_URL, "http://192.168.1.3:3003");
    }

    public void setClientEmail(String email) {
        prefs.edit().putString(KEY_CLIENT_EMAIL, email.trim()).apply();
    }

    public String getClientEmail() {
        return prefs.getString(KEY_CLIENT_EMAIL, "");
    }

    public void setKeepScreenOn(boolean keepOn) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, keepOn).apply();
    }

    public boolean isKeepScreenOn() {
        return prefs.getBoolean(KEY_KEEP_SCREEN_ON, true); // Enabled by default
    }
}
