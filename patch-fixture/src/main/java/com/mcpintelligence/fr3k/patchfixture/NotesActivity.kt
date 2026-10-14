package com.mcpintelligence.fr3k.patchfixture

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** A third owned screen: gives the bridge plane a text-entry target and the map a third node. */
class NotesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Owned notes screen"
            contentDescription = "notes-title"
        })
        val field = EditText(this).apply { contentDescription = "notes-field" }
        root.addView(field)
        val status = TextView(this).apply {
            text = "EMPTY"
            contentDescription = "notes-status"
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Save note"
            contentDescription = "notes-save"
            setOnClickListener { status.text = "SAVED" }
        })
        setContentView(root)
    }
}
