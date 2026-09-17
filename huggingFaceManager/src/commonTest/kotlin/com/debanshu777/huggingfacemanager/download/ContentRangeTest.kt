package com.debanshu777.huggingfacemanager.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContentRangeTest {
    @Test
    fun parsesExactByteRange() {
        assertEquals(ContentRange(start = 4L, endInclusive = 9L, total = 10L), ContentRange.parse("bytes 4-9/10"))
    }

    @Test
    fun rejectsMalformedOverflowAndInconsistentRanges() {
        listOf(
            "bytes */10",
            "bytes 9-4/10",
            "bytes 4-10/10",
            "bytes -1-4/10",
            "items 4-9/10",
            "bytes 4-9/${Long.MAX_VALUE}0",
        ).forEach { assertNull(ContentRange.parse(it), it) }
    }
}
