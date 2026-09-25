#!/bin/sh
set -eu

if [ "$#" -lt 2 ]; then
    echo "usage: llama_source_isolation_test.sh <repo-root> <native-build-command...>" >&2
    exit 2
fi

repo_root=$1
shift
llama_source="$repo_root/libraries/llama.cpp"
case "$(uname -s)" in
    Darwin) desktop_platform=macos ;;
    Linux) desktop_platform=linux ;;
    MINGW*|MSYS*|CYGWIN*) desktop_platform=windows ;;
    *) echo "unsupported desktop host for llama source isolation test" >&2; exit 2 ;;
esac

before_pin=$(git -C "$repo_root" submodule status -- libraries/llama.cpp)
before_worktree=$(git -C "$llama_source" status --porcelain=v1 --untracked-files=all)
if [ -n "$before_worktree" ]; then
    echo "llama source isolation test requires a clean pinned source tree" >&2
    exit 2
fi

"$@"

pinned_commit=$(git -C "$llama_source" rev-parse --short=7 HEAD)
pinned_build_number=$(git -C "$llama_source" rev-list --count HEAD)
build_info="$repo_root/nativeEngine/build/llama-runner-desktop/$desktop_platform/llama-build/common/build-info.cpp"
if ! grep -Fq "int LLAMA_BUILD_NUMBER = $pinned_build_number;" "$build_info" ||
        ! grep -Fq "char const * LLAMA_COMMIT = \"$pinned_commit\";" "$build_info"; then
    echo "isolated native build did not preserve pinned llama build metadata" >&2
    exit 1
fi

after_pin=$(git -C "$repo_root" submodule status -- libraries/llama.cpp)
after_worktree=$(git -C "$llama_source" status --porcelain=v1 --untracked-files=all)
if [ "$before_pin" != "$after_pin" ] || [ "$before_worktree" != "$after_worktree" ]; then
    echo "standard native build mutated the pinned llama source tree" >&2
    exit 1
fi
