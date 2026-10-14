package com.debanshu777.caraml.core.recommendation

import kotlinx.coroutines.CancellationException

internal data class BoundedCollectionSnapshot<T>(
    val values: List<T>,
    val limitExceeded: Boolean,
)

/** Snapshots untrusted iterables without consulting a potentially dishonest size. */
internal fun <T> boundedCollectionSnapshot(
    source: Iterable<T>,
    limit: Int,
): BoundedCollectionSnapshot<T> {
    require(limit >= 0)
    val values = ArrayList<T>(minOf(limit, 16))
    return try {
        val iterator = source.iterator()
        while (values.size < limit && iterator.hasNext()) {
            values += iterator.next()
        }
        BoundedCollectionSnapshot(
            values = values.toList(),
            limitExceeded = iterator.hasNext(),
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        BoundedCollectionSnapshot(
            values = values.toList(),
            limitExceeded = true,
        )
    }
}
