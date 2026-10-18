package com.mcpintelligence.fr3k.integrations.blackwave

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class NativeDeviceEvidenceTest {
    private val vectors = JSONObject(javaClass.classLoader!!.getResource("phone-controller-v1.json")!!.readText())
    private val device = vectors.getJSONObject("hello").getJSONObject("payload")
    private val public = Base64.getDecoder().decode(vectors.getJSONObject("association").getJSONObject("payload").getString("controller_public_key"))
    private fun fixture(requestName: String, receiptName: String, action: String, state: String, kind: String = "SIGNED_DEVICE_RESULT"): Pair<JSONObject, JSONObject> {
        val request = vectors.getJSONObject(requestName); val p = request.getJSONObject("payload")
        val parameters = if (action == "device.associate_controller") JSONObject().put("controller_id", p.getString("controller_id")).put("controller_identity", p.getString("controller_identity"))
            else JSONObject().put("volume", p.getJSONObject("parameters").get("volume"))
        val job = JSONObject().put("operation_id", p.getString("operation_id")).put("request_id", p.getString("request_id"))
            .put("target_id", p.getString("target_identity")).put("operation_digest", "sha256:" + p.getString("operation_digest"))
            .put("action", action).put("parameters", parameters).put("state", state)
        val artifact = JSONObject(job.toString()).put("protocol", "blackwave-native-evidence/1").put("device_id", device.getString("device_id"))
            .put("model_id", device.getString("model_id")).put("device_public_key", device.getString("public_key"))
            .put("device_request", request).put("device_receipt", vectors.getJSONObject(receiptName)).put("device_evidence_kind", kind)
        return artifact to job
    }
    @Test fun realCppSignedCommandAndControllerGrantVerifyWithoutPrivateKeys() {
        for ((request, result, action) in listOf(Triple("command", "result", "device.set_volume"), Triple("association", "ownership", "device.associate_controller"))) {
            val (artifact, job) = fixture(request, result, action, "SUCCEEDED")
            val parsed = NativeDeviceEvidence.parse(LocalReceiptBytes.canonical(artifact))
            assertTrue(NativeDeviceEvidence.verify(parsed, job, public).startsWith("Verified"))
        }
    }
    @Test fun realCppCancelledLinkIsAnObservationRatherThanACommandReceipt() {
        val (artifact, job) = fixture("cancelled_association", "unowned_after_cancel_hello", "device.associate_controller", "FAILED", "OWNERSHIP_NOT_APPLIED_OBSERVATION")
        assertTrue(NativeDeviceEvidence.verify(artifact, job, public).contains("not applied"))
        job.put("state", "SUCCEEDED")
        assertThrows(Exception::class.java) { NativeDeviceEvidence.verify(artifact, job, public) }
    }
    @Test fun changedRequestsResultsTargetsAndUnboundedArtifactsAreRefused() {
        val (artifact, job) = fixture("command", "result", "device.set_volume", "SUCCEEDED")
        val different = JSONObject(job.toString()).put("operation_id", "d37a8a63-7350-45eb-851c-678148200002")
        assertThrows(Exception::class.java) { NativeDeviceEvidence.verify(artifact, different, public) }
        val changed = JSONObject(artifact.toString())
        changed.getJSONObject("device_request").getJSONObject("payload").getJSONObject("parameters").put("volume", 100)
        assertThrows(Exception::class.java) { NativeDeviceEvidence.verify(changed, job, public) }
        val wrong = JSONObject(artifact.toString()).put("device_public_key", Base64.getEncoder().encodeToString(ByteArray(32)))
        assertThrows(Exception::class.java) { NativeDeviceEvidence.verify(wrong, job, public) }
        for (bytes in listOf("[".repeat(1000).toByteArray(), ByteArray(256 * 1024 + 1), "{}{}".toByteArray()))
            assertThrows(Exception::class.java) { NativeDeviceEvidence.parse(bytes) }
    }
}
