package com.debanshu777.caraml.core.download

import com.debanshu777.caraml.core.recommendation.storage.EncodedModelEvidence
import com.debanshu777.caraml.core.recommendation.storage.InstalledEvidenceState
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosDownloadResponseBoundTest {
    @Test
    fun restoredTaskRejectsSameSizeScopedRequestChangedSinceRegistration() {
        val oldRequest = request(remoteObjectId = "b".repeat(40))
        val currentRequest = request(remoteObjectId = "c".repeat(40))
        val batch = batchWithPersistedArtifactId(
            persistedRequest = oldRequest,
            currentRequest = currentRequest,
        )
        val descriptor = IosBackgroundTaskDescriptor(
            batchId = batch.batchId,
            artifactId = batch.artifacts.single().artifactId,
            expectedBytes = currentRequest.metadata.artifact.expectedBytes,
        )

        assertEquals(
            DownloadFailureCode.INTEGRITY,
            iosPersistedTaskBindingFailure(batch, descriptor),
        )
    }

    @Test
    fun completionBindingAcceptsExactCurrentGitOidSizeOnlyRequest() {
        val currentRequest = request(remoteObjectId = "b".repeat(40))
        val batch = batchWithPersistedArtifactId(
            persistedRequest = currentRequest,
            currentRequest = currentRequest,
        )
        val descriptor = IosBackgroundTaskDescriptor(
            batchId = batch.batchId,
            artifactId = batch.artifacts.single().artifactId,
            expectedBytes = currentRequest.metadata.artifact.expectedBytes,
        )

        assertNull(iosPersistedTaskBindingFailure(batch, descriptor))
    }

    @Test
    fun typedRequestRejectsUnscopedDestinationBeforeCompletionBinding() {
        val scoped = request(remoteObjectId = "b".repeat(40))

        assertFailsWith<IllegalArgumentException> {
            scoped.metadata.copy(
                destinationRelativePath = scoped.metadata.artifact.relativePath,
            )
        }
    }

    @Test
    fun declaredResponseLargerThanExactArtifactIsRejectedOnlyOnce() {
        val subject = IosDownloadResponseBound()
        val description = descriptor(expectedBytes = 10L).encode()

        val first = subject.inspect("41", description, totalBytesWritten = 1L, declaredExpectedBytes = 11L)
        val duplicate = subject.inspect("41", description, totalBytesWritten = 2L, declaredExpectedBytes = 11L)

        assertIs<IosDownloadBoundDecision.Reject>(first)
        assertEquals(10L, first.descriptor.expectedBytes)
        assertEquals(IosBackgroundTaskDisposition.REJECTED, first.descriptor.disposition)
        assertEquals(IosDownloadBoundDecision.Inactive, duplicate)
        assertEquals(IosDownloadBoundCompletion.REJECTED, subject.complete("41", first.descriptor.encode()))
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

        val stopped = subject.stop("46", expected.encode())

        assertEquals(IosBackgroundTaskDisposition.STOPPED, stopped?.disposition)
        assertEquals(
            IosDownloadBoundDecision.Inactive,
            subject.inspect("46", stopped?.encode(), totalBytesWritten = 11L, declaredExpectedBytes = -1L),
        )
        assertEquals(IosDownloadBoundCompletion.STOPPED, subject.complete("46", stopped?.encode()))
    }

    @Test
    fun malformedOrUnboundedRestoredDescriptionFailsClosedWithoutCachingInput() {
        val subject = IosDownloadResponseBound()

        assertEquals(
            IosDownloadBoundDecision.Invalid(
                IosBackgroundTaskKey("a".repeat(64), "b".repeat(64)),
            ),
            subject.inspect(
                taskId = "47",
                persistedTaskDescription = "a".repeat(64) + ":" + "b".repeat(64),
                totalBytesWritten = 1L,
                declaredExpectedBytes = -1L,
            ),
        )
        assertEquals(
            IosDownloadBoundCompletion.INVALID,
            subject.complete("47", "a".repeat(64) + ":" + "b".repeat(64)),
        )
    }

    @Test
    fun invalidNegativeWrittenCountFailsClosed() {
        val subject = IosDownloadResponseBound()
        val expected = descriptor(expectedBytes = 10L)

        assertIs<IosDownloadBoundDecision.Reject>(
            subject.inspect("48", expected.encode(), totalBytesWritten = -1L, declaredExpectedBytes = 10L),
        )
    }

    @Test
    fun invalidDeclaredNegativeLengthFailsClosed() {
        val subject = IosDownloadResponseBound()
        val expected = descriptor(expectedBytes = 10L)

        assertIs<IosDownloadBoundDecision.Reject>(
            subject.inspect("53", expected.encode(), totalBytesWritten = 0L, declaredExpectedBytes = -2L),
        )
    }

    @Test
    fun overflowDispositionSurvivesProcessRecreationAndCancellationCompletion() {
        val activeProcess = IosDownloadResponseBound()
        val active = descriptor(expectedBytes = 10L)
        val rejected = assertIs<IosDownloadBoundDecision.Reject>(
            activeProcess.inspect("49", active.encode(), totalBytesWritten = 11L, declaredExpectedBytes = -1L),
        ).descriptor
        var persistedByUrlSession = active.encode()
        var cancelled = false
        persistIosRejectionBeforeCancellation(
            descriptor = rejected,
            persistTaskDescription = { persistedByUrlSession = it },
            cancel = { cancelled = true },
        )

        val restoredProcess = IosDownloadResponseBound()

        assertTrue(cancelled)
        assertEquals(IosBackgroundTaskDisposition.REJECTED, rejected.disposition)
        assertEquals(
            IosDownloadBoundCompletion.REJECTED,
            restoredProcess.complete("49", persistedByUrlSession),
        )
    }

    @Test
    fun durableRejectedSnapshotOverridesStaleActiveMemoryAtCompletion() {
        val subject = IosDownloadResponseBound()
        val active = descriptor(expectedBytes = 10L)
        assertTrue(subject.registerNewTask("52", active.encode()))

        assertEquals(
            IosDownloadBoundCompletion.REJECTED,
            subject.complete(
                "52",
                active.withDisposition(IosBackgroundTaskDisposition.REJECTED).encode(),
            ),
        )
    }

    @Test
    fun invalidDurableSnapshotFailsClosedDespiteStaleActiveMemory() {
        val subject = IosDownloadResponseBound()
        val active = descriptor(expectedBytes = 10L)
        assertTrue(subject.registerNewTask("54", active.encode()))

        assertEquals(
            IosDownloadBoundCompletion.INVALID,
            subject.complete("54", "a".repeat(64) + ":" + "b".repeat(64)),
        )
    }

    @Test
    fun stopAfterCompletionDoesNotResurrectCompletedTaskGeneration() {
        val subject = IosDownloadResponseBound()
        val active = descriptor(expectedBytes = 10L)
        repeat(256) {
            assertTrue(subject.registerNewTask("50", active.encode()))
            assertEquals(IosDownloadBoundCompletion.ACTIVE, subject.complete("50", active.encode()))

            val stoppedSnapshot = subject.stop("50", active.encode())

            assertEquals(IosBackgroundTaskDisposition.STOPPED, stoppedSnapshot?.disposition)
            assertEquals(IosDownloadBoundCompletion.MISSING, subject.complete("50"))
        }
        assertIs<IosDownloadBoundDecision.Progress>(
            subject.inspect("50", active.encode(), totalBytesWritten = 1L, declaredExpectedBytes = 10L),
        )
    }

    @Test
    fun rejectedDispositionCannotBeDowngradedByConcurrentStop() {
        val subject = IosDownloadResponseBound()
        val active = descriptor(expectedBytes = 10L)
        val rejected = assertIs<IosDownloadBoundDecision.Reject>(
            subject.inspect("51", active.encode(), totalBytesWritten = 11L, declaredExpectedBytes = -1L),
        ).descriptor

        val stopped = subject.stop("51", rejected.encode())

        assertEquals(IosBackgroundTaskDisposition.REJECTED, stopped?.disposition)
        assertEquals(IosDownloadBoundCompletion.REJECTED, subject.complete("51", stopped?.encode()))
    }

    @Test
    fun priorTaskDescriptionFormatsAreRecoverableOnlyForTerminalFailure() {
        val batchId = "a".repeat(64)
        val artifactId = "b".repeat(64)
        val oldUnbounded = "$batchId:$artifactId"
        val oldBounded = "$batchId:$artifactId:10"

        assertEquals(null, IosBackgroundTaskDescriptor.decode(oldUnbounded))
        assertEquals(null, IosBackgroundTaskDescriptor.decode(oldBounded))
        assertEquals(
            IosBackgroundTaskKey(batchId, artifactId),
            IosBackgroundTaskDescriptor.recoverKeyForTerminalFailure(oldUnbounded),
        )
        assertEquals(
            IosBackgroundTaskKey(batchId, artifactId),
            IosBackgroundTaskDescriptor.recoverKeyForTerminalFailure(oldBounded),
        )
    }

    @Test
    fun currentTaskDescriptionIsCanonicalAndStrictlyBounded() {
        val batchId = "a".repeat(64)
        val artifactId = "b".repeat(64)
        val maximum = 1L shl 50
        IosBackgroundTaskDisposition.entries.forEach { disposition ->
            val expected = IosBackgroundTaskDescriptor(batchId, artifactId, maximum, disposition)
            assertEquals(expected, IosBackgroundTaskDescriptor.decode(expected.encode()))
        }

        assertNull(IosBackgroundTaskDescriptor.decode("2:a:$batchId:$artifactId:10"))
        assertNull(IosBackgroundTaskDescriptor.decode("1:x:$batchId:$artifactId:10"))
        assertNull(IosBackgroundTaskDescriptor.decode("1:a:${"A".repeat(64)}:$artifactId:10"))
        assertNull(IosBackgroundTaskDescriptor.decode("1:a:$batchId:$artifactId:010"))
        assertNull(IosBackgroundTaskDescriptor.decode("1:a:$batchId:$artifactId:${maximum + 1L}"))
        assertNull(IosBackgroundTaskDescriptor.decode("1:a:$batchId:$artifactId:10:extra"))
    }

    @Test
    fun delegateAdapterPersistsRejectedSnapshotBeforeCancellation() {
        val events = mutableListOf<String>()
        var snapshot: String? = null

        persistIosRejectionBeforeCancellation(
            descriptor = descriptor(expectedBytes = 10L),
            persistTaskDescription = {
                snapshot = it
                events += "persist"
            },
            cancel = {
                assertEquals(
                    IosBackgroundTaskDisposition.REJECTED,
                    IosBackgroundTaskDescriptor.decode(snapshot)?.disposition,
                )
                events += "cancel"
            },
        )

        assertEquals(listOf("persist", "cancel"), events)
    }

    @Test
    fun stoppedTaskStillEnumeratedByUrlSessionDoesNotOwnOrBlockResume() {
        val active = descriptor(expectedBytes = 10L)
        val stopped = active.withDisposition(IosBackgroundTaskDisposition.STOPPED)
        val enumeratedDescriptions = listOf(stopped.encode())

        assertFalse(
            enumeratedDescriptions.any { description ->
                iosActiveTaskKey(description)?.batchId == active.batchId
            },
        )
        assertEquals(active.key, iosActiveTaskKey(active.encode()))
        assertNull(
            iosActiveTaskKey(
                active.withDisposition(IosBackgroundTaskDisposition.REJECTED).encode(),
            ),
        )
    }

    @Test
    fun relaunchCompletesOldStoppedTaskBeforeRegisteringFreshReplacement() {
        val restoredProcess = IosDownloadResponseBound()
        val active = descriptor(expectedBytes = 10L)
        val stopped = active.withDisposition(IosBackgroundTaskDisposition.STOPPED)

        assertEquals(IosDownloadBoundCompletion.STOPPED, restoredProcess.complete("60", stopped.encode()))
        assertTrue(restoredProcess.registerNewTask("61", active.encode()))
        assertIs<IosDownloadBoundDecision.Progress>(
            restoredProcess.inspect("61", active.encode(), totalBytesWritten = 1L, declaredExpectedBytes = 10L),
        )
    }

    @Test
    fun oldStoppedCompletionAfterResumeCannotMutateActiveReplacement() {
        val subject = IosDownloadResponseBound()
        val active = descriptor(expectedBytes = 10L)
        val stopped = active.withDisposition(IosBackgroundTaskDisposition.STOPPED)
        assertTrue(subject.registerNewTask("63", active.encode()))

        assertEquals(IosDownloadBoundCompletion.STOPPED, subject.complete("62", stopped.encode()))

        assertEquals(
            IosDownloadBoundDecision.Progress(active, 2L),
            subject.inspect("63", active.encode(), totalBytesWritten = 2L, declaredExpectedBytes = 10L),
        )
    }

    @Test
    fun activeSnapshotCompletionMakesItsLaterRestoreRegistrationStale() {
        val subject = IosDownloadResponseBound()
        val active = descriptor(expectedBytes = 10L)
        val capturedBeforeGetAllTasks = subject.captureRestoreRegistrationGeneration()

        assertEquals(IosDownloadBoundCompletion.ACTIVE, subject.complete("70", active.encode()))
        assertFalse(subject.registerRestored("70", active.encode(), capturedBeforeGetAllTasks))
        assertNull(subject.stop("70"))
    }

    @Test
    fun completionGenerationRemainsBoundedAcrossManyTasksAndFreshIdsStillRegister() {
        val subject = IosDownloadResponseBound()
        val active = descriptor(expectedBytes = 10L)
        val staleGeneration = subject.captureRestoreRegistrationGeneration()
        repeat(10_000) { index ->
            subject.complete((1_000 + index).toString(), active.encode())
        }

        assertFalse(subject.registerRestored("80", active.encode(), staleGeneration))
        assertTrue(
            subject.registerRestored(
                "11000",
                active.encode(),
                subject.captureRestoreRegistrationGeneration(),
            ),
        )
    }

    @Test
    fun freshProcessCanRegisterRestoredActiveTaskWithFreshGeneration() {
        val priorProcess = IosDownloadResponseBound()
        val active = descriptor(expectedBytes = 10L)
        val staleGeneration = priorProcess.captureRestoreRegistrationGeneration()
        priorProcess.complete("90", active.encode())
        assertFalse(priorProcess.registerRestored("90", active.encode(), staleGeneration))

        val restoredProcess = IosDownloadResponseBound()

        assertTrue(
            restoredProcess.registerRestored(
                "91",
                active.encode(),
                restoredProcess.captureRestoreRegistrationGeneration(),
            ),
        )
    }

    private fun descriptor(expectedBytes: Long) = IosBackgroundTaskDescriptor(
        batchId = "a".repeat(64),
        artifactId = "b".repeat(64),
        expectedBytes = expectedBytes,
        disposition = IosBackgroundTaskDisposition.ACTIVE,
    )

    private fun request(remoteObjectId: String): DownloadArtifactRequest {
        val identity = requireNotNull(
            DownloadArtifactIdentity.create(
                repositoryId = "org/model",
                immutableRevision = "d".repeat(40),
                relativePath = "model.gguf",
                remoteObjectId = remoteObjectId,
                expectedBytes = 10L,
            ),
        )
        return DownloadArtifactRequest(
            metadata = DownloadMetadataDTO(
                artifact = identity,
                logicalRole = "model",
                sizeBytes = identity.expectedBytes,
                author = null,
                libraryName = null,
                pipelineTag = null,
            ),
            primary = true,
        )
    }

    private fun batchWithPersistedArtifactId(
        persistedRequest: DownloadArtifactRequest,
        currentRequest: DownloadArtifactRequest,
    ): DownloadBatchSnapshot {
        val batchId = "a".repeat(64)
        return DownloadBatchSnapshot(
            batchId = batchId,
            ownerModelId = "org/model",
            modelType = "text",
            displayName = "model.gguf",
            state = DownloadBatchState.RUNNING,
            userIntent = DownloadUserIntent.RUN,
            artifacts = listOf(
                DownloadArtifactSnapshot(
                    artifactId = downloadBatchArtifactId(batchId, persistedRequest),
                    batchId = batchId,
                    request = currentRequest,
                    state = DownloadArtifactState.RUNNING,
                    userIntent = DownloadUserIntent.RUN,
                    bytesReceived = 0L,
                    expectedBytes = currentRequest.metadata.artifact.expectedBytes,
                ),
            ),
            evidence = EncodedModelEvidence(
                state = InstalledEvidenceState.REQUIRES_ENRICHMENT,
                schemaVersion = 1,
                payload = "{}",
                sha256 = "f".repeat(64),
            ),
        )
    }
}
