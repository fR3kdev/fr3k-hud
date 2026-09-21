package com.mcpintelligence.fr3k.integrations.blackwave

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * HTTP client that talks to the blackwave fleet bridge.
 *
 * Maintains three provider lambdas so the caller can supply endpoint,
 * credential, and client ID reactively (from settings / secure store /
 * identity). Each call to [fetchRole], [fetchFleetStatus], and
 * [fetchDeviceStatus] constructs the request from the current provider values.
 */
class BlackwaveBridgeClient(
    private val endpointProvider: () -> String,
    private val credentialProvider: () -> String?,
    private val clientIdProvider: () -> String,
) {
    /** Join a `/mobile/v1/...` path onto the (possibly trailing-slash) endpoint root. */
    private fun route(path: String): String {
        val root = endpointProvider().trimEnd('/')
        return if (path.startsWith("/")) "$root$path" else "$root/$path"
    }

    /**
     * Fetch the role manifest for this client identity.
     * @return Result with [BlackwaveRoleManifest] on success, failure on error/status <200..
     */
    suspend fun fetchRole(): Result<BlackwaveRoleManifest> {
        val response = get(route("mobile/v1/role"))
        return if (response.code in 200..299) {
            try {
                Result.success(json.decodeFromString(response.body))
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else {
            Result.failure(RuntimeException("HTTP ${response.code}: ${response.body.take(200)}"))
        }
    }

    /**
     * Fetch fleet status: list of all devices in the fleet.
     */
    suspend fun fetchFleetStatus(): Result<FleetStatusResponse> {
        val response = get(route("mobile/v1/fleet"))
        return if (response.code in 200..299) {
            try {
                Result.success(json.decodeFromString(response.body))
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else {
            Result.failure(RuntimeException("HTTP ${response.code}: ${response.body.take(200)}"))
        }
    }

    /**
     * Fetch detailed status for a specific device by model_id.
     */
    suspend fun fetchDeviceStatus(deviceId: String): Result<DeviceStatusResponse> {
        val response = get(route("mobile/v1/devices/${java.net.URLEncoder.encode(deviceId, "UTF-8")}"))
        return if (response.code in 200..299) {
            try {
                Result.success(json.decodeFromString(response.body))
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else {
            Result.failure(RuntimeException("HTTP ${response.code}: ${response.body.take(200)}"))
        }
    }

    /**
     * Classify wireless health without turning policy failures into transport
     * failures. USB fallback is permitted only for [WirelessHealth.UNREACHABLE].
     */
    fun probeHealth(): WirelessHealth {
        val response = get(route("mobile/v1/health"))
        return when {
            response.code in 200..299 -> WirelessHealth.HEALTHY
            response.code == 401 || response.code == 403 -> WirelessHealth.AUTH_FAILED
            response.failure != null -> response.failure
            else -> WirelessHealth.REMOTE_FAILED
        }
    }

    /** True only when the authenticated BLACKWAVE health endpoint is healthy. */
    fun isAvailable(): Boolean = probeHealth() == WirelessHealth.HEALTHY

    /**
     * Synchronous GET request. Uses [HttpURLConnection] with no
     * third-party dependencies. Sets auth and client-id headers.
     */
    private fun get(urlString: String): HttpResponse {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            if (this is HttpsURLConnection && isLoopbackHost(url.host)) {
                // Standalone phone mode: BLACKWAVE runs inside Termux on loopback
                // with its own self-signed certificate. Relax certificate-chain trust
                // only for loopback; the platform hostname verifier still requires
                // the certificate SAN to match 127.0.0.1/localhost. Remote endpoints
                // continue to use normal Android TLS trust (fail-closed).
                sslSocketFactory = loopbackSslSocketFactory
            }
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            credentialProvider()?.let { setRequestProperty("Authorization", "Bearer $it") }
            setRequestProperty("X-Blackwave-Client", clientIdProvider())
            // Disable redirect-following so we don't follow an HTTP→HTTPS
            // upgrade we can't verify (trust-on-first-use for LAN certs).
            instanceFollowRedirects = false
        }
        return try {
            val code = conn.responseCode
            val body = if (code in 200..299) {
                readStream(conn.inputStream)
            } else {
                readStream(conn.errorStream)
            }
            HttpResponse(code, body)
        } catch (e: Exception) {
            val failure = when (e) {
                is SSLHandshakeException,
                is SSLPeerUnverifiedException,
                is CertificateException -> WirelessHealth.TRUST_FAILED
                is ConnectException,
                is SocketTimeoutException,
                is UnknownHostException,
                is NoRouteToHostException -> WirelessHealth.UNREACHABLE
                else -> WirelessHealth.REMOTE_FAILED
            }
            Log.w(TAG, "GET $urlString failed [$failure]: ${e.message}")
            HttpResponse(0, e.message ?: "unknown error", failure)
        } finally {
            conn.disconnect()
        }
    }

    private fun readStream(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        return try {
            BufferedReader(InputStreamReader(stream)).use { it.readText() }
        } catch (_: Exception) { "" }
    }

    companion object {
        private const val TAG = "FR3K.blackwave"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 10_000

        /** True only for loopback hostnames/IPs (standalone-phone BLACKWAVE). */
        internal fun isLoopbackHost(host: String): Boolean =
            host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1"

        /**
         * Trust-any-cert chain for the loopback BLACKWAVE server only. Never
         * installed on a remote endpoint's connection — remote TLS uses Android's
         * normal trust anchors (fail-closed against self-signed remote certs).
         * The platform hostname verifier still enforces SAN == 127.0.0.1/localhost.
         */
        private val loopbackSslSocketFactory: SSLSocketFactory by lazy {
            val trustLoopbackCertificate = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            }
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustLoopbackCertificate), SecureRandom())
            }.socketFactory
        }

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        // Keep for integration tests / DI
        val serializer = { json }
    }
}

/**
 * Response from /mobile/v1/devices/{model_id}.
 */
@Serializable
data class DeviceStatusResponse(
    val identity: Map<String, JsonElement> = emptyMap(),
    val software: Map<String, String> = emptyMap(),
    val connectivity: Map<String, String> = emptyMap(),
    val hardware: List<String> = emptyList(),
    val battery: Map<String, String> = emptyMap(),
    val verification: Map<String, String> = emptyMap(),
    val power: Map<String, JsonElement> = emptyMap(),
    val validation: Map<String, JsonElement> = emptyMap(),
    val observed_at: String? = null,
    val observation_status: String = "unknown",
    val live: Boolean = false,
    val cached: Boolean = true,
    val identity_evidence: String = "unknown",
    val device_identity_verified: Boolean = false,
    val capability_evidence: String = "none",
    val catalog_capabilities: List<String> = emptyList(),
)

/**
 * Response from /mobile/v1/fleet.
 */
@Serializable
data class FleetStatusResponse(
    val accounted: Int = 0,
    val online: String = "0/0",
    val devices: List<FleetDeviceCard> = emptyList(),
    val stale: Boolean = false,
    val observed_at: String? = null,
    val observation_status: String = "unknown",
    val live: Boolean = false,
    val cached: Boolean = true,
)

/**
 * Single device card in the fleet list.
 */
@Serializable
data class FleetDeviceCard(
    val model_id: String = "",
    val display_name: String = "",
    val online: String = "unknown",
    val firmware_version: String = "",
    val enrollment: String = "",
    val device_class: String = "",
    val blackwave_authority: Boolean = false,
    val device_id: String? = null,
    val capabilities: List<String> = emptyList(),
    val capability_evidence: String = "none",
)

/**
 * Minimal HTTP response wrapper.
 */
internal data class HttpResponse(
    val code: Int,
    val body: String,
    val failure: WirelessHealth? = null,
)
