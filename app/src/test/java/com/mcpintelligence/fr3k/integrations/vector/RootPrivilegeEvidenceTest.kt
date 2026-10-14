package com.mcpintelligence.fr3k.integrations.vector

import org.junit.Assert.*
import org.junit.Test

class RootPrivilegeEvidenceTest {
    @Test fun exitZeroWithoutRootUidIsNotRoot() {
        assertFalse(RootPrivilegeEvidence.isVerified(0, "uid=2000(shell) gid=2000(shell)"))
    }
    @Test fun nonzeroExitIsNotRoot() {
        assertFalse(RootPrivilegeEvidence.isVerified(1, "uid=0(root)"))
    }
    @Test fun actualRootIdentityIsVerified() {
        assertTrue(RootPrivilegeEvidence.isVerified(0, "uid=0(root) gid=0(root) groups=0(root)\n"))
    }
    @Test fun embeddedOrEmptyUidClaimIsRejected() {
        assertFalse(RootPrivilegeEvidence.isVerified(0, "error: uid=0(root)"))
        assertFalse(RootPrivilegeEvidence.isVerified(0, ""))
    }
}
