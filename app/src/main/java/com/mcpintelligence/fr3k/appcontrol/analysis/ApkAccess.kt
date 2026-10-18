package com.mcpintelligence.fr3k.appcontrol.analysis

import com.reandroid.archive.ArchiveFile
import com.reandroid.archive.InputSource
import com.reandroid.archive.io.ZipFileInput
import com.reandroid.apk.ApkModule
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.DexFile
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Seek through a granted descriptor. Never reopen an application-private path in the sandbox. */
class ApkAccess private constructor(private val channel: FileChannel) : Closeable {
    private val archive: ArchiveFile
    val sources: List<InputSource>
    val module: ApkModule
    init {
        val size = channel.size(); require(size in 1..ArchiveBudget.INPUT)
        // Bound the central directory before the binary ZIP library allocates anything from it.
        val tailSize = minOf(size, 65557).toInt(); val tail = ByteArray(tailSize)
        readFully(channel, size - tailSize, tail)
        val end = (tail.size - 22 downTo 0).firstOrNull { p -> tail[p] == 0x50.toByte() && tail[p + 1] == 0x4b.toByte() && tail[p + 2] == 5.toByte() && tail[p + 3] == 6.toByte() } ?: error("ZIP end record missing")
        val header = ByteBuffer.wrap(tail, end, tail.size - end).slice().order(ByteOrder.LITTLE_ENDIAN)
        require(header.getShort(4).toInt() == 0 && header.getShort(6).toInt() == 0)
        val count = header.getShort(10).toInt().and(65535)
        require(count in 1..10_000 && header.getShort(8) == header.getShort(10))
        val directoryBytes = header.getInt(12).toLong().and(0xffffffffL)
        val directoryOffset = header.getInt(16).toLong().and(0xffffffffL)
        require(directoryBytes <= 8 * 1024 * 1024 && directoryOffset + directoryBytes <= size)
        require(end + 22 + header.getShort(20).toInt().and(65535) == tail.size) { "Trailing ZIP data or malformed comment" }
        if (directoryOffset >= 24) {
            val footer = ByteArray(24); readFully(channel, directoryOffset - 24, footer)
            if (footer.copyOfRange(8, 24).toString(Charsets.US_ASCII) == "APK Sig Block 42") {
                val blockSize = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN).long
                require(blockSize in 24..(16L * 1024 * 1024) && blockSize + 8 <= directoryOffset) { "APK signing block exceeds supported budget" }
                val leading = ByteArray(8); readFully(channel, directoryOffset - blockSize - 8, leading)
                require(ByteBuffer.wrap(leading).order(ByteOrder.LITTLE_ENDIAN).long == blockSize) { "APK signing block size mismatch" }
            }
        }
        val input = object : ZipFileInput(File("descriptor.apk")) {
            override fun getLength() = channel.size()
            override fun getFileChannel() = channel
            override fun isOpen() = channel.isOpen
            override fun position() = channel.position()
            override fun position(value: Long) { channel.position(value) }
            override fun getFooter(length: Int): ByteArray = ByteArray(minOf(length.toLong(), channel.size()).toInt()).also { readFully(channel, channel.size() - it.size, it) }
            override fun getInputStream(offset: Long, length: Long): InputStream = object : InputStream() {
                private var position = offset; private var remaining = length
                private var markedPosition = position; private var markedRemaining = remaining
                override fun markSupported() = true
                override fun mark(readLimit: Int) { markedPosition = position; markedRemaining = remaining }
                override fun reset() { position = markedPosition; remaining = markedRemaining }
                override fun skip(count: Long): Long { val skipped = minOf(count.coerceAtLeast(0), remaining); position += skipped; remaining -= skipped; return skipped }
                override fun available() = minOf(Int.MAX_VALUE.toLong(), remaining.coerceAtLeast(0)).toInt()
                override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt().and(255) }
                override fun read(bytes: ByteArray, start: Int, count: Int): Int {
                    if (remaining <= 0) return -1
                    val n = channel.read(ByteBuffer.wrap(bytes, start, minOf(count.toLong(), remaining).toInt()), position)
                    if (n > 0) { remaining -= n; position += n }; return n
                }
            }
            override fun close() {} // Owned by ApkAccess, not by each entry stream.
        }
        archive = ArchiveFile(input)
        sources = archive.inputSources.toList()
        require(sources.size == count && sources.map { it.alias }.distinct().size == count) { "Duplicate or inconsistent central directory" }
        verify()
        module = ApkModule(archive.createZipEntryMap())
    }
    fun verify() {
        var expanded = 0L
        for (source in sources) {
            val name = (source as? com.reandroid.archive.io.ArchiveEntrySource<*>)?.archiveEntry?.name ?: source.alias
            require(!name.startsWith('/') && '\\' !in name && '\u0000' !in name && name.split('/').none { it == "." || it == ".." })
            val size = uncompressedSize(source); require(size in 0..ArchiveBudget.ENTRY); expanded += size
            require(expanded <= 2L * 1024 * 1024 * 1024)
            var actual = 0L; val crc = java.util.zip.CRC32()
            source.openStream().use { input -> val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; actual += n
                    require(actual <= size && actual <= ArchiveBudget.ENTRY) { "Entry $name declared $size read $actual" }; crc.update(buffer, 0, n)
                    check(!Thread.currentThread().isInterrupted) { "Cancelled" }
                }
            }
            require(actual == size && crc.value == source.crc) { "ZIP length or CRC mismatch" }
        }
        require(sources.any { it.alias == "AndroidManifest.xml" })
    }
    fun dexes(): Sequence<Pair<String, DexFile>> = sources.asSequence().filter { it.alias.matches(Regex("classes[0-9]*\\.dex")) }.map { source ->
        source.alias to source.openStream().buffered().use { DexBackedDexFile.fromInputStream(null, it) }
    }
    override fun close() { module.close(); archive.close(); channel.close() }
    companion object {
        fun open(file: File): ApkAccess { require(file.isFile && file.canRead()) { "APK is not readable" }; return fromChannel(FileInputStream(file).channel) }
        fun uncompressedSize(source: InputSource): Long = (source as? com.reandroid.archive.io.ArchiveEntrySource<*>)?.archiveEntry?.size ?: source.length
        fun fromChannel(channel: FileChannel): ApkAccess = try { ApkAccess(channel) } catch (failure: Throwable) { channel.close(); throw failure }
        private fun readFully(channel: FileChannel, offset: Long, bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes); var position = offset
            while (buffer.hasRemaining()) { val read = channel.read(buffer, position); require(read > 0) { "Truncated archive" }; position += read }
        }
    }
}
