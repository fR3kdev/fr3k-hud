package com.mcpintelligence.fr3k.patchfixture;

import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Owned runtime fixture for resources, DEX defaults and inserted helper calls. */
public class ModLabActivity extends Activity {
    public static String mediaUrl() { return "https://example.org/video?utm_source=fixture&keep=yes#chapter"; }
    public static int playbackDefault() { return 3; }
    public static String contextDefault(Context context, String key) { return "normal"; }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.appmod_lab);
        ((TextView) findViewById(R.id.appmod_url)).setText(mediaUrl());
        ((TextView) findViewById(R.id.appmod_speed)).setText("Playback default: " + playbackDefault());
        ((TextView) findViewById(R.id.appmod_preference)).setText(contextDefault(this, "podcast-one"));
    }
}
