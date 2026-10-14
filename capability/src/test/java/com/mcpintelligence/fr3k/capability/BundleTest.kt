package com.mcpintelligence.fr3k.capability

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator

class BundleTest {
    private val sha = "a".repeat(64)
    private val identity = AppIdentity("com.mcpintelligence.fr3k.patchfixture", 1, sha, listOf(sha))

    private fun bundleJson(): String = BundleLoader.toJson(
        CapabilityBundle(
            bundleId = "fr3k.fixture",
            version = "0.1.0",
            publisher = BundleSigner.LOCAL_PUBLISHER,
            capabilities = listOf(
                CapabilityDescriptor(
                    id = "fixture.banner.toggle",
                    plane = Plane.BRIDGE,
                    risk = RiskClass.REVERSIBLE_WRITE,
                    summary = "Toggle the owned fixture banner",
                    identity = identity,
                    steps = listOf(Step.Broadcast("com.mcpintelligence.fr3k.hud.SET_OWNED_FIXTURE_BANNER", mapOf("visible" to "\$visible"))),
                    parameters = mapOf("visible" to ParamType.BOOL),
                )
            ),
            rawSha256 = sha,
        )
    )

    @Test fun roundTripsAndIndexesByFingerprint() {
        val bundle = BundleLoader.parse(bundleJson())
        assertEquals("fr3k.fixture", bundle.bundleId)
        assertNotNull(bundle.find("fixture.banner.toggle", identity))
        assertEquals(1, bundle.listFor(identity).size)
        assertEquals(0, bundle.listFor(identity.copy(versionCode = 2)).size)
    }

    @Test fun rejectsUnsupportedSchemaAndEmptyBundle() {
        assertThrows(IllegalArgumentException::class.java) {
            BundleLoader.parse("""{"schema":99,"bundleId":"x.y","version":"1","publisher":"p","capabilities":[]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BundleLoader.parse("""{"schema":1,"bundleId":"x.y","version":"1","publisher":"p","capabilities":[]}""")
        }
    }

    @Test fun signedBundleVerifiesAndTamperedBundleDoesNot() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PUBLIC KEY-----\n" + java.util.Base64.getEncoder().encodeToString(keys.public.encoded) + "\n-----END PUBLIC KEY-----"
        val payload = bundleJson().toByteArray()
        val signature = BundleSigner.sign(payload, keys.private)
        assertTrue(BundleSigner.verify(payload, signature, pem))
        val tampered = payload.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFalse(BundleSigner.verify(tampered, signature, pem))
        assertTrue(BundleSigner.vet("some.publisher", payload, signature, mapOf("some.publisher" to pem)).accepted)
        assertTrue(BundleSigner.vet("some.publisher", tampered, signature, mapOf("some.publisher" to pem)).rejected)
    }

    @Test fun unsignedRemoteBundleIsRejectedAndUnsignedLocalIsAccepted() {
        val payload = bundleJson().toByteArray()
        assertTrue(BundleSigner.vet("random.publisher", payload, null, emptyMap()).rejected)
        assertTrue(BundleSigner.vet(BundleSigner.LOCAL_PUBLISHER, payload, null, emptyMap()).accepted)
        assertTrue(BundleSigner.vet("unknown.publisher", payload, null, emptyMap(), allowUnsignedLocal = false).rejected)
    }

    @Test fun registryRefusesDuplicatesAndRejectedBundles() {
        val store = RegistryStore()
        val bundle = BundleLoader.parse(bundleJson())
        val verdict = SignerVerdict(true, "test")
        bundle.capabilities.forEach { store.put(RegistryEntry(it, bundle.bundleId, bundle.rawSha256, verdict)) }
        assertEquals(1, store.size)
        assertThrows(IllegalArgumentException::class.java) {
            bundle.capabilities.forEach { store.put(RegistryEntry(it, bundle.bundleId, bundle.rawSha256, verdict)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.put(RegistryEntry(bundle.capabilities[0], "other", sha, SignerVerdict(false, "unsigned")))
        }
        assertEquals(listOf("fixture.banner.toggle"), store.ids())
        assertNotNull(store.find("fixture.banner.toggle", identity))
        assertNull(store.find("fixture.banner.toggle", identity.copy(splits = listOf("c".repeat(64)))))
    }
}
