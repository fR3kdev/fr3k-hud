package com.fr3k.appmodpayload;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

/** Compiled during HUD's development build; never compiled or dynamically loaded by HUD at runtime. */
public final class Helpers {
    private Helpers() {}
    public static String cleanUrl(String source) {
        if (source == null) return null;
        try {
            Uri uri = Uri.parse(source);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) return source;
            Set<String> keys = new HashSet<>(Arrays.asList("utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "fbclid", "gclid"));
            String query = uri.getEncodedQuery();
            if (query == null) return source;
            StringBuilder result = new StringBuilder();
            for (String part : query.split("&", -1)) {
                String name = Uri.decode(part.split("=", 2)[0]);
                if (keys.contains(name)) continue;
                if (result.length() > 0) result.append('&');
                result.append(part);
            }
            return uri.buildUpon().encodedQuery(result.length() == 0 ? null : result.toString()).build().toString();
        } catch (RuntimeException failure) { return source; }
    }
    public static String persist(Context context, String contextKey, String current) {
        if (context == null || contextKey == null || current == null) return current;
        SharedPreferences prefs = context.getSharedPreferences("hud_appmod_defaults", Context.MODE_PRIVATE);
        if (!prefs.contains(contextKey)) prefs.edit().putString(contextKey, current).apply();
        return prefs.getString(contextKey, current);
    }
    public static String share(Context context, String current) {
        if (context == null || current == null) return current;
        Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain").setPackage("com.mcpintelligence.fr3k.hud")
            .putExtra(Intent.EXTRA_TEXT, current).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { context.startActivity(intent); } catch (android.content.ActivityNotFoundException ignored) {}
        return current;
    }
}
