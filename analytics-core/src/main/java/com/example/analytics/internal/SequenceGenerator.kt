package com.example.analytics.internal

import com.example.analytics.storage.MetaStore

/**
 * Install-wide unique, per-process increasing `seq`. Numbers are reserved from the shared
 * [MetaStore] in blocks, so processes never collide; the unused tail of a block is skipped when a
 * process dies, so the backend should treat gaps as normal and only use `seq` for ordering/dedupe.
 * Only called from the writer thread.
 */
internal class SequenceGenerator(private val meta: MetaStore, private val blockSize: Int = 100) {
    private var next = 0L
    private var end = 0L

    fun next(): Long {
        if (next >= end) {
            next = meta.reserveSequence(KEY, blockSize)
            end = next + blockSize
        }
        return next++
    }

    private companion object {
        const val KEY = "seq_next"
    }
}
