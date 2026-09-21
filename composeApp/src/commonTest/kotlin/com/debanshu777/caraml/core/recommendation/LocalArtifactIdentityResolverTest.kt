package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.huggingfacemanager.download.ArtifactManifest
import com.debanshu777.huggingfacemanager.download.ArtifactManifestEntry
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.huggingfacemanager.download.StoragePathProvider
import com.debanshu777.huggingfacemanager.download.StoredArtifactKind
import com.debanshu777.huggingfacemanager.download.StoredArtifactSnapshot
import com.debanshu777.huggingfacemanager.download.immutableArtifactGenerationRoot
import com.debanshu777.huggingfacemanager.download.immutableArtifactStorageLocation
import com.debanshu777.huggingfacemanager.model.DIFFUSERS_BUNDLE_DB_FILENAME
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalArtifactIdentityResolverTest {
    private val defaultBundleId = "b".repeat(64)

    @Test
    fun gitRemoteIdentityAndStorageRootRoundTripAndRejectMutation() = runTest {
        val remoteObjectId = "a".repeat(40)
        assertExactRemoteIdentityRoundTrip(remoteObjectId) { identity ->
            assertEquals(remoteObjectId, identity.gitOid)
        }
    }

    @Test
    fun lfsRemoteIdentityAndStorageRootRoundTripAndRejectMutation() = runTest {
        val remoteObjectId = "sha256:${"b".repeat(64)}"
        assertExactRemoteIdentityRoundTrip(remoteObjectId) { identity ->
            assertEquals(remoteObjectId, identity.lfsOid)
        }
    }

    @Test
    fun xetRemoteIdentityAndStorageRootRoundTripAndRejectMutation() = runTest {
        val remoteObjectId = "c".repeat(64)
        assertExactRemoteIdentityRoundTrip(remoteObjectId) { identity ->
            assertEquals(remoteObjectId, identity.xetHash)
        }
    }

    @Test
    fun missingHubManifestDoesNotReadOrRewriteLegacySidecar() = runTest {
        withRoot { storage, root ->
            val bytes = "model".encodeToByteArray()
            val path = write(root / "owner/model/model.gguf", bytes)
            val legacySidecar = root / "owner/model/.caraml-local-identity-v1.json"
            val legacyBytes = "legacy-sidecar-must-be-ignored".encodeToByteArray()
            write(legacySidecar, legacyBytes)
            val trackingFileSystem = LegacySidecarReadTrackingFileSystem(FileSystem.SYSTEM, legacySidecar)
            var hashCalls = 0

            val rejected = assertIs<ArtifactIdentityResolution.Rejected>(
                resolver(
                    storage = storage,
                    manifest = null,
                    fileSystem = trackingFileSystem,
                    hashFile = { _, _ ->
                        hashCalls += 1
                        bytes.sha256()
                    },
                ).resolve(model(path, bytes.size.toLong()), emptyList()),
            )

            assertEquals(ArtifactIdentityRejection.STALE_MANIFEST, rejected.reason)
            assertEquals(0, trackingFileSystem.legacySidecarReadCount)
            assertEquals(0, hashCalls)
            assertEquals(
                legacyBytes.toList(),
                FileSystem.SYSTEM.read(legacySidecar) { readByteArray() }.toList(),
            )
        }
    }

    @Test
    fun strictLoadRequestRevalidatesVerifiedArtifactWithoutLegacyResolutionOrSidecar() = runTest {
        withRoot { storage, root ->
            val bytes = "model".encodeToByteArray()
            val digest = bytes.sha256()
            val revision = "a".repeat(40)
            val entry = manifestEntry("model", "owner/model", revision, "model.gguf", bytes)
            val path = write(root / "owner/model" / entry.localRelativePath, bytes)
            val model = model(path, bytes.size.toLong())
            var manifestReads = 0
            val resolver = resolver(
                storage = storage,
                manifest = manifest(
                    entry,
                ),
                onManifestRead = { manifestReads += 1 },
            )
            val artifact = assertIs<ArtifactIdentityResolution.Verified>(
                resolver.resolve(model, emptyList()),
            ).artifact
            val descriptor = descriptorIdentity(revision, "model.gguf", bytes.size.toLong(), digest)
            val plan = task6LlmPlan()
            val planAssessment = task6PlanAssessment(plan = plan)
            val assessment = task6Assessment(
                plans = listOf(planAssessment),
                assessmentKey = "strict-artifact",
            )
            val recommendation = PersonalizedRecommendation(
                assessmentKey = assessment.assessmentKey,
                category = RecommendationCategory.RECOMMENDED,
                selectedPlan = plan,
                reasons = emptyList(),
                profile = RecommendationProfile(),
                selectedPlanAssessment = planAssessment,
            )

            val result = resolver.createLoadRequestFromVerifiedArtifact(
                model,
                descriptor,
                artifact,
                assessment,
                recommendation,
            )

            assertIs<LoadRequestResolution.Ready>(result)
            assertEquals(1, manifestReads)
            assertFalse(FileSystem.SYSTEM.exists(root / "owner/model/.caraml-local-identity-v1.json"))
        }
    }

    @Test
    fun verifiedArtifactRequestRejectsEveryStaleDescriptorIdentityField() = runTest {
        withRoot { storage, root ->
            val bytes = "model".encodeToByteArray()
            val digest = bytes.sha256()
            val revision = "a".repeat(40)
            val manifest = checkNotNull(
                ArtifactManifest.create(
                    listOf(
                        manifestEntry("model", "owner/model", revision, "model.gguf", bytes),
                    ),
                ),
            )
            val path = write(root / "owner/model" / manifest.entries.single().localRelativePath, bytes)
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
            val artifact = assertIs<ArtifactIdentityResolution.Verified>(
                resolver.resolve(installedModel, emptyList()),
            ).artifact

            assertIs<LoadRequestResolution.Ready>(
                resolver.createLoadRequestFromVerifiedArtifact(
                    installedModel,
                    descriptorIdentity(revision, "model.gguf", bytes.size.toLong(), digest),
                    artifact,
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
                    resolver.createLoadRequestFromVerifiedArtifact(
                        installedModel,
                        descriptor,
                        artifact,
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
            val entry = manifestEntry("model", "owner/model", "a".repeat(40), "model.gguf", bytes)
            val path = write(root / "owner/model" / entry.localRelativePath, bytes)
            val manifest = manifest(entry)
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
    fun scopedSingleFileUsesManifestRemotePathInsteadOfFabricatingItFromCatalogFilename() = runTest {
        withRoot { storage, root ->
            val bytes = "nested-model".encodeToByteArray()
            val identity = downloadIdentity(
                repo = "owner/model",
                revision = "a".repeat(40),
                relative = "weights/model.gguf",
                size = bytes.size.toLong(),
            )
            val bundleId = requireNotNull(
                com.debanshu777.huggingfacemanager.download.artifactBundleId(listOf(identity)),
            )
            val location = immutableArtifactStorageLocation(identity, bundleId)
            val localPath = write(root / "owner/model" / location.localRelativePath, bytes)
            val entry = requireNotNull(
                ArtifactManifestEntry.create(
                    logicalRole = "model",
                    identity = identity,
                    byteCount = bytes.size.toLong(),
                    contentSha256 = bytes.sha256(),
                    bundleId = bundleId,
                    localRelativePath = location.localRelativePath,
                    layoutRelativePath = location.layoutRelativePath,
                ),
            )

            val result = resolver(storage, manifest(entry)).resolve(
                    model(localPath, bytes.size.toLong(), filename = "model.gguf"),
                    emptyList(),
                )
            val verified = assertIs<ArtifactIdentityResolution.Verified>(result, result.toString())

            assertEquals("weights/model.gguf", verified.artifact.components.single().repositoryRelativePath)
            assertEquals(localPath, assertIs<VerifiedArtifactLoadTarget.File>(verified.artifact.loadTarget).path)
        }
    }

    @Test
    fun validMultiRepositoryDiffusionBundleKeepsEveryRepositoryCommitAndRole() = runTest {
        withRoot { storage, root ->
            val primary = "primary".encodeToByteArray()
            val vae = "vae".encodeToByteArray()
            val identities = listOf(
                downloadIdentity(
                    "owner/model", "a".repeat(40), "model.safetensors", primary.size.toLong(),
                    "sha256:${primary.sha256()}",
                ),
                downloadIdentity(
                    "other/vae", "b".repeat(40), "vae.safetensors", vae.size.toLong(),
                    "sha256:${vae.sha256()}",
                ),
            )
            val bundle = checkNotNull(com.debanshu777.huggingfacemanager.download.artifactBundleId(identities))
            val primaryLocation = immutableArtifactStorageLocation(identities[0], bundle)
            val vaeLocation = immutableArtifactStorageLocation(identities[1], bundle)
            val primaryPath = write(root / "owner/model" / primaryLocation.localRelativePath, primary)
            val vaePath = write(root / "other/vae" / vaeLocation.localRelativePath, vae)
            val manifest = checkNotNull(
                ArtifactManifest.create(
                    listOf(
                        checkNotNull(
                            ArtifactManifestEntry.create(
                                "model", identities[0], primary.size.toLong(), primary.sha256(), bundle,
                                primaryLocation.localRelativePath, primaryLocation.layoutRelativePath,
                            ),
                        ),
                        checkNotNull(
                            ArtifactManifestEntry.create(
                                "vae", identities[1], vae.size.toLong(), vae.sha256(), bundle,
                                vaeLocation.localRelativePath, vaeLocation.layoutRelativePath,
                            ),
                        ),
                    ),
                ),
            )
            val components = listOf(
                component(
                    "other/vae", "vae.safetensors", "vae", vaePath, vae.size.toLong(),
                    immutableRevision = identities[1].immutableRevision,
                    remoteObjectId = identities[1].remoteObjectId,
                    bundleId = bundle,
                    contentSha256 = vae.sha256(),
                ),
            )

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
    fun catalogComponentWithDifferentExactRevisionCannotBindToManifestPath() = runTest {
        withRoot { storage, root ->
            val primary = "primary".encodeToByteArray()
            val componentBytes = "component".encodeToByteArray()
            val primaryIdentity = downloadIdentity(
                "owner/model",
                "a".repeat(40),
                "model.safetensors",
                primary.size.toLong(),
            )
            val componentIdentity = requireNotNull(
                DownloadArtifactIdentity.create(
                    "shared/repo",
                    "b".repeat(40),
                    "component.safetensors",
                    "sha256:${componentBytes.sha256()}",
                    componentBytes.size.toLong(),
                ),
            )
            val bundle = requireNotNull(
                com.debanshu777.huggingfacemanager.download.artifactBundleId(
                    listOf(primaryIdentity, componentIdentity),
                ),
            )
            val primaryLocation = immutableArtifactStorageLocation(primaryIdentity, bundle)
            val componentLocation = immutableArtifactStorageLocation(componentIdentity, bundle)
            val primaryPath = write(root / "owner/model" / primaryLocation.localRelativePath, primary)
            val componentPath = write(root / "shared/repo" / componentLocation.localRelativePath, componentBytes)
            val exactManifest = manifest(
                requireNotNull(
                    ArtifactManifestEntry.create(
                        "model",
                        primaryIdentity,
                        primary.size.toLong(),
                        primary.sha256(),
                        bundle,
                        primaryLocation.localRelativePath,
                        primaryLocation.layoutRelativePath,
                    ),
                ),
                requireNotNull(
                    ArtifactManifestEntry.create(
                        "vae",
                        componentIdentity,
                        componentBytes.size.toLong(),
                        componentBytes.sha256(),
                        bundle,
                        componentLocation.localRelativePath,
                        componentLocation.layoutRelativePath,
                    ),
                ),
            )
            val staleCatalog = component(
                repo = "shared/repo",
                relative = "component.safetensors",
                role = "vae",
                path = componentPath,
                size = componentBytes.size.toLong(),
                immutableRevision = "c".repeat(40),
                remoteObjectId = componentIdentity.remoteObjectId,
                bundleId = bundle,
                contentSha256 = componentBytes.sha256(),
            )

            val rejected = assertIs<ArtifactIdentityResolution.Rejected>(
                resolver(storage, exactManifest).resolve(
                    model(primaryPath, primary.size.toLong(), filename = "model.safetensors"),
                    listOf(staleCatalog),
                ),
            )

            assertEquals(ArtifactIdentityRejection.STALE_MANIFEST, rejected.reason)
        }
    }

    @Test
    fun loadRequestRejectsDiffusionComponentsWhoseVerifiedRolesWereSwapped() = runTest {
        withRoot { storage, root ->
            val revision = "a".repeat(40)
            val primaryBytes = "primary".encodeToByteArray()
            val vaeBytes = "vae".encodeToByteArray()
            val clipBytes = "clip".encodeToByteArray()
            val primaryIdentity = downloadIdentity(
                "owner/model", revision, "model.safetensors", primaryBytes.size.toLong(),
                "sha256:${primaryBytes.sha256()}",
            )
            val vaeIdentity = downloadIdentity(
                "other/vae", "b".repeat(40), "vae.safetensors", vaeBytes.size.toLong(),
                "sha256:${vaeBytes.sha256()}",
            )
            val clipIdentity = downloadIdentity(
                "other/clip", "c".repeat(40), "clip.safetensors", clipBytes.size.toLong(),
                "sha256:${clipBytes.sha256()}",
            )
            val identities = listOf(primaryIdentity, vaeIdentity, clipIdentity)
            val bundle = requireNotNull(
                com.debanshu777.huggingfacemanager.download.artifactBundleId(identities),
            )
            val primaryLocation = immutableArtifactStorageLocation(primaryIdentity, bundle)
            val vaeLocation = immutableArtifactStorageLocation(vaeIdentity, bundle)
            val clipLocation = immutableArtifactStorageLocation(clipIdentity, bundle)
            val primaryPath = write(root / "owner/model" / primaryLocation.localRelativePath, primaryBytes)
            val vaePath = write(root / "other/vae" / vaeLocation.localRelativePath, vaeBytes)
            val clipPath = write(root / "other/clip" / clipLocation.localRelativePath, clipBytes)
            val manifest = checkNotNull(
                ArtifactManifest.create(
                    listOf(
                        checkNotNull(
                            ArtifactManifestEntry.create(
                                "model", primaryIdentity, primaryBytes.size.toLong(), primaryBytes.sha256(), bundle,
                                primaryLocation.localRelativePath, primaryLocation.layoutRelativePath,
                            ),
                        ),
                        checkNotNull(
                            ArtifactManifestEntry.create(
                                "clip_l", vaeIdentity, vaeBytes.size.toLong(), vaeBytes.sha256(), bundle,
                                vaeLocation.localRelativePath, vaeLocation.layoutRelativePath,
                            ),
                        ),
                        checkNotNull(
                            ArtifactManifestEntry.create(
                                "vae", clipIdentity, clipBytes.size.toLong(), clipBytes.sha256(), bundle,
                                clipLocation.localRelativePath, clipLocation.layoutRelativePath,
                            ),
                        ),
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

            val installedModel = model(primaryPath, primaryBytes.size.toLong(), "model.safetensors")
            val components = listOf(
                component(
                    "other/vae", "vae.safetensors", "clip_l", vaePath, vaeBytes.size.toLong(),
                    immutableRevision = vaeIdentity.immutableRevision,
                    remoteObjectId = vaeIdentity.remoteObjectId,
                    bundleId = bundle,
                    contentSha256 = vaeBytes.sha256(),
                ),
                component(
                    "other/clip", "clip.safetensors", "vae", clipPath, clipBytes.size.toLong(), id = 2,
                    immutableRevision = clipIdentity.immutableRevision,
                    remoteObjectId = clipIdentity.remoteObjectId,
                    bundleId = bundle,
                    contentSha256 = clipBytes.sha256(),
                ),
            )
            val resolver = resolver(storage, manifest)
            val artifact = assertIs<ArtifactIdentityResolution.Verified>(
                resolver.resolve(installedModel, components),
            ).artifact

            val rejected = assertIs<LoadRequestResolution.Rejected>(
                resolver.createLoadRequestFromVerifiedArtifact(
                    installedModel,
                    descriptor,
                    artifact,
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
                manifestEntry(role, "owner/model", "a".repeat(40), relativePath, bytes).also { entry ->
                    write(modelRoot / entry.localRelativePath, bytes)
                }
            }
            val generationRoot = modelRoot / immutableArtifactGenerationRoot(defaultBundleId)
            val resolver = resolver(storage, manifest(*entries.toTypedArray()))
            val verified = assertIs<ArtifactIdentityResolution.Verified>(
                resolver.resolve(
                    model(
                        path = generationRoot.toString(),
                        size = 0L,
                        filename = DIFFUSERS_BUNDLE_DB_FILENAME,
                    ),
                    emptyList(),
                ),
            )

            assertIs<VerifiedArtifactLoadTarget.Directory>(verified.artifact.loadTarget)
            assertEquals(4, verified.artifact.components.size)

            write(modelRoot / entries.first().localRelativePath, "vnet".encodeToByteArray())
            assertFalse(resolver.revalidate(verified.artifact))
        }
    }

    @Test
    fun scopedDirectoryBundleLoadsOnlyFromItsExactGenerationRoot() = runTest {
        withRoot { storage, root ->
            val repositoryRoot = root / "owner/model"
            val bundleId = "b".repeat(64)
            val files = listOf(
                Triple("model", "unet/diffusion_pytorch_model.safetensors", "unet"),
                Triple("diffusers-vae", "vae/diffusion_pytorch_model.safetensors", "vae"),
                Triple("diffusers-clip-l", "text_encoder/model.safetensors", "clip-l"),
                Triple("diffusers-clip-g", "text_encoder_2/model.safetensors", "clip-g"),
            )
            val entries = files.map { (role, relativePath, contents) ->
                val bytes = contents.encodeToByteArray()
                val identity = downloadIdentity(
                    "owner/model",
                    "a".repeat(40),
                    relativePath,
                    bytes.size.toLong(),
                )
                val location = immutableArtifactStorageLocation(identity, bundleId)
                write(repositoryRoot / location.localRelativePath, bytes)
                requireNotNull(
                    ArtifactManifestEntry.create(
                        logicalRole = role,
                        identity = identity,
                        byteCount = bytes.size.toLong(),
                        contentSha256 = bytes.sha256(),
                        bundleId = bundleId,
                        localRelativePath = location.localRelativePath,
                        layoutRelativePath = location.layoutRelativePath,
                    ),
                )
            }
            val generationRoot = repositoryRoot / immutableArtifactGenerationRoot(bundleId)
            val installed = model(
                path = generationRoot.toString(),
                size = 0L,
                filename = DIFFUSERS_BUNDLE_DB_FILENAME,
            )

            val result = resolver(storage, manifest(*entries.toTypedArray())).resolve(installed, emptyList())
            val verified = assertIs<ArtifactIdentityResolution.Verified>(result, result.toString())

            val target = assertIs<VerifiedArtifactLoadTarget.Directory>(verified.artifact.loadTarget)
            assertEquals(generationRoot.toString(), target.path)
            assertEquals(
                files.map { it.second }.sorted(),
                target.nativeConsumedRelativePaths,
            )

            val wrongGeneration = repositoryRoot / immutableArtifactGenerationRoot("c".repeat(64))
            FileSystem.SYSTEM.createDirectories(wrongGeneration)
            val rejected = assertIs<ArtifactIdentityResolution.Rejected>(
                resolver(storage, manifest(*entries.toTypedArray())).resolve(
                    installed.copy(localPath = wrongGeneration.toString()),
                    emptyList(),
                ),
            )
            assertEquals(ArtifactIdentityRejection.INCOMPLETE_DIRECTORY, rejected.reason)
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
                manifestEntry(role, "owner/model", "a".repeat(40), relativePath, bytes).also { entry ->
                    write(modelRoot / entry.localRelativePath, bytes)
                }
            }
            val generationRoot = modelRoot / immutableArtifactGenerationRoot(defaultBundleId)
            val differentContainedDirectory = generationRoot / "stale-row-target"
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
                manifestEntry(role, "owner/model", "a".repeat(40), relativePath, bytes).also { entry ->
                    write(modelRoot / entry.localRelativePath, bytes)
                }
            }
            val generationRoot = modelRoot / immutableArtifactGenerationRoot(defaultBundleId)
            val vaeEntry = entries.single { it.logicalRole == "diffusers-vae" }
            val misplacedVae = write(
                generationRoot / "alternate/vae/diffusion_pytorch_model.safetensors",
                "vae".encodeToByteArray(),
            )

            val rejected = assertIs<ArtifactIdentityResolution.Rejected>(
                resolver(storage, manifest(*entries.toTypedArray())).resolve(
                    model(generationRoot.toString(), 0L, DIFFUSERS_BUNDLE_DB_FILENAME),
                    listOf(
                        component(
                            repo = "owner/model",
                            relative = "vae/diffusion_pytorch_model.safetensors",
                            role = "diffusers-vae",
                            path = misplacedVae,
                            size = 3L,
                            immutableRevision = vaeEntry.identity.immutableRevision,
                            remoteObjectId = vaeEntry.identity.remoteObjectId,
                            bundleId = vaeEntry.bundleId,
                            contentSha256 = vaeEntry.contentSha256,
                        ),
                    ),
                ),
            )

            assertEquals(ArtifactIdentityRejection.STALE_MANIFEST, rejected.reason)
        }
    }

    @Test
    fun directoryWithoutManifestIsRejectedBeforeAnyPrimaryCanBeLoaded() = runTest {
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

            assertEquals(ArtifactIdentityRejection.STALE_MANIFEST, rejected.reason)
        }
    }

    @Test
    fun staleSizeOrDigestEvidenceIsRejected() = runTest {
        withRoot { storage, root ->
            val bytes = "real".encodeToByteArray()
            val staleDigestEntry =
                manifestEntry("model", "owner/model", "a".repeat(40), "model.gguf", "fake".encodeToByteArray())
            val staleDigest = manifest(staleDigestEntry)
            val staleSize = manifest(
                manifestEntry("model", "owner/model", "a".repeat(40), "model.gguf", bytes + byteArrayOf(0)),
            )
            val path = write(root / "owner/model" / staleDigestEntry.localRelativePath, bytes)

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
    fun cancellationDuringManifestHashingIsRethrown() = runTest {
        withRoot { storage, root ->
            val bytes = byteArrayOf(1, 2, 3)
            val entry = manifestEntry("model", "owner/model", "a".repeat(40), "model.gguf", bytes)
            val main = write(root / "owner/model" / entry.localRelativePath, bytes)
            val expected = CancellationException("stop")
            val resolver = LocalArtifactIdentityResolver(
                storagePathProvider = storage,
                manifestSource = {
                    manifest(entry)
                },
                hashingDispatcher = StandardTestDispatcher(testScheduler),
                fileSystem = FileSystem.SYSTEM,
                hashFile = { _, _ -> throw expected },
            )

            val actual = kotlin.test.assertFailsWith<CancellationException> {
                resolver.resolve(model(main, 3), emptyList())
            }

            assertTrue(actual === expected)
        }
    }

    @Test
    fun aggregateArtifactByteBoundRejectsOverflowWithoutArithmeticWraparound() {
        assertEquals(
            DescriptorLimits.MAX_FILE_BYTES,
            checkedResolvedArtifactBytes(listOf(DescriptorLimits.MAX_FILE_BYTES)),
        )
        assertEquals(
            null,
            checkedResolvedArtifactBytes(listOf(DescriptorLimits.MAX_FILE_BYTES, 1L)),
        )
        assertEquals(null, checkedResolvedArtifactBytes(listOf(Long.MAX_VALUE)))
    }

    private fun TestScope.resolver(
        storage: TestStorage,
        manifest: ArtifactManifest?,
        hashFile: (suspend (String, Long) -> String)? = null,
        onManifestRead: () -> Unit = {},
        fileSystem: FileSystem = FileSystem.SYSTEM,
    ) = LocalArtifactIdentityResolver(
        storagePathProvider = storage,
        manifestSource = {
            onManifestRead()
            manifest
        },
        hashingDispatcher = StandardTestDispatcher(testScheduler),
        fileSystem = fileSystem,
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

    private suspend fun TestScope.assertExactRemoteIdentityRoundTrip(
        remoteObjectId: String,
        assertTypedIdentity: (ModelFileIdentity) -> Unit,
    ) {
        withRoot { storage, root ->
            val bytes = "exact-remote-$remoteObjectId".encodeToByteArray()
            val revision = "d".repeat(40)
            val identity = downloadIdentity(
                repo = "owner/model",
                revision = revision,
                relative = "model.gguf",
                size = bytes.size.toLong(),
                remoteObjectId = remoteObjectId,
            )
            val location = immutableArtifactStorageLocation(identity, defaultBundleId)
            val entry = requireNotNull(
                ArtifactManifestEntry.create(
                    logicalRole = "model",
                    identity = identity,
                    byteCount = bytes.size.toLong(),
                    contentSha256 = bytes.sha256(),
                    bundleId = defaultBundleId,
                    localRelativePath = location.localRelativePath,
                    layoutRelativePath = location.layoutRelativePath,
                ),
            )
            val path = write(root / "owner/model" / location.localRelativePath, bytes)
            val resolver = resolver(storage, manifest(entry))
            val artifact = assertIs<ArtifactIdentityResolution.Verified>(
                resolver.resolve(model(path, bytes.size.toLong()), emptyList()),
            ).artifact
            val component = artifact.components.single()

            assertEquals(remoteObjectId, component.remoteObjectId)
            assertEquals((root / "owner/model").normalized().toString(), component.storageRoot)
            assertTypedIdentity(component.identity)
            assertTrue(resolver.revalidate(artifact))

            assertFalse(
                resolver.revalidate(
                    artifact.copy(
                        components = listOf(component.copy(remoteObjectId = "wrong-$remoteObjectId")),
                    ),
                ),
            )
            assertFalse(
                resolver.revalidate(
                    artifact.copy(
                        components = listOf(component.copy(storageRoot = (root / "other/root").toString())),
                    ),
                ),
            )
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
        immutableRevision: String? = null,
        remoteObjectId: String? = null,
        bundleId: String? = null,
        contentSha256: String? = null,
    ) = DownloadedComponentEntity(
        id,
        repo,
        relative,
        role,
        path,
        size,
        1,
        immutableRevision,
        remoteObjectId,
        bundleId,
        contentSha256,
    )

    private fun manifestEntry(
        role: String,
        repo: String,
        revision: String,
        relative: String,
        expectedBytes: ByteArray,
    ): ArtifactManifestEntry {
        val identity = downloadIdentity(
            repo,
            revision,
            relative,
            expectedBytes.size.toLong(),
            "sha256:${expectedBytes.sha256()}",
        )
        val location = immutableArtifactStorageLocation(identity, defaultBundleId)
        return checkNotNull(
            ArtifactManifestEntry.create(
                logicalRole = role,
                identity = identity,
                byteCount = expectedBytes.size.toLong(),
                contentSha256 = expectedBytes.sha256(),
                bundleId = defaultBundleId,
                localRelativePath = location.localRelativePath,
                layoutRelativePath = location.layoutRelativePath,
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

    private fun downloadIdentity(
        repo: String,
        revision: String,
        relative: String,
        size: Long,
        remoteObjectId: String? = "f".repeat(40),
    ) = checkNotNull(DownloadArtifactIdentity.create(repo, revision, relative, remoteObjectId, size))

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

    private class LegacySidecarReadTrackingFileSystem(
        delegate: FileSystem,
        private val legacySidecar: Path,
    ) : ForwardingFileSystem(delegate) {
        var legacySidecarReadCount = 0

        override fun source(file: Path): Source {
            if (file == legacySidecar) legacySidecarReadCount += 1
            return super.source(file)
        }
    }
}
