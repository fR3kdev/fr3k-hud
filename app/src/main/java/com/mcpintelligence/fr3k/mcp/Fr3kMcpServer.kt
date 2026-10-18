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

    @Volatile var token: String = UUID.randomUUID().toString().replace("-", "")
        private set

    // A caller's "approve":true is not an operator approval. Exact reviewed goal/id,
    // single use, limited to 2 minutes, and approved only from an in-app control.
    private data class WriteReview(
        val kind: String, val exactTarget: String, val summary: String,
        val expires: Long, val operatorApproved: Boolean = false
    )
    @Volatile private var pendingWrite: WriteReview? = null

    @Synchronized fun pendingWriteSummary(): String? =
        pendingWrite?.takeIf { android.os.SystemClock.elapsedRealtime() < it.expires }
            ?.let { it.kind + ": " + it.summary.take(500) + (if (it.operatorApproved) " · APPROVED" else " · NOT APPROVED") }

    @Synchronized fun approvePendingWrite(): Boolean {
        val request = pendingWrite ?: return false
        if (android.os.SystemClock.elapsedRealtime() >= request.expires) { pendingWrite = null; return false }
        pendingWrite = request.copy(operatorApproved = true)
        return true
    }

    @Synchronized fun denyPendingWrite() { pendingWrite = null }

    @Synchronized private fun recordWriteReview(kind: String, exact: String, summary: String) {
        pendingWrite = WriteReview(kind, exact, summary, android.os.SystemClock.elapsedRealtime() + 120_000)
    }

    @Synchronized private fun consumeWriteReview(kind: String, exact: String): Boolean {
        val request = pendingWrite ?: return false
        pendingWrite = null
        return request.operatorApproved && request.kind == kind && request.exactTarget == exact &&
            android.os.SystemClock.elapsedRealtime() < request.expires
    }
    val diagnostics: HudDiagnosticSurface by lazy {
        HudDiagnosticSurface(com.mcpintelligence.fr3k.Fr3kApplication.get(), this)
    }
    private var server: ServerSocket? = null
    private var loop: Thread? = null

    @Volatile var running: Boolean = false
        private set

    @Volatile private var boundPort: Int = 0

    fun port(): Int = boundPort

    fun start(): Int {
        check(!running) { "already running" }
        token = UUID.randomUUID().toString().replace("-", "")
        denyPendingWrite()
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
        if (running) diagnostics.revokeControls()
        denyPendingWrite()
        running = false
        runCatching { server?.close() }
        server = null
        loop = null
    }

    /** The application context, used for the live device read the guides report. */
    private val context: android.content.Context
        get() = com.mcpintelligence.fr3k.Fr3kApplication.get()

    fun toolList(): JSONArray = JSONArray().apply {
        listOf("analyze_apk", "suggest_app_mods", "preview_app_mods", "request_app_mod_preparation", "get_app_mod_job").forEach { name ->
            val params = JSONObject()
            com.mcpintelligence.fr3k.core.tools.AgentToolSchema.parameterNames("app.mods.$name").forEach { params.put(it, "string") }
            put(tool(name, "App modification: $name. Exact user review required before preparation.", params))
        }
        put(tool("list_hud_screens", "Enumerate internal HUD screens and manifest presence", JSONObject()))
        put(tool("get_hud_diagnostics", "Read-only HUD MCP, bridge, diagnostic control status", JSONObject()))
        put(tool("run_hud_selftest", "Bounded evidence checks on this HUD process", JSONObject()))
        put(tool("inspect_hud_ui", "Redacted clickable-node inventory of the HUD foreground only", JSONObject()))
        put(tool("open_hud_screen", "Navigate to internal screen after locally enabled 10-minute diagnostic session",
            JSONObject().put("id", "screen ID")))
        put(tool("press_hud_control", "Tap non-sensitive HUD control by exact accessibility path after local approval",
            JSONObject().put("path", "index path")))
        put(tool("list_capabilities", "List registered capabilities for the apps installed on this device", JSONObject()))
        put(tool("get_app_identity", "Read the installed identity (version, signer, APK hashes) of one package",
            JSONObject().put("package", "string")))
        put(tool("render_plan", "Compile a plain-English goal into a reviewable plan", JSONObject().put("goal", "string")))
        put(tool("execute_plan", "Compile and run a plan after the operator approved it",
            JSONObject().put("goal", "string").put("approve", "bool")))
        put(tool("preview_workflow", "View a registered workflow for explicit single-use on-screen approval",
            JSONObject().put("id", "exact workflow id")))
        put(tool("run_workflow", "Run a workflow only after in-app review and approval",
            JSONObject().put("id", "exact workflow id")))
        put(tool("read_ui_tree", "Read the accessibility tree of the foreground window", JSONObject()))
        put(tool("get_journal", "Return the evidence journal", JSONObject()))
        put(tool("bridge_status", "Report whether the accessibility bridge is connected and what it may act on", JSONObject()))
        put(tool("explore_app", "Record an app map for an installed package from its deeplink entries",
            JSONObject().put("package", "string").put("entries", "string")))
        put(tool("get_guides", "Return every guide: what a capability does, what it requires, the walkthrough, " +
            "the phrase that runs it, its limits and how to undo it",
            JSONObject().put("category", "string (optional: INSPECT, MODIFY, AUTOMATE, COMBINE, CONNECT)").put("id", "string (optional)")))
    }

    private fun tool(name: String, description: String, parameters: JSONObject): JSONObject =
        JSONObject().put("name", name).put("description", description).put("parameters", parameters)

    fun call(name: String, arguments: JSONObject): JSONObject = callBlock(name, arguments)

    private fun callBlock(name: String, arguments: JSONObject): JSONObject {
        if (name in setOf("analyze_apk", "suggest_app_mods", "preview_app_mods", "request_app_mod_preparation", "get_app_mod_job")) {
            return try { runBlocking { com.mcpintelligence.fr3k.appcontrol.AppModApi(context).call(name, arguments) } }
            catch (failure: Exception) { error(failure.message) }
        }
        return when (name) {
        "list_hud_screens" -> diagnostics.screens()
        "get_hud_diagnostics" -> diagnostics.status()
        "run_hud_selftest" -> diagnostics.selfTest()
        "inspect_hud_ui" -> diagnostics.inspectUi()
        "open_hud_screen" -> diagnostics.openScreen(arguments.optString("id"))
        "press_hud_control" -> diagnostics.clickControl(arguments.optString("path"))
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
        "get_guides" -> {
            val wanted = arguments.optString("category").trim().uppercase()
            val only = arguments.optString("id").trim()
            var guides = com.mcpintelligence.fr3k.appcontrol.ModGuideCatalogue.all(context)
            if (wanted.isNotEmpty()) guides = guides.filter { it.category.label == wanted }
            if (only.isNotEmpty()) guides = guides.filter { it.id == only }
            if (guides.isEmpty()) return error("no guide matches that filter")
            JSONObject().put("ok", true).put("count", guides.size)
                .put("guides", com.mcpintelligence.fr3k.appcontrol.ModGuideCatalogue.toJson(guides))
                .put(
                    "readiness",
                    JSONObject().let { state ->
                        val env = com.mcpintelligence.fr3k.appcontrol.ModGuideCatalogue.environment(context)
                        state.put("bridgeBound", env.bridgeBound)
                            .put("privilegedShell", env.privilegedShell)
                            .put("privilegedRoot", env.privilegedRoot)
                    },
                )
        }
        "render_plan" -> {
            val goal = arguments.optString("goal")
            if (goal.isBlank()) return error("goal is required")
            val result = PlanCompiler(host).compile(goal)
            val plan = result.plan ?: return error(result.refusal ?: "no plan")
            val summary = PlanRenderer.render(plan, host.registry())
            recordWriteReview("plan", goal, summary)
            JSONObject().put("ok", true).put("plan", summary)
                .put("operatorApproval", "PENDING_IN_HUD")
                .put("capability", result.candidates.firstOrNull() ?: "")
        }
        "execute_plan" -> {
            val goal = arguments.optString("goal")
            if (goal.isBlank()) return error("goal is required")
            if (!arguments.optBoolean("approve", false) || !consumeWriteReview("plan", goal))
                return error("Exact plan requires fresh operator approval in the HUD Agent screen (single use)")
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
        "preview_workflow" -> {
            val id = arguments.optString("id")
            val spec = host.workflows().firstOrNull { it.id == id } ?: return error("no such workflow")
            recordWriteReview("workflow", id, spec.name + " (" + spec.steps.size + " steps)")
            JSONObject().put("ok", true).put("id", spec.id).put("name", spec.name)
                .put("steps", spec.steps.size).put("operatorApproval", "PENDING_IN_HUD")
        }
        "run_workflow" -> {
            val id = arguments.optString("id")
            if (!consumeWriteReview("workflow", id))
                return error("Exact workflow requires fresh operator approval in the HUD Agent screen")
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
            return error("EXPLICIT_OPERATOR_REVIEW_REQUIRED: external-app exploration is not included in HUD-only diagnostic approval")
            @Suppress("UNREACHABLE_CODE")
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
            if (pkg in com.mcpintelligence.fr3k.appcontrol.CapabilityCatalogue.selectedPackages(context)) {
                com.mcpintelligence.fr3k.appcontrol.UserAppMaps.save(context, result.map)
                host.reset()
            }
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
