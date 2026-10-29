package com.mcpintelligence.fr3k.integrations.xiaozhi

import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Real HttpsURLConnection, with an ephemeral loopback server and test-only trust. */
class BootstrapHttpsIntegrationTest {
    private fun withServer(trustServer: Boolean = true, block: (MockWebServer) -> Unit) {
        val dir = kotlin.io.path.createTempDirectory("bootstrap-tls-").toFile()
        val storeFile = File(dir, "server.p12")
        val password = "test-only".toCharArray()
        val original = HttpsURLConnection.getDefaultSSLSocketFactory()
        val server = MockWebServer()
        try {
            val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
            val process = ProcessBuilder(keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keystore", storeFile.absolutePath, "-storetype", "PKCS12", "-storepass", String(password), "-keypass", String(password), "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "1", "-noprompt").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
            val keys = KeyStore.getInstance("PKCS12").apply { storeFile.inputStream().use { load(it, password) } }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
            val serverTls = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
            server.useHttps(serverTls.socketFactory, false)
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            if (trustServer) {
                val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null); setCertificateEntry("test-server", keys.getCertificate("server")) }
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
                HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory)
            }
            block(server)
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(original)
            server.shutdown()
            dir.deleteRecursively()
        }
    }

    private fun endpoint(server: MockWebServer) = "https://localhost:${server.port}/bootstrap"
    private fun discover(url: String) = BootstrapClient().discover(url, "device", "client", "{\"board\":\"test\"}")

    @Test fun realHttpsPostSendsHeadersAndBodyAndParsesReady() = withServer { server ->
        server.enqueue(MockResponse().setBody("""{"websocket":{"url":"wss://example.invalid/voice"}}"""))
        assertTrue(discover(endpoint(server)) is Bootstrap.Result.Ready)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/bootstrap", request.path)
        assertEquals("device", request.getHeader("Device-Id"))
        assertEquals("client", request.getHeader("Client-Id"))
        assertEquals("1", request.getHeader("Activation-Version"))
        assertEquals("application/json", request.getHeader("Content-Type"))
        assertEquals("{\"board\":\"test\"}", request.body.readUtf8())
    }

    @Test fun untrustedCertificateIsRejected() = withServer(trustServer = false) { server ->
        server.enqueue(MockResponse().setBody("{}"))
        assertSame(Bootstrap.Result.Invalid, discover(endpoint(server)))
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun hostnameMismatchIsRejectedEvenWhenCertificateTrusted() = withServer { server ->
        server.enqueue(MockResponse().setBody("{}"))
        assertSame(Bootstrap.Result.Invalid, discover("https://127.0.0.1:${server.port}/bootstrap"))
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun oversizedChunkedResponseIsRejected() = withServer { server ->
        val validBody = """{"websocket":{"url":"wss://example.invalid/voice"},"padding":"""" + "é".repeat(40000) + "\"}"
        assertTrue(validBody.length < 65536)
        assertTrue(validBody.toByteArray(Charsets.UTF_8).size > 65536)
        assertTrue(BootstrapClient { _, _, _ -> BootstrapClient.Response(200, validBody) }
            .discover("https://example.invalid", "device", "client", "{}") is Bootstrap.Result.Ready)
        server.enqueue(MockResponse().setChunkedBody(validBody, 1024))
        assertSame(Bootstrap.Result.Invalid, discover(endpoint(server)))
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
    }

    @Test fun redirectsAreRejectedWithoutFollowingLocation() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://localhost:${server.port}/redirected"))
        server.enqueue(MockResponse().setBody("""{"websocket":{"url":"wss://example.invalid/voice"}}"""))
        assertSame(Bootstrap.Result.Invalid, discover(endpoint(server)))
        assertEquals("/bootstrap", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }
}
