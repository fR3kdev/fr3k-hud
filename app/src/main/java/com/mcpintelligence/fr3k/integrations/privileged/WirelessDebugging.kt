package com.mcpintelligence.fr3k.integrations.privileged

import android.content.Context
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.security.*
import java.security.cert.Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** A private paired ADB identity. Pairing codes never leave this process or enter preferences/logs. */
class WirelessDebugging(context: Context) : AbsAdbConnectionManager() {
    private val prefs = EncryptedSharedPreferences.create(context, "wireless-adb-identity",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    private val entry: Pair<PrivateKey, Certificate>
    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(15, TimeUnit.SECONDS)
        val decoder = java.util.Base64.getDecoder()
        val storedKey = prefs.getString("key", null)
        val storedCert = prefs.getString("cert", null)
        entry = if (storedKey != null && storedCert != null) {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(decoder.decode(storedKey))) to
                java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(decoder.decode(storedCert).inputStream())
        } else {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=fR3k wireless debugging")
            val now = System.currentTimeMillis()
            val cert = JcaX509CertificateConverter().getCertificate(JcaX509v3CertificateBuilder(name,
                java.math.BigInteger(128, SecureRandom()), java.util.Date(now - 60000),
                java.util.Date(now + 315360000000L), name, keys.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private)))
            val encoder = java.util.Base64.getEncoder()
            check(prefs.edit().putString("key", encoder.encodeToString(keys.private.encoded))
                .putString("cert", encoder.encodeToString(cert.encoded)).commit())
            keys.private to cert
        }
    }
    override fun getPrivateKey(): PrivateKey = entry.first
    override fun getCertificate(): Certificate = entry.second
    override fun getDeviceName(): String = "fR3kHUD"

    fun startHud(context: Context, host: String, port: Int): String {
        require(port in 1..65535) { "Connection port must be 1–65535" }
        require(host.isNotBlank() && host.length <= 253) { "Enter the device IP address" }
        disconnect()
        check(connect(host, port)) { "ADB connection was not authorised. Pair this HUD installation first." }
        // APK path comes from PackageManager, never from user input.
        val source = context.applicationInfo.sourceDir.replace("'", "'\"'\"'")
        val command = "trap '' HUP; CLASSPATH='$source' /system/bin/setsid /system/bin/app_process /system/bin com.mcpintelligence.fr3k.privileged.HudPrivilegedMain </dev/null >/data/local/tmp/fr3k-hud-bootstrap-shell.log 2>&1 & echo HUD_BOOTSTRAP_SENT"
        openStream("shell:$command").use { stream ->
            check(stream.openInputStream().bufferedReader().readLine() == "HUD_BOOTSTRAP_SENT") { "ADB shell did not acknowledge bootstrap" }
            // Keep the bootstrap shell alive until app_process has published. Closing on the shell's
            // echo races nohup in the background child and can kill it before SIGHUP is ignored.
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                HudPrivilegedBridge.serviceOrNull()?.let { service ->
                    return "CONNECTED · ${service.capabilityTier} · UID ${service.serverUid}. ADB connection closed; HUD service verified."
                }
                Thread.sleep(100)
            }
        }
        error("ADB connected, but HUD service did not publish a Binder. Reconnect after opening HUD; inspect diagnostics.")
    }
    fun forget() { disconnect(); prefs.edit().clear().commit() }
}
