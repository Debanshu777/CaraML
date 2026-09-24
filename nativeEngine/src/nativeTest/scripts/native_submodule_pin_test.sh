#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -- "$script_dir/../../../.." && pwd -P)

expected_llama=f46bc30cb6a7f68a67e34a00061e20a4ad1eff43
expected_diffusion=c92d73c408515c94beef32161bb5960764fde7a0
llama_path="$repo_root/libraries/llama.cpp"
diffusion_path="$repo_root/libraries/stable-diffusion.cpp"

fail() {
    echo "native submodule pin check failed: $1" >&2
    exit 1
}

index_pin() {
    git -C "$repo_root" ls-files --stage -- "$1" | awk 'NF == 4 { print $2 }'
}

check_pin() {
    name=$1
    path=$2
    relative_path=$3
    expected=$4
    expected_remote=$5

    [ -d "$path" ] || fail "$name checkout is missing"
    git -C "$path" cat-file -e "$expected^{commit}" 2>/dev/null ||
        fail "$name candidate commit is unavailable"

    recorded=$(index_pin "$relative_path")
    [ "$recorded" = "$expected" ] ||
        fail "$name gitlink is $recorded, expected $expected"

    checked_out=$(git -C "$path" rev-parse HEAD)
    [ "$checked_out" = "$expected" ] ||
        fail "$name checkout is $checked_out, expected $expected"

    remote=$(git -C "$path" remote get-url origin)
    [ "$remote" = "$expected_remote" ] ||
        fail "$name origin is not the allowlisted public HTTPS remote"

    dirty=$(git -C "$path" status --porcelain=v1 --untracked-files=all)
    [ -z "$dirty" ] || fail "$name checkout is dirty"
}

check_pin \
    "llama.cpp" \
    "$llama_path" \
    "libraries/llama.cpp" \
    "$expected_llama" \
    "https://github.com/ggerganov/llama.cpp.git"

check_pin \
    "stable-diffusion.cpp" \
    "$diffusion_path" \
    "libraries/stable-diffusion.cpp" \
    "$expected_diffusion" \
    "https://github.com/leejet/stable-diffusion.cpp.git"

echo "native submodule pins verified"
