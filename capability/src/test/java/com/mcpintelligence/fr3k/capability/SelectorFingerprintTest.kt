package com.mcpintelligence.fr3k.capability

import org.junit.Assert.*
import org.junit.Test

class SelectorFingerprintTest {
    private val tree = UiNode(
        className = "android.widget.LinearLayout",
        children = listOf(
            UiNode(className = "android.widget.TextView", text = "Simulated pre-roll", contentDescription = "ad-demo-title"),
            UiNode(
                viewId = "com.mcpintelligence.fr3k.patchfixture:id/ad-demo-skip",
                className = "android.widget.Button",
                text = "Skip Ad",
                contentDescription = "ad-demo-skip",
                clickable = true,
            ),
            UiNode(
                viewId = "com.mcpintelligence.fr3k.patchfixture:id/ad-demo-decoy",
                className = "android.widget.Button",
                text = "Decoy",
                contentDescription = "ad-demo-decoy",
                clickable = true,
            ),
            UiNode(className = "android.widget.EditText", password = true, text = "hunter2", contentDescription = "password field"),
        ),
    )

    private fun found(fp: SelectorFingerprint): MatchOutcome.Found {
        val outcome = SelectorMatcher.find(tree, fp)
        assertTrue("expected Found but was $outcome", outcome is MatchOutcome.Found)
        return outcome as MatchOutcome.Found
    }

    @Test fun exactViewIdScoresCertainty() {
        val hit = found(SelectorFingerprint(viewId = "com.mcpintelligence.fr3k.patchfixture:id/ad-demo-skip"))
        assertEquals(1.0, hit.confidence, 0.0001)
        assertTrue(hit.node.text == "Skip Ad")
    }

    @Test fun shortViewIdSuffixIsAcceptedAtLowerConfidence() {
        val hit = found(SelectorFingerprint(viewId = "ad-demo-skip"))
        assertEquals(0.9, hit.confidence, 0.0001)
    }

    @Test fun everyDeclaredCriterionMustHold() {
        val both = found(SelectorFingerprint(className = "android.widget.Button", textRegex = "(?i)skip(\\s+ad(s)?)?"))
        assertEquals(1.0, both.confidence, 0.0001)
        // The right class with the wrong text satisfies half of a two-criterion selector, so it is refused.
        val half = SelectorMatcher.find(tree, SelectorFingerprint(className = "android.widget.Button", textRegex = "(?i)nosuchlabel"))
        assertTrue("a half-satisfied selector must not match: $half", half is MatchOutcome.NotFound)
    }

    @Test fun aSingleDeclaredCriterionIsSatisfiableOnItsOwn() {
        val hit = found(SelectorFingerprint(contentDescRegex = "ad-demo-skip"))
        assertEquals(1.0, hit.confidence, 0.0001)
        assertEquals("ad-demo-skip", hit.node.contentDescription)
        // Two nodes satisfying the same single criterion is ambiguous, and that is the real guard.
        val ambiguous = SelectorMatcher.find(tree, SelectorFingerprint(contentDescRegex = "(?i)ad-demo-(skip|decoy)"))
        assertTrue(ambiguous is MatchOutcome.Ambiguous)
    }

    @Test fun ambiguityRefusesInsteadOfClickingFirst() {
        val outcome = SelectorMatcher.find(tree, SelectorFingerprint(className = "android.widget.Button", contentDescRegex = "(?i)(skip|decoy)"))
        assertTrue("two candidates must be ambiguous, was $outcome", outcome is MatchOutcome.Ambiguous)
    }

    @Test fun passwordTextIsNeverUsedAsEvidence() {
        val outcome = SelectorMatcher.find(tree, SelectorFingerprint(className = "android.widget.EditText", textRegex = "hunter2"))
        assertTrue("password text must never match: $outcome", outcome is MatchOutcome.NotFound)
    }
}
