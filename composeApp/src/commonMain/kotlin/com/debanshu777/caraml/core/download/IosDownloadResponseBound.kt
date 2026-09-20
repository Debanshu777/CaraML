package com.debanshu777.caraml.core.download

private const val IOS_BACKGROUND_ID_LENGTH = 64
private const val IOS_BACKGROUND_MAX_ARTIFACT_BYTES = 1L shl 50
private const val IOS_BACKGROUND_MAX_DESCRIPTION_LENGTH =
    IOS_BACKGROUND_ID_LENGTH + 1 + IOS_BACKGROUND_ID_LENGTH + 1 + 16

/**
 * Exact download identity persisted by `NSURLSessionTask.taskDescription`.
 *
 * A background session restores the description before delivering delegate callbacks, so the
 * response limit remains available synchronously even after process death.
 */
internal data class IosBackgroundTaskDescriptor(
    val batchId: String,
    val artifactId: String,
    val expectedBytes: Long,
) {
    init {
        require(batchId.isCanonicalDownloadId()) { "Invalid background download" }
        require(artifactId.isCanonicalDownloadId()) { "Invalid background download" }
        require(expectedBytes in 1L..IOS_BACKGROUND_MAX_ARTIFACT_BYTES) { "Invalid background download" }
    }

    fun encode(): String = "$batchId:$artifactId:$expectedBytes"

    companion object {
        fun decode(value: String?): IosBackgroundTaskDescriptor? {
            if (value == null || value.length !in 131..IOS_BACKGROUND_MAX_DESCRIPTION_LENGTH) return null
            val firstSeparator = value.indexOf(':')
            val secondSeparator = value.indexOf(':', firstSeparator + 1)
            if (firstSeparator != IOS_BACKGROUND_ID_LENGTH || secondSeparator != IOS_BACKGROUND_ID_LENGTH * 2 + 1) {
                return null
            }
            if (value.indexOf(':', secondSeparator + 1) != -1) return null
            val batchId = value.substring(0, firstSeparator)
            val artifactId = value.substring(firstSeparator + 1, secondSeparator)
            val expectedText = value.substring(secondSeparator + 1)
            if (!batchId.isCanonicalDownloadId() || !artifactId.isCanonicalDownloadId()) return null
            if (expectedText.isEmpty() || expectedText.length > 16 || expectedText.first() == '0' ||
                expectedText.any { it !in '0'..'9' }
            ) {
                return null
            }
            val expectedBytes = expectedText.toLongOrNull()
                ?.takeIf { it in 1L..IOS_BACKGROUND_MAX_ARTIFACT_BYTES }
                ?: return null
            return IosBackgroundTaskDescriptor(batchId, artifactId, expectedBytes)
        }
    }
}

internal sealed interface IosDownloadBoundDecision {
    data class Progress(
        val descriptor: IosBackgroundTaskDescriptor,
        val bytesWritten: Long,
    ) : IosDownloadBoundDecision

    data class Reject(val descriptor: IosBackgroundTaskDescriptor) : IosDownloadBoundDecision

    data object Duplicate : IosDownloadBoundDecision
    data object Inactive : IosDownloadBoundDecision
    data object Invalid : IosDownloadBoundDecision
}

internal enum class IosDownloadBoundCompletion {
    ACTIVE,
    REJECTED,
    STOPPED,
    MISSING,
}

/**
 * Pure state machine behind the iOS URLSession delegate response limit.
 *
 * Its owner serializes access. Keeping Foundation out of this class makes all boundary and
 * restoration behavior executable on every host test target.
 */
internal class IosDownloadResponseBound {
    private val tasks = mutableMapOf<String, TaskState>()

    fun register(taskId: String, persistedTaskDescription: String?): Boolean {
        if (!taskId.isPlatformTaskId()) return false
        val descriptor = IosBackgroundTaskDescriptor.decode(persistedTaskDescription) ?: return false
        val current = tasks[taskId]
        if (current == null) {
            tasks[taskId] = TaskState.Active(descriptor, highestBytesWritten = -1L)
            return true
        }
        if (current.descriptor != descriptor) {
            tasks[taskId] = TaskState.Rejected(current.descriptor)
            return false
        }
        return current is TaskState.Active
    }

    fun inspect(
        taskId: String,
        persistedTaskDescription: String?,
        totalBytesWritten: Long,
        declaredExpectedBytes: Long,
    ): IosDownloadBoundDecision {
        if (!taskId.isPlatformTaskId()) return IosDownloadBoundDecision.Invalid
        val descriptor = IosBackgroundTaskDescriptor.decode(persistedTaskDescription)
            ?: return IosDownloadBoundDecision.Invalid
        val state = tasks[taskId]
        val active = when {
            state == null -> TaskState.Active(descriptor, highestBytesWritten = -1L)
                .also { tasks[taskId] = it }
            state.descriptor != descriptor -> {
                tasks[taskId] = TaskState.Rejected(state.descriptor)
                return IosDownloadBoundDecision.Reject(state.descriptor)
            }
            state is TaskState.Rejected || state is TaskState.Stopped -> {
                return IosDownloadBoundDecision.Inactive
            }
            else -> state as TaskState.Active
        }
        if (totalBytesWritten < 0L || declaredExpectedBytes > descriptor.expectedBytes ||
            totalBytesWritten > descriptor.expectedBytes
        ) {
            tasks[taskId] = TaskState.Rejected(descriptor)
            return IosDownloadBoundDecision.Reject(descriptor)
        }
        if (totalBytesWritten <= active.highestBytesWritten) return IosDownloadBoundDecision.Duplicate
        active.highestBytesWritten = totalBytesWritten
        return IosDownloadBoundDecision.Progress(descriptor, totalBytesWritten)
    }

    fun stop(taskId: String, persistedTaskDescription: String? = null) {
        if (!taskId.isPlatformTaskId()) return
        val descriptor = tasks[taskId]?.descriptor
            ?: IosBackgroundTaskDescriptor.decode(persistedTaskDescription)
            ?: return
        tasks[taskId] = TaskState.Stopped(descriptor)
    }

    fun complete(taskId: String): IosDownloadBoundCompletion = when (tasks.remove(taskId)) {
        is TaskState.Active -> IosDownloadBoundCompletion.ACTIVE
        is TaskState.Rejected -> IosDownloadBoundCompletion.REJECTED
        is TaskState.Stopped -> IosDownloadBoundCompletion.STOPPED
        null -> IosDownloadBoundCompletion.MISSING
    }

    private sealed interface TaskState {
        val descriptor: IosBackgroundTaskDescriptor

        data class Active(
            override val descriptor: IosBackgroundTaskDescriptor,
            var highestBytesWritten: Long,
        ) : TaskState

        data class Rejected(override val descriptor: IosBackgroundTaskDescriptor) : TaskState
        data class Stopped(override val descriptor: IosBackgroundTaskDescriptor) : TaskState
    }
}

private fun String.isCanonicalDownloadId(): Boolean =
    length == IOS_BACKGROUND_ID_LENGTH && all { it in '0'..'9' || it in 'a'..'f' }

private fun String.isPlatformTaskId(): Boolean =
    length in 1..20 && all { it in '0'..'9' }
