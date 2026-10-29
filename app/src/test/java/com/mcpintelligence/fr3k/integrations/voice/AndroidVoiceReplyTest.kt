package com.mcpintelligence.fr3k.integrations.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidVoiceReplyTest {
    @Test fun replyEmittedDuringSendIsNotLost() = runTest {
        val replies = MutableSharedFlow<String>()
        assertEquals("answer", awaitVoiceReply(replies, 100) { replies.emit("answer") })
        assertEquals(0, replies.subscriptionCount.value)
    }

    @Test fun timeoutCancelsHungSendAndUnsubscribes() = runTest {
        val replies = MutableSharedFlow<String>()
        var cancelled = false
        val reply = awaitVoiceReply(replies, 100) {
            try { awaitCancellation() } finally { cancelled = true }
        }
        assertNull(reply)
        assertTrue(cancelled)
        assertEquals(0, replies.subscriptionCount.value)
        assertEquals(100L, testScheduler.currentTime)
    }

    @Test fun timeoutAlsoBoundsWaitingForAnAbsentReply() = runTest {
        val replies = MutableSharedFlow<String>()
        assertNull(awaitVoiceReply(replies, 100) { })
        assertEquals(0, replies.subscriptionCount.value)
    }

    @Test fun cancellationPropagatesAndCancelsSend() = runTest {
        val replies = MutableSharedFlow<String>()
        var completed = false
        var cancelled = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            awaitVoiceReply(replies, 100) {
                try { awaitCancellation() } finally { cancelled = true }
            }
            completed = true
        }
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertFalse(completed)
        assertEquals(0, replies.subscriptionCount.value)
    }

    @Test fun providerExceptionDoesNotLeakSubscriber() = runTest {
        val replies = MutableSharedFlow<String>()
        try {
            awaitVoiceReply(replies, 100) { throw IllegalStateException("offline") }
            fail("Expected provider failure")
        } catch (e: IllegalStateException) { assertEquals("offline", e.message) }
        assertEquals(0, replies.subscriptionCount.value)
    }
}
