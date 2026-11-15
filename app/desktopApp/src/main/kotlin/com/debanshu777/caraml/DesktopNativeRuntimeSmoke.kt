package com.debanshu777.caraml

import com.debanshu777.caraml.core.download.transfer.ArtifactFsInstalledImageSmoke
import com.debanshu777.caraml.core.platform.PlatformPaths
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionBackendKind
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionRunner
import com.debanshu777.caraml.sdk.plugin.llama.bridge.LlamaRunner
import com.debanshu777.caraml.sdk.plugin.llama.bridge.NativeBackendKind
import com.debanshu777.caraml.sdk.runtime.DesktopNativeLibraries
import java.io.File

/** Runs without model downloads or a GUI, using only the distributable's files. */
object DesktopNativeRuntimeSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1)
        val engines = args.single().split(',').map(String::trim).toSet()
        require(engines.isNotEmpty() && engines.all { it in setOf("llama", "diffusion") })
        val resources = File(System.getProperty("compose.application.resources.dir")).canonicalFile
        check(File(requireNotNull(DesktopNativeLibraries.directory())).canonicalFile == resources)
        check(File(PlatformPaths.getNativeLibDir()).canonicalFile == resources)
        println("Installed native runtime smoke: checking artifact_fs")
        ArtifactFsInstalledImageSmoke.main(emptyArray())
        if ("llama" in engines) {
            println("Installed native runtime smoke: initializing llama")
            val runner = LlamaRunner()
            try {
                runner.initialize(resources.absolutePath)
                check(!runner.engineVersion().isNullOrBlank()) { "Missing llama engine version" }
                check(runner.backendCapabilities().any { it.kind == NativeBackendKind.CPU }) {
                    "The installed llama runtime cannot discover its CPU backend"
                }
            } finally {
                println("Installed native runtime smoke: shutting down llama")
                runner.shutdown()
            }
        }
        if ("diffusion" in engines) {
            println("Installed native runtime smoke: initializing diffusion")
            val runner = DiffusionRunner()
            try {
                runner.initialize(resources.absolutePath)
                check(runner.backendCapabilities().any { it.kind == DiffusionBackendKind.CPU }) {
                    "The installed diffusion runtime cannot discover its CPU backend"
                }
            } finally {
                println("Installed native runtime smoke: releasing diffusion")
                runner.release()
            }
        }
        println("Installed native runtime smoke passed: artifact_fs, ${engines.sorted().joinToString()}")
    }
}
