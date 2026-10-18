package com.mcpintelligence.fr3k.ui.blackwave

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun LocalAssociationGuide() {
    val context = LocalContext.current
    var guide by remember { mutableStateOf<JSONObject?>(null) }
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(context) {
        guide = withContext(Dispatchers.IO) { JSONObject(context.assets.open("guides/local-association-v1.json").bufferedReader().use { it.readText() }) }
    }
    Column {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Collapse connection guide" else "Connection guide · v1") }
        if (expanded) guide?.let { g ->
            Text(g.getString("explanation"))
            val prerequisites = g.getJSONArray("prerequisites")
            for (i in 0 until prerequisites.length()) Text("Required: ${prerequisites.getString(i)}")
            val steps = g.getJSONArray("steps")
            for (i in 0 until steps.length()) Text("${i + 1}. ${steps.getJSONObject(i).getString("instruction")}")
            Text("Expected: ${g.getString("expected")}")
            Text("If it fails: ${g.getString("failure")}")
            Text("Undo: ${g.getString("recovery")}")
        }
    }
}
