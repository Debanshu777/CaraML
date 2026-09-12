package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.huggingfacemanager.download.ArtifactManifest
import com.debanshu777.huggingfacemanager.download.ArtifactManifestEntry
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.download.StoredArtifactKind
import com.debanshu777.huggingfacemanager.download.StoredArtifactSnapshot
import com.debanshu777.huggingfacemanager.model.DIFFUSERS_BUNDLE_DB_FILENAME
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class LocalArtifactIdentityResolverTest {
    @Test
    fun loadRequestRejectsEveryStaleDescriptorIdentityFieldAfterResolvingBytes() = runTest {
        withRoot { storage, root ->
            val bytes = "model".encodeToByteArray()
            val digest = bytes.sha256()
            val path = write(root / "owner/model/model.gguf", bytes)
            val revision = "a".repeat(40)
            val downloadIdentity = checkNotNull(
                DownloadArtifactIdentity.create(
                    repositoryId = "owner/model",
                    immutableRevision = revision,
                    relativePath = "model.gguf",
                    remoteObjectId = "sha256:$digest",
                    expectedBytes = bytes.size.toLong(),
                ),
            )
            val manifest = checkNotNull(
                ArtifactManifest.create(
                    listOf(
                        checkNotNull(
                            ArtifactManifestEntry.create(
                                logicalRole = "model",
                                identity = downloadIdentity,
                                byteCount = bytes.size.toLong(),
                                contentSha256 = digest,
                            ),
                        ),
                    ),
                ),
            )
            val plan = task6LlmPlan()
            val planAssessment = task6PlanAssessment(plan = plan)
            val assessment = task6Assessment(
                plans = listOf(planAssessment),
                assessmentKey = "artifact-binding",
            )
            val recommendation = PersonalizedRecommendation(
                assessmentKey = "artifact-binding",
                category = RecommendationCategory.RECOMMENDED,
                selectedPlan = plan,
                reasons = emptyList(),
                profile = RecommendationProfile(),
                selectedPlanAssessment = planAssessment,
            )
            val resolver = resolver(storage, manifest)
            val installedModel = model(path, bytes.size.toLong())

            assertIs<LoadRequestResolution.Ready>(
                resolver.createLoadRequest(
                    installedModel,
                    emptyList(),
                    descriptorIdentity(revision, "model.gguf", bytes.size.toLong(), digest),
                    assessment,
                    recommendation,
                ),
            )

            val staleDescriptors = listOf(
                descriptorIdentity("b".repeat(40), "model.gguf", bytes.size.toLong(), digest),
                descriptorIdentity(revision, "stale.gguf", bytes.size.toLong(), digest),
                descriptorIdentity(revision, "model.gguf", bytes.size.toLong() + 1L, digest),
                descriptorIdentity(revision, "model.gguf", bytes.size.toLong(), "f".repeat(64)),
            )
            staleDescriptors.forEach { descriptor ->
                val rejected = assertIs<LoadRequestResolution.Rejected>(
                    resolver.createLoadRequest(
                        installedModel,
                        emptyList(),
                        descriptor,
                        assessment,
                        recommendation,
                    ),
                )
                assertEquals(ArtifactIdentityRejection.STALE_MANIFEST, rejected.reason)
            }
        }
    }

    @Test
    fun validSingleFileHubManifestProducesCommitIdentity() = runTest {
        withRoot { storage, root ->
            val bytes = "model".encodeToByteArray()
            val path = write(root / "owner/model/model.gguf", bytes)
            val manifest = manifest(
                manifestEntry("model", "owner/model", "a".repeat(40), "model.gguf", bytes),
            )
            val resolver = resolver(storage, manifest)

            val verified = assertIs<ArtifactIdentityResolution.Verified>(
                resolver.resolve(model(path, bytes.size.toLong()), emptyList()),
            )

            val revision = assertIs<RevisionIdentity.HubCommit>(verified.artifact.revisionIdentity)
            assertEquals(listOf(RepositoryCommit("owner/model", "a".repeat(40))), revision.commits)
            assertEquals(bytes.sha256(), verified.artifact.components.single().contentSha256)
        }
    }

    @Test
    fun validMultiRepositoryDiffusionBundleKeepsEveryRepositoryCommitAndRole() = runTest {
        withRoot { storage, root ->
            val primary = "primary".encodeToByteArray()
            val vae = "vae".encodeToByteArray()
            val primaryPath = write(root / "owner/model/model.safetensors", primary)
            val vaePath = write(root / "other/vae/vae.safetensors", vae)
            val identities = listOf(
                downloadIdentity("owner/model", "a".repeat(40), "model.safetensors", primary.size.toLong()),
                downloadIdentity("other/vae", "b".repeat(40), "vae.safetensors", vae.size.toLong()),
            )
            val bundle = checkNotNull(com.debanshu777.huggingfacemanager.download.artifactBundleId(identities))
            val manifest = checkNotNull(
                ArtifactManifest.create(
                    listOf(
                        checkNotNull(ArtifactManifestEntry.create("model", identities[0], primary.size.toLong(), primary.sha256(), bundle)),
                        checkNotNull(ArtifactManifestEntry.create("vae", identities[1], vae.size.toLong(), vae.sha256(), bundle)),
                    ),
                ),
            )
            val components = listOf(component("other/vae", "vae.safetensors", "vae", vaePath, vae.size.toLong()))

            val verified = assertIs<ArtifactIdentityResolution.Verified>(
                resolver(storage, manifest).resolve(
                    model(primaryPath, primary.size.toLong(), filename = "model.safetensors"),
                    components,
                ),
            )

            assertEquals(listOf("model", "vae"), verified.artifact.components.map { it.logicalRole })
            assertEquals(
                listOf(RepositoryCommit("other/vae", "b".repeat(40)), RepositoryCommit("owner/model", "a".repeat(40))),
                assertIs<RevisionIdentity.HubCommit>(verified.artifact.revisionIdentity).commits,
            )
        }
    }

    @Test
    fun loadRequestRejectsDiffusionComponentsWhoseVerifiedRolesWereSwapped() = runTest {
        withRoot { storage, root ->
            val revision = "a".repeat(40)
            val primaryBytes = "primary".encodeToByteArray()
            val vaeBytes = "vae".encodeToByteArray()
            val clipBytes = "clip".encodeToByteArray()
            val primaryPath = write(root / "owner/model/model.safetensors", primaryBytes)
            val vaePath = write(root / "other/vae/vae.safetensors", vaeBytes)
            val clipPath = write(root / "other/clip/clip.safetensors", clipBytes)
            val primaryIdentity = downloadIdentity("owner/model", revision, "model.safetensors", primaryBytes.size.toLong())
            val vaeIdentity = downloadIdentity("other/vae", "b".repeat(40), "vae.safetensors", vaeBytes.size.toLong())
            val clipIdentity = downloadIdentity("other/clip", "c".repeat(40), "clip.safetensors", clipBytes.size.toLong())
            val manifest = checkNotNull(
                ArtifactManifest.create(
                    listOf(
                        checkNotNull(ArtifactManifestEntry.create("model", primaryIdentity, primaryBytes.size.toLong(), primaryBytes.sha256())),
                        checkNotNull(ArtifactManifestEntry.create("clip_l", vaeIdentity, vaeBytes.size.toLong(), vaeBytes.sha256())),
                        checkNotNull(ArtifactManifestEntry.create("vae", clipIdentity, clipBytes.size.toLong(), clipBytes.sha256())),
                    ),
                ),
            )
            val descriptor = DiffusionModelDescriptor(
                repositoryId = "owner/model",
                revision = revision,
                components = listOf(
                    diffusionComponent(primaryIdentity, primaryBytes.sha256(), null, isPrimary = true),
                    diffusionComponent(vaeIdentity, vaeBytes.sha256(), ComponentRole.VAE),
                    diffusionComponent(clipIdentity, clipBytes.sha256(), ComponentRole.CLIP_L),
                ),
                mode = DiffusionMode.IMAGE,
                family = "SDXL",
                quantizationDistribution = emptySet(),
                requiredComponentsPresent = true,
                requiredEngineFeatures = emptySet(),
                evidence = emptyList(),
            )
            val plan = task6DiffusionPlan()
            val planAssessment = task6PlanAssessment(plan = plan)
            val assessment = task6Assessment(
                plans = listOf(planAssessment),
                assessmentKey = "diffusion-role-binding",
            )
            val recommendation = PersonalizedRecommendation(
                assessmentKey = assessment.assessmentKey,
                category = RecommendationCategory.RECOMMENDED,
                selectedPlan = plan,
                reasons = emptyList(),
                profile = RecommendationProfile(),
                selectedPlanAssessment = planAssessment,
            )

            val rejected = assertIs<LoadRequestResolution.Rejected>(
                resolver(storage, manifest).createLoadRequest(
                    model(primaryPath, primaryBytes.size.toLong(), "model.safetensors"),
                    listOf(
                        component("other/vae", "vae.safetensors", "vae", vaePath, vaeBytes.size.toLong()),
                        component("other/clip", "clip.safetensors", "clip_l", clipPath, clipBytes.size.toLong(), id = 2),
                    ),
                    descriptor,
                    assessment,
                    recommendation,
                ),
            )

            assertEquals(ArtifactIdentityRejection.STALE_MANIFEST, rejected.reason)
        }
    }

    @Test
    fun completeDirectoryManifestProducesTypedTargetAndPrimaryMutationInvalidatesIt() = runTest {
        withRoot { storage, root ->
            val modelRoot = root / "owner/model"
            val files = listOf(
                Triple("model", "unet/diffusion_pytorch_model.safetensors", "unet"),
                Triple("diffusers-vae", "vae/diffusion_pytorch_model.safetensors", "vae"),
                Triple("diffusers-clip-l", "text_encoder/model.safetensors", "clip-l"),
                Triple("diffusers-clip-g", "text_encoder_2/model.safetensors", "clip-g"),
            )
            val entries = files.map { (role, relativePath, contents) ->
                val bytes = contents.encodeToByteArray()
                write(modelRoot / relativePath, bytes)
                manifestEntry(role, "owner/model", "a".repeat(40), relativePath, bytes)
            }
            val resolver = resolver(storage, manifest(*entries.toTypedArray()))
            val verified = assertIs<ArtifactIdentityResolution.Verified>(
                resolver.resolve(
                    model(
                        path = modelRoot.toString(),
                        size = 0L,
                        filename = DIFFUSERS_BUNDLE_DB_FILENAME,
                    ),
                    emptyList(),
                ),
            )

            assertIs<VerifiedArtifactLoadTarget.Directory>(verified.artifact.loadTarget)
            assertEquals(4, verified.artifact.components.size)

            write(modelRoot / "unet/diffusion_pytorch_model.safetensors", "vnet".encodeToByteArray())
            assertFalse(resolver.revalidate(verified.artifact))
        }
    }

    @Test
    fun completeDirectoryManifestRejectsDifferentContainedRoomDirectory() = runTest {
        withRoot { storage, root ->
            val modelRoot = root / "owner/model"
            val files = listOf(
                Triple("model", "unet/diffusion_pytorch_model.safetensors", "unet"),
                Triple("diffusers-vae", "vae/diffusion_pytorch_model.safetensors", "vae"),
                Triple("diffusers-clip-l", "text_encoder/model.safetensors", "clip-l"),
                Triple("diffusers-clip-g", "text_encoder_2/model.safetensors", "clip-g"),
            )
            val entries = files.map { (role, relativePath, contents) ->
                val bytes = contents.encodeToByteArray()
                write(modelRoot / relativePath, bytes)
                manifestEntry(role, "owner/model", "a".repeat(40), relativePath, bytes)
            }
            val differentContainedDirectory = modelRoot / "stale-row-target"
            FileSystem.SYSTEM.createDirectories(differentContainedDirectory)

            val rejected = assertIs<ArtifactIdentityResolution.Rejected>(
                resolver(storage, manifest(*entries.toTypedArray())).resolve(
                    model(
                        path = differentContainedDirectory.toString(),
                        size = 0L,
                        filename = DIFFUSERS_BUNDLE_DB_FILENAME,
                    ),
                    emptyList(),
                ),
            )

            assertEquals(ArtifactIdentityRejection.INCOMPLETE_DIRECTORY, rejected.reason)
        }
    }

    @Test
    fun completeDirectoryManifestRejectsNativeComponentOutsideItsRootRelativeLocation() = runTest {
        withRoot { storage, root ->
            val modelRoot = root / "owner/model"
            val files = listOf(
                Triple("model", "unet/diffusion_pytorch_model.safetensors", "unet"),
                Triple("diffusers-vae", "vae/diffusion_pytorch_model.safetensors", "vae"),
                Triple("diffusers-clip-l", "text_encoder/model.safetensors", "clip-l"),
                Triple("diffusers-clip-g", "text_encoder_2/model.safetensors", "clip-g"),
            )
            val entries = files.map { (role, relativePath, contents) ->
                val bytes = contents.encodeToByteArray()
                write(modelRoot / relativePath, bytes)
                manifestEntry(role, "owner/model", "a".repeat(40), relativePath, bytes)
            }
            val misplacedVae = write(
                modelRoot / "alternate/vae/diffusion_pytorch_model.safetensors",
                "vae".encodeToByteArray(),
            )

            val rejected = assertIs<ArtifactIdentityResolution.Rejected>(
                resolver(storage, manifest(*entries.toTypedArray())).resolve(
                    model(modelRoot.toString(), 0L, DIFFUSERS_BUNDLE_DB_FILENAME),
                    listOf(
                        component(
                            repo = "owner/model",
                            relative = "vae/diffusion_pytorch_model.safetensors",
                            role = "diffusers-vae",
                            path = misplacedVae,
                            size = 3L,
                        ),
                    ),
                ),
            )

            assertEquals(ArtifactIdentityRejection.INCOMPLETE_DIRECTORY, rejected.reason)
        }
    }

    @Test
    fun legacyDirectoryWithoutCompleteManifestIsRejectedBeforeAnyPrimaryCanBeLoaded() = runTest {
        withRoot { storage, root ->
            val modelRoot = root / "owner/model"
            FileSystem.SYSTEM.createDirectories(modelRoot)
            val auxiliary = write(root / "other/vae/vae.safetensors", "vae".encodeToByteArray())

            val rejected = assertIs<ArtifactIdentityResolution.Rejected>(
                resolver(storage, null).resolve(
                    model(modelRoot.toString(), 0L, DIFFUSERS_BUNDLE_DB_FILENAME),
                    listOf(component("other/vae", "vae.safetensors", "vae", auxiliary, 3L)),
                ),
            )

            assertEquals(ArtifactIdentityRejection.INCOMPLETE_DIRECTORY, rejected.reason)
        }
    }

    @Test
    fun staleSizeOrDigestEvidenceIsRejected() = runTest {
        withRoot { storage, root ->
            val bytes = "real".encodeToByteArray()
            val path = write(root / "owner/model/model.gguf", bytes)
            val staleDigest = manifest(
                manifestEntry("model", "owner/model", "a".repeat(40), "model.gguf", "fake".encodeToByteArray()),
            )
            val staleSize = manifest(
                manifestEntry("model", "owner/model", "a".repeat(40), "model.gguf", bytes + byteArrayOf(0)),
            )

            assertIs<ArtifactIdentityResolution.Rejected>(
                resolver(storage, staleDigest).resolve(model(path, bytes.size.toLong()), emptyList()),
            )
            assertIs<ArtifactIdentityResolution.Rejected>(
                resolver(storage, staleSize).resolve(model(path, bytes.size.toLong()), emptyList()),
            )
        }
    }

    @Test
    fun pathEscapeSymlinkEscapeAndUnreadableFileAreRejected() = runTest {
        withRoot { storage, root ->
            val outside = write(root.parent!! / "outside-${Random.nextInt()}.gguf", byteArrayOf(1))
            val escaped = model(outside, 1)
            assertIs<ArtifactIdentityResolution.Rejected>(resolver(storage, null).resolve(escaped, emptyList()))

            val link = root / "owner/model/link.gguf"
            FileSystem.SYSTEM.createDirectories(link.parent!!)
            FileSystem.SYSTEM.createSymlink(link, outside.toPath())
            assertIs<ArtifactIdentityResolution.Rejected>(
                resolver(storage, null).resolve(model(link.toString(), 1, "link.gguf"), emptyList()),
            )

            storage.unreadable += (root / "owner/model/model.gguf").toString()
            val unreadable = write(root / "owner/model/model.gguf", byteArrayOf(1))
            assertIs<ArtifactIdentityResolution.Rejected>(
                resolver(storage, null).resolve(model(unreadable, 1), emptyList()),
            )
            FileSystem.SYSTEM.delete(outside.toPath(), mustExist = false)
        }
    }

    @Test
    fun duplicateOrMoreThanSixtyFourComponentsAreRejectedBeforeHashing() = runTest {
        withRoot { storage, root ->
            val main = write(root / "owner/model/model.safetensors", byteArrayOf(1))
            val duplicatePath = write(root / "component/repo/dup.safetensors", byteArrayOf(2))
            val duplicate = component("component/repo", "dup.safetensors", "vae", duplicatePath, 1)
            val resolver = resolver(storage, null)

            assertIs<ArtifactIdentityResolution.Rejected>(
                resolver.resolve(model(main, 1, "model.safetensors"), listOf(duplicate, duplicate.copy(id = 2))),
            )
            val tooMany = (0..64).map { index ->
                val path = write(root / "component/repo/$index.safetensors", byteArrayOf(index.toByte()))
                component("component/repo", "$index.safetensors", "role_$index", path, 1, id = index.toLong() + 1)
            }
            assertIs<ArtifactIdentityResolution.Rejected>(
                resolver.resolve(model(main, 1, "model.safetensors"), tooMany),
            )
        }
    }

    @Test
    fun legacySingleFileAndAllowlistedBundleAreContentAddressedAndRootIndependent() = runTest {
        withRoot { storage, firstRoot ->
            val firstMain = write(firstRoot / "owner/model/model.gguf", byteArrayOf(1, 2, 3))
            val single = assertIs<ArtifactIdentityResolution.Verified>(
                resolver(storage, null).resolve(model(firstMain, 3), emptyList()),
            )
            assertIs<RevisionIdentity.LocalContent>(single.artifact.revisionIdentity)

            withRoot { secondStorage, secondRoot ->
                val secondMain = write(secondRoot / "owner/model/model.gguf", byteArrayOf(1, 2, 3))
                val same = assertIs<ArtifactIdentityResolution.Verified>(
                    resolver(secondStorage, null).resolve(model(secondMain, 3), emptyList()),
                )
                assertEquals(single.artifact.identity.revision, same.artifact.identity.revision)

                write(secondRoot / "owner/model/model.gguf", byteArrayOf(1, 2, 4))
                val changed = assertIs<ArtifactIdentityResolution.Verified>(
                    resolver(secondStorage, null).resolve(model(secondMain, 3), emptyList()),
                )
                assertNotEquals(single.artifact.identity.revision, changed.artifact.identity.revision)
            }

            val componentPath = write(firstRoot / "component/repo/vae.safetensors", byteArrayOf(9, 8))
            val bundled = assertIs<ArtifactIdentityResolution.Verified>(
                resolver(storage, null).resolve(
                    model(firstMain, 3),
                    listOf(component("component/repo", "vae.safetensors", "vae", componentPath, 2)),
                ),
            )
            assertEquals(2, bundled.artifact.components.size)
            assertTrue(FileSystem.SYSTEM.exists(firstRoot / "owner/model/${LocalArtifactIdentityResolver.MANIFEST_FILE_NAME}"))
        }
    }

    @Test
    fun cancellationDuringLegacyHashingIsRethrownAndLeavesNoManifestOrPart() = runTest {
        withRoot { storage, root ->
            val main = write(root / "owner/model/model.gguf", byteArrayOf(1, 2, 3))
            val expected = CancellationException("stop")
            val resolver = LocalArtifactIdentityResolver(
                storagePathProvider = storage,
                manifestSource = { null },
                hashingDispatcher = StandardTestDispatcher(testScheduler),
                fileSystem = FileSystem.SYSTEM,
                hashFile = { _, _ -> throw expected },
            )

            try {
                resolver.resolve(model(main, 3), emptyList())
                fail("Expected cancellation")
            } catch (actual: CancellationException) {
                assertTrue(actual === expected)
            }

            val manifest = root / "owner/model/${LocalArtifactIdentityResolver.MANIFEST_FILE_NAME}"
            assertFalse(FileSystem.SYSTEM.exists(manifest))
            assertFalse(FileSystem.SYSTEM.exists("$manifest.part".toPath()))
        }
    }

    @Test
    fun aggregateAboveOnePiBIsRejectedWithoutThrowingOrPersistingSidecar() = runTest {
        withRoot { storage, root ->
            val main = write(root / "owner/model/model.gguf", byteArrayOf(1))
            val auxiliary = write(root / "other/vae/vae.safetensors", byteArrayOf(2))
            storage.snapshots[main] = snapshot(DescriptorLimits.MAX_FILE_BYTES)
            storage.snapshots[auxiliary] = snapshot(1L)
            var hashCalls = 0

            val rejected = assertIs<ArtifactIdentityResolution.Rejected>(
                resolver(storage, null, hashFile = { _, _ ->
                    hashCalls++
                    "a".repeat(64)
                }).resolve(
                    model(main, DescriptorLimits.MAX_FILE_BYTES),
                    listOf(component("other/vae", "vae.safetensors", "vae", auxiliary, 1L)),
                ),
            )

            assertEquals(ArtifactIdentityRejection.ARTIFACT_TOO_LARGE, rejected.reason)
            assertEquals(0, hashCalls)
            assertFalse(FileSystem.SYSTEM.exists(root / "owner/model/${LocalArtifactIdentityResolver.MANIFEST_FILE_NAME}"))
        }
    }

    @Test
    fun aggregateAboveTwoPiBAndOverflowSizedEvidenceAreStableRejections() = runTest {
        withRoot { storage, root ->
            val main = write(root / "owner/model/model.gguf", byteArrayOf(1))
            val first = write(root / "other/one/one.safetensors", byteArrayOf(2))
            val second = write(root / "other/two/two.safetensors", byteArrayOf(3))
            storage.snapshots[main] = snapshot(DescriptorLimits.MAX_FILE_BYTES)
            storage.snapshots[first] = snapshot(DescriptorLimits.MAX_FILE_BYTES)
            storage.snapshots[second] = snapshot(1L)
            val resolver = resolver(storage, null, hashFile = { _, _ -> "b".repeat(64) })

            val overBundle = assertIs<ArtifactIdentityResolution.Rejected>(
                resolver.resolve(
                    model(main, DescriptorLimits.MAX_FILE_BYTES),
                    listOf(
                        component("other/one", "one.safetensors", "one", first, DescriptorLimits.MAX_FILE_BYTES),
                        component("other/two", "two.safetensors", "two", second, 1L, id = 2),
                    ),
                ),
            )
            assertEquals(ArtifactIdentityRejection.ARTIFACT_TOO_LARGE, overBundle.reason)

            storage.snapshots[main] = snapshot(Long.MAX_VALUE)
            val overflow = assertIs<ArtifactIdentityResolution.Rejected>(
                resolver.resolve(model(main, Long.MAX_VALUE), emptyList()),
            )
            assertEquals(ArtifactIdentityRejection.ARTIFACT_TOO_LARGE, overflow.reason)
        }
    }

    private fun TestScope.resolver(
        storage: TestStorage,
        manifest: ArtifactManifest?,
        hashFile: (suspend (String, Long) -> String)? = null,
    ) = LocalArtifactIdentityResolver(
        storagePathProvider = storage,
        manifestSource = { manifest },
        hashingDispatcher = StandardTestDispatcher(testScheduler),
        fileSystem = FileSystem.SYSTEM,
        hashFile = hashFile ?: { path, maxBytes ->
            val source = FileSystem.SYSTEM.source(path.toPath())
            val buffer = Buffer()
            try {
                var total = 0L
                while (total < maxBytes) {
                    val read = source.read(buffer, minOf(8_192L, maxBytes - total))
                    if (read == -1L) break
                    total += read
                }
                check(total == maxBytes)
                buffer.snapshot().sha256().hex()
            } finally {
                source.close()
            }
        },
    )

    private suspend fun withRoot(block: suspend (TestStorage, Path) -> Unit) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "caraml-identity-${Random.nextLong()}"
        FileSystem.SYSTEM.createDirectories(root)
        try {
            block(TestStorage(root), root)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    private fun write(path: Path, bytes: ByteArray): String {
        FileSystem.SYSTEM.createDirectories(path.parent!!)
        FileSystem.SYSTEM.write(path) { write(bytes) }
        return path.toString()
    }

    private fun descriptorIdentity(
        revision: String,
        relativePath: String,
        sizeBytes: Long,
        lfsDigest: String,
    ) = LlmModelDescriptor(
        repositoryId = "owner/model",
        revision = revision,
        file = ModelFileIdentity(
            repositoryId = "owner/model",
            revision = revision,
            path = relativePath,
            sizeBytes = sizeBytes,
            gitOid = null,
            lfsOid = lfsDigest,
            xetHash = null,
            evidence = emptyList(),
        ),
        architecture = "llama",
        quantization = QuantizationEvidence.Known("Q4_K_M"),
        parameterCount = 7_000_000_000L,
        contextLimit = 16_384,
        transformerShape = TransformerShape(32, 8, 32, 4_096, 128),
        ggufVersion = 3,
        requiredEngineFeatures = emptyList(),
        evidence = emptyList(),
    )

    private fun diffusionComponent(
        identity: DownloadArtifactIdentity,
        digest: String,
        role: ComponentRole?,
        isPrimary: Boolean = false,
    ) = DiffusionComponentDescriptor(
        file = ModelFileIdentity(
            repositoryId = identity.repositoryId,
            revision = identity.immutableRevision,
            path = identity.relativePath,
            sizeBytes = identity.expectedBytes,
            gitOid = null,
            lfsOid = "sha256:$digest",
            xetHash = null,
            evidence = emptyList(),
        ),
        role = role,
        required = true,
        isPrimary = isPrimary,
    )

    private fun model(path: String, size: Long, filename: String = "model.gguf") = LocalModelEntity(
        modelId = "owner/model",
        filename = filename,
        localPath = path,
        sizeBytes = size,
        downloadedAt = 1,
        author = null,
        libraryName = null,
        pipelineTag = if (filename.endsWith("gguf")) "text-generation" else "text-to-image",
    )

    private fun component(
        repo: String,
        relative: String,
        role: String,
        path: String,
        size: Long,
        id: Long = 1,
    ) = DownloadedComponentEntity(id, repo, relative, role, path, size, 1)

    private fun manifestEntry(
        role: String,
        repo: String,
        revision: String,
        relative: String,
        expectedBytes: ByteArray,
    ): ArtifactManifestEntry {
        val identity = downloadIdentity(repo, revision, relative, expectedBytes.size.toLong())
        return checkNotNull(
            ArtifactManifestEntry.create(
                logicalRole = role,
                identity = identity,
                byteCount = expectedBytes.size.toLong(),
                contentSha256 = expectedBytes.sha256(),
            ),
        )
    }

    private fun manifest(vararg entries: ArtifactManifestEntry) =
        checkNotNull(ArtifactManifest.create(entries.toList()))

    private fun snapshot(bytes: Long) = StoredArtifactSnapshot(
        kind = StoredArtifactKind.REGULAR_FILE,
        byteCount = bytes,
        changeStamp = "stamp:$bytes",
    )

    private fun downloadIdentity(repo: String, revision: String, relative: String, size: Long) =
        checkNotNull(DownloadArtifactIdentity.create(repo, revision, relative, null, size))

    private fun ByteArray.sha256(): String = Buffer().write(this).snapshot().sha256().hex()

    private class TestStorage(private val root: Path) : StoragePathProvider {
        val unreadable = mutableSetOf<String>()
        val snapshots = mutableMapOf<String, StoredArtifactSnapshot>()

        override fun getModelsStorageDirectory(modelId: String): String = (root / modelId).toString()
        override fun getDatabasePath(): String = (root / "db").toString()
        override fun fileExists(path: String): Boolean = FileSystem.SYSTEM.exists(path.toPath())
        override fun getAvailableStorageBytes(): Long = Long.MAX_VALUE
        override fun getTotalStorageBytes(): Long = Long.MAX_VALUE
        override fun isModelFileReadable(path: String): Boolean = inspect(path)?.kind == StoredArtifactKind.REGULAR_FILE
        override fun isDirectoryReadable(path: String): Boolean = inspect(path)?.kind == StoredArtifactKind.DIRECTORY
        override fun getFileSize(path: String): Long = inspect(path)?.byteCount ?: 0
        override fun renameFile(from: String, to: String): Boolean = false
        override fun deleteDownloadedModelContent(modelId: String, localPath: String): Boolean = false

        override fun inspectDownloadedArtifact(modelId: String, localPath: String): StoredArtifactSnapshot? {
            if (localPath in unreadable) return null
            val expectedRoot = (root / modelId).normalized()
            val target = localPath.toPath().normalized()
            if (target != expectedRoot && !target.toString().startsWith("$expectedRoot/")) return null
            return inspect(localPath)
        }

        private fun inspect(path: String): StoredArtifactSnapshot? {
            snapshots[path]?.let { return it }
            val value = path.toPath()
            val metadata = FileSystem.SYSTEM.metadataOrNull(value) ?: return null
            if (metadata.symlinkTarget != null) return null
            val kind = when {
                metadata.isRegularFile -> StoredArtifactKind.REGULAR_FILE
                metadata.isDirectory -> StoredArtifactKind.DIRECTORY
                else -> return null
            }
            return StoredArtifactSnapshot(
                kind = kind,
                byteCount = metadata.size ?: 0,
                changeStamp = "${metadata.lastModifiedAtMillis ?: 0}:${metadata.size ?: 0}",
            )
        }
    }
}
