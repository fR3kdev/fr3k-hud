package com.mcpintelligence.fr3k.mcp

import android.util.Log
import com.mcpintelligence.fr3k.appcontrol.CapabilityHost
import com.mcpintelligence.fr3k.appcontrol.PlanCompiler
import com.mcpintelligence.fr3k.bridge.Fr3kBridgeService
import com.mcpintelligence.fr3k.capability.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * Localhost-only capability surface. Every tool is closed-schema: no tool accepts a shell string,
 * a file path or a raw command. Bound to 127.0.0.1 and gated on a per-run bearer token.
 */
class Fr3kMcpServer(private val host: CapabilityHost, private val port: Int = 8791) {

    val token: String = UUID.randomUUID().toString().replace("-", "")
    private var server: ServerSocket? = null
    private var loop: Thread? = null

    @Volatile var running: Boolean = false
        private set

    @Volatile private var boundPort: Int = 0

    fun port(): Int = boundPort

    fun start(): Int {
        check(!running) { "already running" }
        val socket = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        server = socket
        running = true
        loop = Thread({
            while (running) {
                val client = try { socket.accept() } catch (closed: Exception) { break }
                runCatching { handle(client) }.onFailure { Log.i(TAG, "request failed: ${it.message}") }
            }
        }, "fr3k-mcp").apply { isDaemon = true; start() }
        boundPort = socket.localPort
        return boundPort
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        loop = null
    }

    fun toolList(): JSONArray = JSONArray().apply {
        put(tool("list_capabilities", "List registered capabilities for the apps installed on this device", JSONObject()))
        put(tool("get_app_identity", "Read the installed identity (version, signer, APK hashes) of one package",
            JSONObject().put("package", "string")))
        put(tool("render_plan", "Compile a plain-English goal into a reviewable plan", JSONObject().put("goal", "string")))
        put(tool("execute_plan", "Compile and run a plan after the operator approved it",
            JSONObject().put("goal", "string").put("approve", "bool")))
        put(tool("run_workflow", "Run a registered workflow by id", JSONObject().put("id", "string")))
        put(tool("read_ui_tree", "Read the accessibility tree of the foreground window", JSONObject()))
        put(tool("get_journal", "Return the evidence journal", JSONObject()))
        put(tool("bridge_status", "Report whether the accessibility bridge is connected and what it may act on", JSONObject()))
        put(tool("explore_app", "Record an app map for an installed package from its deeplink entries",
            JSONObject().put("package", "string").put("entries", "string")))
    }

    private fun tool(name: String, description: String, parameters: JSONObject): JSONObject =
        JSONObject().put("name", name).put("description", description).put("parameters", parameters)

    fun call(name: String, arguments: JSONObject): JSONObject = callBlock(name, arguments)

