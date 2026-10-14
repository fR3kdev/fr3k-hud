package com.mcpintelligence.fr3k.patchfixturetwo

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Owned second target: exists so cross-app workflows are tested across two real packages. */
class TargetTwoActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "fR3k owned target two"
            contentDescription = "target-two-title"
        })
        root.addView(TextView(this).apply {
            text = "pid=${android.os.Process.myPid()}"
            contentDescription = "target-two-pid"
        })
        status = TextView(this).apply {
            text = "READY"
            contentDescription = "target-two-status"
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "ACKNOWLEDGE"
            contentDescription = "target-two-ack"
            setOnClickListener {
                getSharedPreferences("target-two", Context.MODE_PRIVATE).edit()
                    .putBoolean("acknowledged", true).commit()
                status.text = "ACKNOWLEDGED"
            }
        })
        setContentView(root)
    }
}
