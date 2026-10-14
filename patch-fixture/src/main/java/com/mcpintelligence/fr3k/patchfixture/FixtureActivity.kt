package com.mcpintelligence.fr3k.patchfixture

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** No exported control API, accounts, network or third-party dependencies. */
class FixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }
        val identity = TextView(this).apply {
            text = "fR3k owned patch fixture\nPID ${Process.myPid()}\nBaseline only — no hook runtime installed"
            textSize = 18f
        }
        val banner = TextView(this).apply {
            contentDescription = "fixture-owned-banner"
            text = "OWNED TEST BANNER — not an advertisement"
            textSize = 20f
        }
        fun refresh() {
            banner.visibility = if (DemoFeatures.showBanner()) View.VISIBLE else View.GONE
        }
        root.addView(identity)
        root.addView(banner)
        root.addView(Button(this).apply {
            text = "Re-evaluate fixture method"
            setOnClickListener { refresh() }
        })
        refresh()
        setContentView(root)
    }
}
