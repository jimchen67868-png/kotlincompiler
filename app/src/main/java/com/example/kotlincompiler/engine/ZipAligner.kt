package com.example.kotlincompiler.engine

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Pure-Kotlin reimplementation of `zipalign -f 4`: re-writes a zip so that
 * every uncompressed (STORED) entry's data starts at a 4-byte-aligned
 * offset within the file, which is what lets Android mmap resources and
 * native libraries directly instead of copying them. Compressed
 * (DEFLATED) entries don't need alignment and are copied through as-is.
 *
 * This avoids bundling a native `zipalign` ARM binary — the algorithm
 * itself is simple enough to not need one. NOTE: this has not been
 * verified against a real device install; if `pm install` rejects the
 * output, compare against the reference algorithm in AOSP's
 * `zipalign/ZipAlign.cpp` and adjust padding/extra-field logic here.
 */
object ZipAligner {

    private const val ALIGNMENT = 4
    private const val LOCAL_HEADER_FIXED_SIZE = 30 // bytes, per the ZIP spec

    fun align(inputApk: File, outputApk: File) {
        ZipFile(inputApk).use { zip ->
            val counting = CountingOutputStream(outputApk.outputStream())
            ZipOutputStream(counting).use { zos ->
                zip.entries().asSequence().forEach { entry ->
                    writeAligned(zip, entry, zos, counting)
                }
            }
        }
    }

    private fun writeAligned(
        zip: ZipFile,
        entry: ZipEntry,
        zos: ZipOutputStream,
        counting: CountingOutputStream
    ) {
        val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
        val newEntry = ZipEntry(entry.name)
        newEntry.time = entry.time

        if (entry.method == ZipEntry.STORED) {
            // STORED entries require size/compressedSize/crc to be known
            // up front (no data descriptor), which ZipFile already has.
            newEntry.method = ZipEntry.STORED
            newEntry.size = entry.size
            newEntry.compressedSize = entry.size
            newEntry.crc = entry.crc

            val headerStart = counting.count
            val unalignedDataStart = headerStart + LOCAL_HEADER_FIXED_SIZE + nameBytes.size
            val padding = ((ALIGNMENT - (unalignedDataStart % ALIGNMENT)) % ALIGNMENT).toInt()
            if (padding > 0) {
                newEntry.setExtra(ByteArray(padding))
            }
        } else {
            newEntry.method = ZipEntry.DEFLATED
        }

        zos.putNextEntry(newEntry)
        zip.getInputStream(entry).use { it.copyTo(zos) }
        zos.closeEntry()
    }

    /** Tracks total bytes written so far, needed to compute alignment padding. */
    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()
        override fun close() = out.close()
    }
}
