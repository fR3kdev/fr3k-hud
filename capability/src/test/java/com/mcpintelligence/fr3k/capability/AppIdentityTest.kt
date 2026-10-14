package com.mcpintelligence.fr3k.capability

import org.junit.Assert.*
import org.junit.Test

class AppIdentityTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test fun rejectsMalformedInput() {
        assertThrows(IllegalArgumentException::class.java) { AppIdentity("bad name", 1, a, listOf(a)) }
        assertThrows(IllegalArgumentException::class.java) { AppIdentity("com.x.y", -1, a, listOf(a)) }
        assertThrows(IllegalArgumentException::class.java) { AppIdentity("com.x.y", 1, "nope", listOf(a)) }
        assertThrows(IllegalArgumentException::class.java) { AppIdentity("com.x.y", 1, a, emptyList()) }
    }

    @Test fun splitsAreCopiedAndImmutable() {
        val splits = mutableListOf(a)
        val id = AppIdentity("com.x.y", 7, a, splits)
        splits.clear()
        assertEquals(listOf(a), id.splitSha256)
        assertThrows(UnsupportedOperationException::class.java) { (id.splitSha256 as MutableList).clear() }
    }

    @Test fun fingerprintIgnoresSplitOrderButNotContent() {
        val one = AppIdentity("com.x.y", 7, a, listOf(a, b))
        val two = AppIdentity("com.x.y", 7, a, listOf(b, a))
        assertEquals(one.fingerprint, two.fingerprint)
        assertNotEquals(one.fingerprint, one.copy(versionCode = 8).fingerprint)
        assertNotEquals(one.fingerprint, one.copy(signerSha256 = b).fingerprint)
        assertNotEquals(one.fingerprint, one.copy(packageName = "com.x.z").fingerprint)
        assertTrue(one.label.startsWith("com.x.y@7/"))
    }
}
