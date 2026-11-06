package com.calyptra.app.blocklist

import java.io.File
import java.io.IOException

/**
 * File I/O for the cached remote lists, kept free of Android so the
 * all-or-nothing replacement rules can be unit-tested on the JVM.
 */
object BlocklistCacheStore {

    /** Reads a cached list, honouring the same parsing rules as downloads. */
    fun read(file: File, maxDomains: Int): Set<String> {
        if (!file.exists()) return emptySet()
        return file.useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.trim().lowercase() }
                .take(maxDomains)
                .toSet()
        }
    }

    /**
     * Replaces [file] with [newDomains] only when the update is acceptable:
     * plausible per [BlocklistUpdatePolicy] and within [maxDomains]. Otherwise
     * returns false and leaves the previous cache untouched. An oversized set is
     * rejected outright rather than truncated, so a cache is never a partial copy
     * of an update.
     */
    fun replaceIfAcceptable(file: File, newDomains: Set<String>, maxDomains: Int): Boolean {
        if (newDomains.size > maxDomains) return false
        if (!BlocklistUpdatePolicy.isPlausibleUpdate(newDomains.size, read(file, maxDomains).size)) {
            return false
        }
        writeAtomically(file, newDomains)
        return true
    }

    /** Writes [domains] one-per-line to [file] via a temp file + rename so a
     *  crash mid-write can never leave a partially-written (and thus shrunken)
     *  cache behind. On Android's internal storage the rename replaces the
     *  destination atomically. A failed write removes the temp file and leaves
     *  [file] as it was. */
    fun writeAtomically(file: File, domains: Set<String>) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.bufferedWriter().use { writer ->
                domains.forEach { domain ->
                    writer.write(domain)
                    writer.newLine()
                }
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
        if (!tmp.renameTo(file)) {
            // Some filesystems won't rename onto an existing file; replace it.
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("Failed to commit blocklist cache: ${file.name}")
            }
        }
    }
}