    private fun callBlock(name: String, arguments: JSONObject): JSONObject {
        return when (name) {
        "list_capabilities" -> JSONObject().put("capabilities", JSONArray().also { arr ->
            host.registry().all().forEach { entry ->
                arr.put(JSONObject().apply {
                    put("id", entry.descriptor.id)
                    put("summary", entry.descriptor.summary)
                    put("risk", entry.descriptor.risk.name)
                    put("package", entry.descriptor.identity.packageName)
                    put("fingerprint", entry.descriptor.identity.fingerprint)
                })
            }
        })
        "get_app_identity" -> {
            val pkg = arguments.optString("package")
            if (pkg.isBlank()) return error("package is required")
            val identity = runCatching { host.installed()[pkg] ?: android_identity(pkg) }.getOrElse { return error(it.message) }
            JSONObject().apply {
                put("package", identity.packageName)
                put("versionCode", identity.versionCode)
                put("signerSha256", identity.signerSha256)
                put("fingerprint", identity.fingerprint)
            }
        }
        "render_plan" -> {
            val goal = arguments.optString("goal")
            if (goal.isBlank()) return error("goal is required")
            val result = PlanCompiler(host).compile(goal)
            val plan = result.plan ?: return error(result.refusal ?: "no plan")
            JSONObject().put("ok", true).put("plan", PlanRenderer.render(plan, host.registry()))
                .put("capability", result.candidates.firstOrNull() ?: "")
        }
        "execute_plan" -> {
            val goal = arguments.optString("goal")
            if (goal.isBlank()) return error("goal is required")
            if (!arguments.optBoolean("approve", false)) return error("operator approval is required (approve=true)")
            val result = PlanCompiler(host).compile(goal)
            val plan = result.plan ?: return error(result.refusal ?: "no plan")
            val spec = WorkflowSpec(
                id = "mcp.plan",
                name = "MCP compiled plan for: $goal",
                version = 1,
                steps = plan.steps.map { WorkflowStep(it.capabilityId, it.identity, it.parameters) },
            )
            val report = runBlocking { host.workflowRunner().run(spec) }
            JSONObject().put("ok", report.ok).put("note", report.note).put("steps", report.records.size)
        }
        "run_workflow" -> {
            val id = arguments.optString("id").ifBlank { host.workflows().firstOrNull()?.id ?: "" }
            val spec = host.workflows().firstOrNull { it.id == id } ?: return error("no such workflow: $id")
            val report = runBlocking { host.workflowRunner().run(spec) }
            JSONObject().put("ok", report.ok).put("note", report.note).put("steps", report.records.size)
        }
        "read_ui_tree" -> {
            val tree = Fr3kBridgeService.instanceOrNull()?.readTree() ?: return error("accessibility service is not connected")
            JSONObject().put("package", Fr3kBridgeService.instanceOrNull()?.foregroundPackage() ?: "")
                .put("tree", SelectorMatcher.toJson(tree))
        }
        "bridge_status" -> JSONObject()
            .put("connected", Fr3kBridgeService.connected())
            .put("foregroundPackage", Fr3kBridgeService.instanceOrNull()?.foregroundPackage() ?: "")
            .put("maskCount", Fr3kBridgeService.instanceOrNull()?.maskCount ?: -1)
            .put("lastMaskBounds", Fr3kBridgeService.instanceOrNull()?.lastMaskBounds?.let {
                org.json.JSONArray(listOf(it.left, it.top, it.right, it.bottom))
            } ?: org.json.JSONArray())
        "explore_app" -> {
            val pkg = arguments.optString("package")
            if (pkg.isBlank()) return error("package is required")
            val service = Fr3kBridgeService.instanceOrNull() ?: return error("accessibility service is not connected")
            val identity = host.installed()[pkg] ?: return error("$pkg is not installed")
            val entries = arguments.optString("entries").split(";").filter { it.isNotBlank() }.map { pair ->
                val parts = pair.split("=")
                if (parts.size != 2) return error("entries must look like name=deeplink;name=deeplink")
                parts[0] to parts[1]
            }
            val result = runBlocking { com.mcpintelligence.fr3k.bridge.AppMapExplorer(service).explore(identity, entries, 0) }
            JSONObject().put("ok", true).put("screens", result.map.screens.map { it.name })
                .put("commands", com.mcpintelligence.fr3k.capability.MapCommandCompiler.compile(result.map).map { it.id })
                .put("notes", result.notes)
        }
        "get_journal" -> JSONObject().put("records", host.journalSnapshot().let { list ->
            JSONArray().also { arr -> list.forEach { arr.put(it.toJson()) } }
        })
            else -> error("unknown tool: $name")
        }
    }

    private fun android_identity(pkg: String): AppIdentity =
        host.identityReader().invoke(pkg)

    private fun error(message: String?): JSONObject =
        JSONObject().put("ok", false).put("error", message ?: "unknown error")

    private fun handle(client: Socket) {
        client.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val index = line.indexOf(':')
                if (index > 0) headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) CharArray(contentLength).also { reader.read(it) }.concatToString() else "{}"
            val parts = requestLine.split(" ")
            val method = parts.getOrElse(0) { "" }
            val path = parts.getOrElse(1) { "/" }
            val authorized = headers["authorization"]?.removePrefix("Bearer ")?.trim() == token
            var status = if (authorized) 200 else 401
            val response = try {
                when {
                    !authorized -> JSONObject().put("error", "token required")
                    method == "GET" && path == "/tools" -> JSONObject().put("tools", toolList())
                    method == "POST" && path.startsWith("/tools/") ->
                        call(path.removePrefix("/tools/"), runCatching { JSONObject(body) }.getOrElse { JSONObject() })
                    else -> JSONObject().put("error", "unsupported request")
                }
            } catch (failure: Throwable) {
                // A failing tool answers with the failure; it never leaves the caller without a reply.
                status = 500
                Log.i(TAG, "tool failed: ${failure.message}")
                JSONObject().put("ok", false)
                    .put("error", failure.message ?: failure.javaClass.simpleName)
                    .put("type", failure.javaClass.simpleName)
            }
            runCatching { respond(socket.getOutputStream(), response, status) }
        }
    }

    private fun respond(out: OutputStream, body: JSONObject, status: Int) {
        val payload = body.toString().toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status \r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(payload)
        out.flush()
    }

    companion object {
        const val TAG = "FR3KMCP"
    }
}
