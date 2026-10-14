package com.mcpintelligence.fr3k.bridge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.mcpintelligence.fr3k.capability.MatchOutcome
import com.mcpintelligence.fr3k.capability.SelectorFingerprint
import com.mcpintelligence.fr3k.capability.SelectorMatcher
import com.mcpintelligence.fr3k.capability.Step
import kotlinx.coroutines.delay

data class StepRecord(
    val index: Int,
    val op: String,
    val status: String,
    val detail: String,
    val confidence: Double = 0.0,
)

class StepFailedException(val record: StepRecord) : IllegalStateException("${record.op} failed: ${record.detail}")

/**
 * Executes the closed step grammar against the accessibility service. There is no model in this
 * loop: a step either matches its fingerprint or the run aborts with the tree state recorded.
 */
class BridgeStepExecutor(
    private val service: Fr3kBridgeService,
    private val approvedPackages: Set<String>,
    private val privileged: suspend (String, String, String) -> String,
    private val approveDestructive: Boolean = false,
    private val log: (String) -> Unit = {},
) {
    val records = ArrayList<StepRecord>()

    suspend fun execute(steps: List<Step>): List<StepRecord> {
        steps.forEachIndexed { index, step -> records.add(runStep(index, step)) }
        return records
    }

    private suspend fun runStep(index: Int, step: Step): StepRecord = when (step) {
        is Step.Launch -> run {
            val intent = launchIntent(step.target) ?: throw StepFailedException(
                StepRecord(index, "launch", "FAILED", "no activity resolves '${step.target}'"),
            )
            service.launch(intent)
            settle()
            record(index, "launch", "OK", "started ${step.target}")
        }
        is Step.WaitFor -> {
            val outcome = awaitMatch(step.selector, step.timeoutMs.toLong())
            when (outcome) {
                is MatchOutcome.Found -> record(index, "wait_for", "OK", "matched ${outcome.node.viewId ?: outcome.node.className}", outcome.confidence)
                else -> throw StepFailedException(record(index, "wait_for", "FAILED", "never appeared: $outcome"))
            }
        }
        is Step.Click -> {
            val outcome = SelectorMatcher.find(requireTree(), step.selector)
            val found = outcome as? MatchOutcome.Found
                ?: throw StepFailedException(record(index, "click", "FAILED", "no unambiguous match: $outcome"))
            val node = liveTarget(found.path)
                ?: throw StepFailedException(record(index, "click", "FAILED", "match has no clickable ancestor"))
            val label = node.text?.toString() ?: node.contentDescription?.toString()
            if (!approveDestructive && BridgeGuard.isDestructive(label)) {
                throw StepFailedException(record(index, "click", "REFUSED", "destructive control not approved: '$label'"))
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            settle()
            if (!ok) throw StepFailedException(record(index, "click", "FAILED", "platform refused the click"))
            record(index, "click", "OK", "clicked '${label ?: node.viewIdResourceName}'", found.confidence)
        }
        is Step.SetText -> {
            val outcome = SelectorMatcher.find(requireTree(), step.selector) as? MatchOutcome.Found
                ?: throw StepFailedException(record(index, "set_text", "FAILED", "no match"))
            val node = liveNodeAt(outcome.path)
                ?: throw StepFailedException(record(index, "set_text", "FAILED", "node disappeared"))
            if (node.isPassword) throw StepFailedException(record(index, "set_text", "REFUSED", "credential fields are out of scope"))
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, step.text)
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            settle()
            if (!ok) throw StepFailedException(record(index, "set_text", "FAILED", "platform refused the edit"))
            record(index, "set_text", "OK", "text set")
        }
        is Step.AssertPresent -> {
            val outcome = SelectorMatcher.find(requireTree(), step.selector)
            if (outcome is MatchOutcome.Found) record(index, "assert_present", "OK", "present", outcome.confidence)
            else throw StepFailedException(record(index, "assert_present", "FAILED", "expected a match but saw $outcome"))
        }
        is Step.AssertAbsent -> {
            val outcome = SelectorMatcher.find(requireTree(), step.selector)
            if (outcome is MatchOutcome.NotFound) record(index, "assert_absent", "OK", "absent")
            else throw StepFailedException(record(index, "assert_absent", "FAILED", "expected absence but saw $outcome"))
        }
        is Step.Mask -> {
            val outcome = SelectorMatcher.find(requireTree(), step.selector) as? MatchOutcome.Found
                ?: throw StepFailedException(record(index, "mask", "FAILED", "no match to mask"))
            val node = liveNodeAt(outcome.path)
                ?: throw StepFailedException(record(index, "mask", "FAILED", "node disappeared"))
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            service.showMask(bounds)
            settle()
            record(index, "mask", "OK", "masked ${bounds.left},${bounds.top} ${bounds.width()}x${bounds.height()}")
        }
        is Step.Unmask -> {
            service.clearMasks()
            settle()
            record(index, "unmask", "OK", "masks cleared")
        }
        is Step.Observe -> {
            delay(step.durationMs.toLong())
            record(index, "observe", "OK", "waited ${step.durationMs}ms")
        }
        is Step.Broadcast -> {
            val intent = Intent(step.action).setPackage("com.mcpintelligence.fr3k.hud")
            step.extras.forEach { (key, value) -> intent.putExtra(key, value) }
            service.sendBroadcast(intent)
            settle()
            record(index, "broadcast", "OK", "sent ${step.action}")
        }
        is Step.PrivilegedOp -> {
            val result = privileged(step.packageName, step.op, step.mode)
            record(index, "privileged", "OK", result)
        }
    }

    private fun launchIntent(target: String): Intent? {
        val context: Context = service
        return if (target.contains("://")) {
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } else {
            context.packageManager.getLaunchIntentForPackage(target)
        }
    }

    private fun liveNodeAt(path: List<Int>): AccessibilityNodeInfo? =
        service.liveRoot()?.let { UiTreeReader.nodeAtPath(it, path) }

    private fun liveTarget(path: List<Int>): AccessibilityNodeInfo? =
        service.liveRoot()?.let { UiTreeReader.clickableTarget(it, path) }

    private fun requireTree(): com.mcpintelligence.fr3k.capability.UiNode =
        service.readTree() ?: throw StepFailedException(StepRecord(-1, "read", "FAILED", "no active window"))

    private suspend fun awaitMatch(selector: SelectorFingerprint, timeoutMs: Long): MatchOutcome {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last: MatchOutcome = MatchOutcome.NotFound
        while (SystemClock.elapsedRealtime() < deadline) {
            last = SelectorMatcher.find(requireTree(), selector)
            if (last is MatchOutcome.Found) return last
            delay(100)
        }
        return last
    }

    private suspend fun settle() = delay(250)

    private fun record(index: Int, op: String, status: String, detail: String, confidence: Double = 0.0): StepRecord =
        StepRecord(index, op, status, detail, confidence).also { log("step[$index] $op $status $detail") }

    /** Foreground package check, used before any plan begins. */
    fun foregroundPackage(): String? = service.foregroundPackage()

    fun packageAllowed(): Boolean = BridgeGuard.mayActOn(service.foregroundPackage(), approvedPackages)
}
