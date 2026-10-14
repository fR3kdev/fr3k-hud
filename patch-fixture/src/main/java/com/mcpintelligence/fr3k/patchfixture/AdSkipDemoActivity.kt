package com.mcpintelligence.fr3k.patchfixture

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Owned simulation of an in-stream ad with a skip control. It exists so the bridge plane can be
 * verified end to end without touching anybody else's application.
 */
class AdSkipDemoActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var countdown: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Simulated pre-roll"
            contentDescription = "ad-demo-title"
        })
        countdown = TextView(this).apply {
            text = "Ad · 0:05"
            contentDescription = "ad-demo-countdown"
        }
        root.addView(countdown)
        status = TextView(this).apply {
            text = "AD_PLAYING"
            contentDescription = "ad-demo-status"
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Skip Ad"
            contentDescription = "ad-demo-skip"
            setOnClickListener {
                status.text = "CONTENT_PLAYING"
                countdown.text = "Content · 0:00"
            }
        })
        root.addView(Button(this).apply {
            text = "Decoy"
            contentDescription = "ad-demo-decoy"
            setOnClickListener { status.text = "DECOY_CLICKED" }
        })
        setContentView(root)
    }
}
