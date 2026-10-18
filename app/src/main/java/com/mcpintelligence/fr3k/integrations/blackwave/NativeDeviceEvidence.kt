package com.mcpintelligence.fr3k.integrations.blackwave

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Read-only native evidence verifier. No signing, controller grant, approval or device I/O. */
object NativeDeviceEvidence {
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun identity(public: ByteArray) = "bw:ed25519:sha256:" + digest(public)
    private fun integer(p: JSONObject, key: String): Long {
        val value = p.get(key); require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it in 0..9007199254740991L) }
    }
    fun parse(bytes: ByteArray): JSONObject {
        require(bytes.size in 1..256 * 1024)
        val raw = bytes.toString(Charsets.UTF_8)
        var depth = 0; var tokens = 0; var quoted = false; var escaped = false
        for (c in raw) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 12 && ++tokens <= 4096) }
                '}', ']' -> { depth--; require(depth >= 0) }
                ',', ':' -> require(++tokens <= 4096)
            }
        }
        require(depth == 0 && !quoted)
        val value = JSONObject(raw)
        require(LocalReceiptBytes.canonical(value).contentEquals(bytes)) { "Noncanonical evidence artifact" }
        return value
    }
    private fun verified(envelope: JSONObject, public: ByteArray): JSONObject {
        require(public.size == 32 && envelope.keys().asSequence().toSet() == setOf("payload", "signature"))
        val p = envelope.getJSONObject("payload")
        val bytes = LocalReceiptBytes.canonical(p)
        require(bytes.size <= 4096 && bytes.all { it.toInt() in 32..126 })
        val signature = Base64.getDecoder().decode(envelope.getString("signature")); require(signature.size == 64)
        val verifier = Ed25519Signer().apply { init(false, Ed25519PublicKeyParameters(public, 0)); update(bytes, 0, bytes.size) }
        check(verifier.verifySignature(signature)) { "Native evidence signature invalid" }
        return p
    }
    /** Caller first verifies the phone receipt signature and this artifact's exact hash/length. */
    fun verify(artifact: JSONObject, job: JSONObject, controllerPublic: ByteArray): String {
        require(artifact.getString("protocol") == "blackwave-native-evidence/1")
        for (key in listOf("operation_id", "request_id", "target_id", "operation_digest", "action"))
            check(artifact.getString(key) == job.getString(key)) { "Native artifact belongs to another operation" }
        check(LocalReceiptBytes.canonical(artifact.getJSONObject("parameters")).contentEquals(LocalReceiptBytes.canonical(job.getJSONObject("parameters"))))
        val public = Base64.getDecoder().decode(artifact.getString("device_public_key")); require(public.size == 32)
        check(identity(public) == job.getString("target_id"))
        val request = verified(artifact.getJSONObject("device_request"), controllerPublic)
        val receipt = verified(artifact.getJSONObject("device_receipt"), public)
        for (p in listOf(request, receipt)) {
            check(p.getString("target_identity") == job.getString("target_id") && p.getString("device_id") == artifact.getString("device_id"))
        }
        for (key in listOf("operation_id", "request_id")) check(request.getString(key) == job.getString(key))
        check(request.getString("operation_digest") == job.getString("operation_digest").removePrefix("sha256:"))
        val action = job.getString("action")
        if (action == "device.associate_controller") {
            check(request.getString("protocol") == "blackwave-device-controller/1" && request.getString("action") == "associate")
            check(request.getString("controller_identity") == identity(controllerPublic))
            check(Base64.getDecoder().decode(request.getString("controller_public_key")).contentEquals(controllerPublic))
            for (key in listOf("controller_id", "controller_identity")) check(request.getString(key) == job.getJSONObject("parameters").getString(key))
            if (artifact.getString("device_evidence_kind") == "OWNERSHIP_NOT_APPLIED_OBSERVATION") {
                check(job.getString("state") == "FAILED" && receipt.getString("protocol") == "blackwave-device-controller/1")
                check(receipt.getString("owner_identity").isEmpty() && integer(receipt, "generation") == integer(request, "generation"))
                check(receipt.getString("session_nonce") != request.getString("session_nonce"))
                return "Verified signed unowned observation; the controller link was not applied."
            }
            check(receipt.getString("protocol") == "blackwave-device-controller/1" && receipt.getString("state") == "OWNED" && job.getString("state") == "SUCCEEDED")
            for (key in listOf("operation_id", "request_id", "operation_digest", "controller_id", "controller_identity")) check(receipt.getString(key) == request.getString(key))
            check(integer(receipt, "generation") == integer(request, "generation") + 1 && receipt.getString("request_digest") == digest(LocalReceiptBytes.canonical(request)))
            return "Verified device-signed controller grant for the original operation."
        }
        require(action in setOf("device.status", "device.set_volume"))
        check(request.getString("protocol") == "blackwave-device-command/1" && request.getString("action") == action)
        val expectedParameters = if (action == "device.status") JSONObject() else JSONObject().put("volume", job.getJSONObject("parameters").get("volume"))
        check(LocalReceiptBytes.canonical(request.getJSONObject("parameters")).contentEquals(LocalReceiptBytes.canonical(expectedParameters)))
        check(receipt.getString("protocol") == "blackwave-device-command-receipt/1")
        for (key in listOf("operation_id", "request_id", "operation_digest", "controller_id", "action")) check(receipt.getString(key) == request.getString(key))
        check(receipt.getString("command_digest") == digest(LocalReceiptBytes.canonical(request)))
        for (key in listOf("generation", "sequence")) check(integer(receipt, key) == integer(request, key))
        val state = receipt.getString("state"); require(state in setOf("SUCCEEDED", "FAILED", "INTERRUPTED")); check(state == job.getString("state"))
        if (state == "SUCCEEDED") {
            val volume = integer(receipt.getJSONObject("result"), "volume"); require(volume <= 100)
            if (action == "device.set_volume") check(volume == integer(expectedParameters, "volume"))
        }
        return "Verified device-signed software result: $state."
    }
}
