# huggingFaceManager

Kotlin Multiplatform library providing a HuggingFace Hub API client, model search/listing, and platform-specific file download management with progress tracking.

Used by `:composeApp` to discover and download GGUF models and stable-diffusion component files.

---

## Platforms

Android, iOS (arm64, x64, simulator), JVM Desktop

HTTP clients:
- Android → Ktor + OkHttp
- iOS → Ktor + Darwin (NSURLSession)
- JVM → Ktor + CIO

---

## Package Structure

```
com.debanshu777.huggingfacemanager/
├── HuggingFaceApi.kt                  # Public API facade (entry point)
├── api/
│   ├── RemoteHuggingFaceApiService.kt # Ktor HTTP calls to HuggingFace REST API
│   ├── ClientWrapper.kt               # Wraps platform HttpClientFactory
│   ├── SearchModelsParams.kt          # Search query parameters
│   ├── ListModelsParams.kt            # List/browse parameters
│   └── error/Result.kt               # Typed Result<T, E> (Success/Error)
├── model/
│   ├── SearchModelsResponse.kt        # Search results DTO
│   ├── ModelDetailResponse.kt         # Full model metadata DTO
│   ├── ListModelsResponse.kt          # Paginated model list DTO
│   ├── PipelineTag.kt                 # Model task categories enum
│   ├── ModelSort.kt                   # Sort options (trending, likes, downloads)
│   └── ModelFileWeightFilter.kt       # Filter GGUF files by quant type/size
├── repository/
│   └── HuggingFaceRepository.kt      # Aggregates API + download operations
├── usecase/
│   ├── SearchModelsUseCase.kt         # Invoke: search query → List<Model>
│   ├── GetModelDetailUseCase.kt       # Invoke: modelId → ModelDetailResponse
│   └── GetModelFileTreeUseCase.kt     # Invoke: modelId → file list with sizes
├── download/
│   └── DownloadManager.kt (expect)    # Platform-specific download impl
│       DownloadManager.android.kt     # Android DownloadManager API / Ktor streaming
│       DownloadManager.ios.kt         # iOS NSURLSession download task
│       DownloadManager.jvm.kt         # JVM Ktor CIO streaming to file
├── sdcpp/
│   ├── SdCppCuratedCatalog.kt         # Curated list of known SD model bundles
│   ├── SdCppModelSetup.kt             # Install logic for multi-file SD models
│   └── SdCppComponentChecker.kt       # Verify all required component files exist
└── HttpClientFactory.kt (expect)      # Platform HTTP client factory
    HttpClientFactory.android.kt
    HttpClientFactory.ios.kt
    HttpClientFactory.jvm.kt
```

---

## Public API

### HuggingFaceApi

Main entry point. Instantiated once and injected via Koin.

```kotlin
val api = HuggingFaceApi(token = "hf_...")  // token optional for public models

// Search models
val results = api.searchModels(SearchModelsParams(query = "llama", filter = PipelineTag.TEXT_GENERATION))

// Get model detail
val detail = api.getModelDetail("bartowski/Meta-Llama-3.1-8B-Instruct-GGUF")

// Get file tree (GGUF files + sizes)
val files = api.getModelFileTree("bartowski/Meta-Llama-3.1-8B-Instruct-GGUF")

// Download a file
api.downloadFile(
    url = "https://huggingface.co/…/model.gguf",
    destPath = "/path/to/model.gguf",
    onProgress = { dto -> /* DownloadProgressDTO */ }
)
```

### DownloadProgressDTO

```kotlin
data class DownloadProgressDTO(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val progressPercent: Float,    // 0.0–1.0
    val speedBytesPerSec: Long,
    val isComplete: Boolean,
    val error: String?
)
```

---

## Stable Diffusion Support

`SdCppCuratedCatalog` — hardcoded catalog of known working SD model bundles (model + VAE + CLIP + scheduler config). Used in the "Smart Install" flow in `:composeApp`.

`SdCppModelSetup` — orchestrates downloading all required components for a given SD model entry.

`SdCppComponentChecker` — verifies all component files are present and non-corrupt before allowing inference.

---

## Error Handling

All API calls return `Result<T, HuggingFaceError>` (not thrown exceptions):

```kotlin
when (val result = api.searchModels(params)) {
    is Result.Success -> result.data  // List<Model>
    is Result.Error -> result.error   // HuggingFaceError (network, parse, auth)
}
```

---

## Recent Changes

<!-- Updated at end of each Claude Code session -->

- Smart install bundle support for multi-component diffusion models
- `SdCppCuratedCatalog` + `SdCppComponentChecker` added
- `ModelFileWeightFilter` for GGUF quantization variant filtering
