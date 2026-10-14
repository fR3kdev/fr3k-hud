package com.mcpintelligence.fr3k.capability

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class SignerVerdict(val accepted: Boolean, val reason: String) {
    val rejected: Boolean get() = !accepted
}

/**
 * A bundle is trusted when its bytes verify against a pinned publisher key. Unsigned bundles are
 * accepted only for the local publisher, so a registry shipped inside the app or authored by the
 * operator still works without key ceremony.
 */
object BundleSigner {
    const val LOCAL_PUBLISHER = "fr3k-local"

    fun sign(payload: ByteArray, privateKey: PrivateKey): String {
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(payload)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    fun verify(payload: ByteArray, signatureBase64: String, publicKeyPem: String): Boolean {
        val body = publicKeyPem.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\n", "").replace("\r", "").trim()
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(body)))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(key)
        verifier.update(payload)
        return verifier.verify(Base64.getDecoder().decode(signatureBase64))
    }

    fun vet(
        publisher: String,
        payload: ByteArray,
        signatureBase64: String?,
        pinnedKeys: Map<String, String>,
        allowUnsignedLocal: Boolean = true,
    ): SignerVerdict {
        if (signatureBase64.isNullOrBlank()) {
            return if (allowUnsignedLocal && publisher == LOCAL_PUBLISHER) {
                SignerVerdict(true, "unsigned local bundle accepted")
            } else {
                SignerVerdict(false, "bundle from '$publisher' is unsigned")
            }
        }
        val pem = pinnedKeys[publisher] ?: return SignerVerdict(false, "no pinned key for publisher '$publisher'")
        return if (verify(payload, signatureBase64, pem)) {
            SignerVerdict(true, "signature verified for '$publisher'")
        } else {
            SignerVerdict(false, "signature mismatch for '$publisher'")
        }
    }
}
