package com.debanshu777.caraml.core.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IosDownloadResponseBoundTest {
    @Test
    fun declaredResponseLargerThanExactArtifactIsRejectedOnlyOnce() {
        val subject = IosDownloadResponseBound()
        val description = descriptor(expectedBytes = 10L).encode()

        val first = subject.inspect("41", description, totalBytesWritten = 1L, declaredExpectedBytes = 11L)
        val duplicate = subject.inspect("41", description, totalBytesWritten = 2L, declaredExpectedBytes = 11L)

        assertIs<IosDownloadBoundDecision.Reject>(first)
        assertEquals(10L, first.descriptor.expectedBytes)
        assertEquals(IosDownloadBoundDecision.Inactive, duplicate)
        assertEquals(IosDownloadBoundCompletion.REJECTED, subject.complete("41"))
    }

    @Test
    fun unknownLengthResponseIsRejectedAsSoonAsWrittenBytesCrossExactSize() {
        val subject = IosDownloadResponseBound()
        val description = descriptor(expectedBytes = 10L).encode()

        val exactlyEqual = subject.inspect("42", description, totalBytesWritten = 10L, declaredExpectedBytes = -1L)
        val crossed = subject.inspect("42", description, totalBytesWritten = 11L, declaredExpectedBytes = -1L)

        assertEquals(IosDownloadBoundDecision.Progress(descriptor(expectedBytes = 10L), 10L), exactlyEqual)
        assertIs<IosDownloadBoundDecision.Reject>(crossed)
    }

    @Test
    fun exactlyEqualDeclaredAndWrittenBytesContinue() {
        val subject = IosDownloadResponseBound()
        val expected = descriptor(expectedBytes = 10L)

        assertEquals(
            IosDownloadBoundDecision.Progress(expected, 10L),
            subject.inspect("43", expected.encode(), totalBytesWritten = 10L, declaredExpectedBytes = 10L),
        )
    }

    @Test
    fun duplicateAndOutOfOrderCallbacksCannotRegressPersistedProgress() {
        val subject = IosDownloadResponseBound()
        val expected = descriptor(expectedBytes = 10L)
        val description = expected.encode()

        assertEquals(
            IosDownloadBoundDecision.Progress(expected, 7L),
            subject.inspect("44", description, totalBytesWritten = 7L, declaredExpectedBytes = 10L),
        )
        assertEquals(
            IosDownloadBoundDecision.Duplicate,
            subject.inspect("44", description, totalBytesWritten = 7L, declaredExpectedBytes = 10L),
        )
        assertEquals(
            IosDownloadBoundDecision.Duplicate,
            subject.inspect("44", description, totalBytesWritten = 6L, declaredExpectedBytes = 10L),
        )
        assertEquals(
            IosDownloadBoundDecision.Progress(expected, 8L),
            subject.inspect("44", description, totalBytesWritten = 8L, declaredExpectedBytes = 10L),
        )
    }

    @Test
    fun restoredTaskReconstructsExactBoundFromPersistedDescription() {
        val persisted = descriptor(expectedBytes = 4_294_967_296L).encode()

        val restoredProcess = IosDownloadResponseBound()

        val decision = restoredProcess.inspect(
            taskId = "45",
            persistedTaskDescription = persisted,
            totalBytesWritten = 4_294_967_297L,
            declaredExpectedBytes = -1L,
        )
        assertIs<IosDownloadBoundDecision.Reject>(decision)
        assertEquals(4_294_967_296L, decision.descriptor.expectedBytes)
    }

    @Test
    fun stoppedTaskIgnoresLateCallbacksUntilItsCompletion() {
        val subject = IosDownloadResponseBound()
        val expected = descriptor(expectedBytes = 10L)

        subject.stop("46", expected.encode())

        assertEquals(
            IosDownloadBoundDecision.Inactive,
            subject.inspect("46", expected.encode(), totalBytesWritten = 11L, declaredExpectedBytes = -1L),
        )
        assertEquals(IosDownloadBoundCompletion.STOPPED, subject.complete("46"))
    }

    @Test
    fun malformedOrUnboundedRestoredDescriptionFailsClosedWithoutCachingInput() {
        val subject = IosDownloadResponseBound()

        assertEquals(
            IosDownloadBoundDecision.Invalid,
            subject.inspect(
                taskId = "47",
                persistedTaskDescription = "a".repeat(64) + ":" + "b".repeat(64),
                totalBytesWritten = 1L,
                declaredExpectedBytes = -1L,
            ),
        )
        assertEquals(IosDownloadBoundCompletion.MISSING, subject.complete("47"))
    }

    @Test
    fun invalidNegativeWrittenCountFailsClosed() {
        val subject = IosDownloadResponseBound()
        val expected = descriptor(expectedBytes = 10L)

        assertIs<IosDownloadBoundDecision.Reject>(
            subject.inspect("48", expected.encode(), totalBytesWritten = -1L, declaredExpectedBytes = 10L),
        )
    }

    private fun descriptor(expectedBytes: Long) = IosBackgroundTaskDescriptor(
        batchId = "a".repeat(64),
        artifactId = "b".repeat(64),
        expectedBytes = expectedBytes,
    )
}
