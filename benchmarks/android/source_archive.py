"""Archive nonignored, untracked build sources without collecting local/private outputs."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess


MODULES = {"core-api", "core", "storage", "native-runtime", "model-discovery",
           "plugin-llama", "plugin-diffusion", "plugin-onnx", "nativeEngine"}
SOURCE_SUFFIXES = {".kt", ".kts", ".java", ".c", ".cc", ".cpp", ".h", ".hpp", ".m", ".mm",
                   ".swift", ".def", ".xml", ".proto", ".cmake", ".py", ".sh", ".sql",
                   ".pro", ".keep", ".patch", ".svg", ".png", ".webp", ".ttf", ".otf"}
EXCLUDED_PARTS = {"build", "generated", "results", "__pycache__", "node_modules", "private", "secrets"}
BUILD_INPUTS = {"build.gradle.kts", "settings.gradle.kts", "gradle.properties",
                "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties"}


def is_build_source(relative):
    parts = relative.parts
    if not parts or relative.is_absolute() or ".." in parts:
        return False
    if any(part.startswith(".") or part.lower() in EXCLUDED_PARTS for part in parts):
        return False
    if relative.name in {"local.properties", "key.properties", "keystore.properties", "secrets.properties"}:
        return False
    if relative.as_posix() in BUILD_INPUTS:
        return True
    module_source = (len(parts) >= 3 and parts[0] in MODULES and parts[1] == "src") or (
        len(parts) >= 4 and parts[0] == "app" and parts[1] in {"androidApp", "composeApp", "desktopApp"}
        and parts[2] == "src")
    module_build = (len(parts) == 2 and parts[0] in MODULES) or (
        len(parts) == 3 and parts[0] == "app" and parts[1] in {"androidApp", "composeApp", "desktopApp"})
    if module_build and relative.name in {"build.gradle.kts", "gradle.properties"}:
        return True
    benchmark = (len(parts) >= 3 and parts[0] == "benchmarks" and parts[1] in {"android", "onnx"}) or (
        len(parts) == 2 and parts[0] == "benchmarks" and relative.name in {"run.py", "test_cli.py"})
    native_patch = len(parts) >= 4 and parts[:2] == ("libraries", "patches") and relative.suffix == ".patch"
    return (module_source or benchmark or native_patch) and (
        relative.suffix in SOURCE_SUFFIXES or relative.name in {"CMakeLists.txt", "baseline-prof.txt"})


def archive_untracked_sources(root, output, paths=None, capture_phase="before build"):
    root = Path(root).resolve()
    if paths is None:
        raw = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "-z"], cwd=root)
        paths = [Path(value.decode("utf-8")) for value in raw.split(b"\0") if value]
    manifest = {"schemaVersion": 1, "capturedAtUtc": datetime.now(timezone.utc).isoformat(),
                "scope": f"allowlisted nonignored untracked build sources; captured {capture_phase}",
                "files": [], "excludedCount": 0}
    for relative in sorted(set(map(Path, paths))):
        source = root / relative
        if not is_build_source(relative) or any((root.joinpath(*relative.parts[:index])).is_symlink()
                                               for index in range(1, len(relative.parts) + 1)):
            manifest["excludedCount"] += 1
            continue
        if not source.is_file() or not source.resolve().is_relative_to(root):
            manifest["excludedCount"] += 1
            continue
        before = source.stat()
        content = source.read_bytes()
        after = source.stat()
        if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
            raise RuntimeError(f"Source changed while archiving: {relative}")
        target = output / "untracked-sources" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        manifest["files"].append({"path": relative.as_posix(), "bytes": len(content),
                                  "sha256": hashlib.sha256(content).hexdigest()})
    (output / "untracked-sources-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest
