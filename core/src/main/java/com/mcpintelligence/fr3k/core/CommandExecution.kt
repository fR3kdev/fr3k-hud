package com.mcpintelligence.fr3k.core

/** UI dispatch boundary: missing requirements and provider failures become visible results. */
suspend fun executeCommand(command: Fr3kCommand, context: Fr3kContext, args: Map<String, String>): CommandResult {
    if (!context.hasAll(command.requiredCapabilities))
        return CommandResult.Failed("Missing capability: ${command.requiredCapabilities - context.enabledCapabilities}")
    return try { command.execute(context, args) }
    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (error: Exception) { CommandResult.Failed(error.message ?: "${command.title} failed") }
}
