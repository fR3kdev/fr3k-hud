package com.mcpintelligence.fr3k.mcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mcpintelligence.fr3k.Fr3kApplication
import org.json.JSONObject
import java.io.File

/**
 * Starts and stops the localhost capability surface. The port and bearer token are written to the
 * app's private files directory so an authorised host can read them with `run-as`.
 */
class McpControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? Fr3kApplication ?: return
        when (intent.action) {
            ACTION_START -> {
                val port = app.startMcp()
                val file = File(context.filesDir, "mcp.json")
                file.writeText(
                    JSONObject().put("port", port).put("token", app.mcpServer.token)
                        .put("pid", android.os.Process.myPid()).toString(),
                )
                Log.i(TAG, "MCP_STARTED port=$port pid=${android.os.Process.myPid()}")
            }
            ACTION_STOP -> {
                app.stopMcp()
                Log.i(TAG, "MCP_STOPPED")
            }
        }
    }

    companion object {
        const val TAG = "FR3KMCP"
        const val ACTION_START = "com.mcpintelligence.fr3k.hud.START_MCP"
        const val ACTION_STOP = "com.mcpintelligence.fr3k.hud.STOP_MCP"
    }
}
