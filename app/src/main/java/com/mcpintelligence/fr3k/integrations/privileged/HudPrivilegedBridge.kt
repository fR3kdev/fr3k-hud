package com.mcpintelligence.fr3k.integrations.privileged

import android.os.IBinder
import com.mcpintelligence.fr3k.privileged.IHudPrivilegedService

/** Published only by our DUMP-protected provider after authenticating the shell/root caller. */
object HudPrivilegedBridge {
    @Volatile private var current: IHudPrivilegedService? = null
    @Synchronized internal fun attach(service: IHudPrivilegedService) {
        require(service.protocolVersion == 1) { "Unsupported privileged service protocol" }
        require(service.serverUid in setOf(0, 2000)) { "Not a privileged service" }
        require(service.generation.isNotBlank())
        val binder = service.asBinder()
        binder.linkToDeath({ synchronized(this) {
            if (current?.asBinder() === binder) current = null
        } }, 0)
        current = service
    }
    fun serviceOrNull(): IHudPrivilegedService? = current?.takeIf { it.asBinder().isBinderAlive }
}
