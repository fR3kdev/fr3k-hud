package com.mcpintelligence.fr3k.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TextActionTruthTest {
    private fun context(command: Fr3kCommand, consent: ConsentLevel = ConsentLevel.NORMAL) =
        Fr3kContext("test", consentLevel = consent, enabledCapabilities = command.requiredCapabilities)

    @Test fun allFourTransformationsReturnBackendTextRatherThanQueuedSuccess() = runBlocking {
        val requests = ArrayList<Pair<String, String>>()
        val backend: suspend (String, String) -> String = { task, text -> requests.add(task to text); "actual result for $text" }
        val commands = listOf(RewriteTextCommand(backend), TranslateTextCommand(backend), SummariseTextCommand(backend), ExplainTextCommand(backend))
        commands.forEach { command -> assertEquals("actual result for sample", (command.execute(context(command), mapOf("text" to "sample")) as CommandResult.Ok).message) }
        assertEquals(4, requests.size)
        assertEquals(4, requests.map { it.first }.distinct().size)
    }
    @Test fun localOnlyConsentDoesNotCallRemoteBackend() = runBlocking {
        var called = false
        val command = ExplainTextCommand { _, _ -> called = true; "result" }
        assertTrue(command.execute(context(command, ConsentLevel.LOCAL_ONLY), mapOf("text" to "private")) is CommandResult.Failed)
        assertFalse(called)
    }
    @Test fun missingInputAndCapabilityDoNotCallBackend() = runBlocking {
        var called = false
        val command = TranslateTextCommand { _, _ -> called = true; "result" }
        assertTrue(command.execute(context(command), emptyMap()) is CommandResult.Failed)
        assertTrue(command.execute(Fr3kContext("test"), mapOf("text" to "sample")) is CommandResult.Failed)
        assertFalse(called)
    }
    @Test fun missingBackendAndBlankResponseAreFailures() = runBlocking {
        val noBackend = SummariseTextCommand()
        assertTrue(noBackend.execute(context(noBackend), mapOf("text" to "sample")) is CommandResult.Failed)
        val empty = SummariseTextCommand { _, _ -> "" }
        assertTrue(empty.execute(context(empty), mapOf("text" to "sample")) is CommandResult.Failed)
    }
    @Test fun providerFailureDoesNotClaimSuccess() = runBlocking {
        val command = RewriteTextCommand { _, _ -> throw IllegalStateException("offline") }
        assertTrue(command.execute(context(command), mapOf("text" to "sample")) is CommandResult.Failed)
    }
    @Test(expected = CancellationException::class) fun cancellationPropagates() = runBlocking<Unit> {
        val command = RewriteTextCommand { _, _ -> throw CancellationException("cancelled") }
        command.execute(context(command), mapOf("text" to "sample"))
        Unit
    }
    @Test fun unavailableMeshAndGpsNeverReportQueuedSuccess() = runBlocking {
        assertTrue(SendToMeshCommand().execute(Fr3kContext("test"), emptyMap()) is CommandResult.Failed)
        val commands = listOf(CopyLocationCommand(), WaypointCommand(), ShareLocationCommand())
        commands.forEach { assertTrue(it.execute(context(it), emptyMap()) is CommandResult.Failed) }
    }
}
