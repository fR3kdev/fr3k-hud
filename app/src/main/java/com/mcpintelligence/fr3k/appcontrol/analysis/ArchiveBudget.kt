package com.mcpintelligence.fr3k.appcontrol.analysis

import java.io.File
import java.util.zip.ZipFile

/** Validate before handing attacker-controlled lengths to a binary parser. */
object ArchiveBudget {
    const val INPUT = 512L * 1024 * 1024
    const val ENTRY = 64L * 1024 * 1024
    fun verify(file: File) {
        require(file.length() in 1..INPUT) { "APK outside 512 MiB budget" }
        ZipFile(file).use { zip ->
            val names = HashSet<String>(); var expanded = 0L; var count = 0
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                check(!Thread.currentThread().isInterrupted) { "Cancelled" }
                val e = entries.nextElement(); val n = e.name
                require(++count <= 10_000 && names.add(n)) { "Too many or duplicate ZIP entries" }
                require(!n.startsWith('/') && '\\' !in n && n.split('/').none { it == ".." || it == "." } && '\u0000' !in n) { "Unsafe ZIP entry" }
                require(e.size in 0..ENTRY) { "ZIP entry exceeds 64 MiB budget: $n" }
                expanded += e.size
                require(expanded <= 2L * 1024 * 1024 * 1024) { "Expanded APK exceeds 2 GiB" }
                // Stream-count actual bytes; ZIP metadata alone is not a trust boundary.
                var actual = 0L
                zip.getInputStream(e).use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { val read = input.read(buffer); if (read < 0) break; actual += read
                        require(actual <= ENTRY && actual <= e.size) { "ZIP entry length mismatch" }
                        check(!Thread.currentThread().isInterrupted) { "Cancelled" }
                    }
                }
                require(actual == e.size) { "Truncated ZIP entry" }
            }
            require("AndroidManifest.xml" in names) { "APK manifest missing" }
        }
    }
}
