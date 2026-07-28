#!/usr/bin/env bash
#
# Builds the experimental karukan neural conversion engine for Android.
#
# karukan (https://github.com/togatoga/karukan) is a Rust kana-kanji engine that runs a small GPT-2
# through llama.cpp. This stages libkarukan.so into the :karukan module; the model weights are a
# separate, opt-in step — see scripts/fetch_karukan_model.sh.
#
# Only the ABIs listed in KARUKAN_ABIS are built, and arm64 alone by default: each one compiles the
# whole of llama.cpp, and a phone that is going to run a neural model at all is arm64.
#
# Outputs:
#   third_party/karukan-libs/<abi>/libkarukan.so
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${ROOT}/third_party/karukan"
# Outside the module on purpose: a default build must not pick these up, and
# source-set subtraction in AGP is easy to get wrong. Gradle adds this directory only
# when -Pzinna.karukan.model=true.
OUT="${ROOT}/third_party/karukan-libs"
NDK="${ANDROID_NDK_ROOT:-${ROOT}/third_party/mozc/src/third_party/ndk/android-ndk-r29}"

# The revision this was integrated against. karukan is young and moves fast, so the checkout is
# pinned rather than tracking main.
KARUKAN_REV="${KARUKAN_REV:-7756d68c725ea2c6e611618af79e06b6363275db}"

# rust target -> android abi
declare -A ABI_FOR=(
  [aarch64-linux-android]=arm64-v8a
  [armv7-linux-androideabi]=armeabi-v7a
  [i686-linux-android]=x86
  [x86_64-linux-android]=x86_64
)
# The clang wrapper name differs from the rust triple for 32-bit arm.
declare -A CLANG_FOR=(
  [aarch64-linux-android]=aarch64-linux-android24
  [armv7-linux-androideabi]=armv7a-linux-androideabi24
  [i686-linux-android]=i686-linux-android24
  [x86_64-linux-android]=x86_64-linux-android24
)

KARUKAN_ABIS="${KARUKAN_ABIS:-aarch64-linux-android}"

if [[ ! -d "${NDK}" ]]; then
  echo "error: Android NDK not found at ${NDK}. Run scripts/build_mozc.sh first, or set ANDROID_NDK_ROOT." >&2
  exit 1
fi

if [[ ! -d "${SRC}" ]]; then
  echo "==> Cloning karukan"
  git clone -q https://github.com/togatoga/karukan.git "${SRC}"
fi
git -C "${SRC}" fetch -q --depth 1 origin "${KARUKAN_REV}" 2>/dev/null || true
git -C "${SRC}" checkout -q "${KARUKAN_REV}" 2>/dev/null || {
  echo "warning: could not check out ${KARUKAN_REV}; building whatever is there" >&2
}

# Patches against upstream, applied the same way as the mozc ones: check first so a re-run is a
# no-op rather than an error.
for patch in "${ROOT}"/patches/karukan/*.patch; do
  [[ -e "${patch}" ]] || continue
  if git -C "${SRC}" apply --check "${patch}" 2>/dev/null; then
    echo "==> Applying $(basename "${patch}")"
    git -C "${SRC}" apply "${patch}"
  else
    echo "==> $(basename "${patch}") already applied"
  fi
done

BIN="${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin"
export ANDROID_NDK_ROOT="${NDK}" ANDROID_NDK="${NDK}" NDK_ROOT="${NDK}"

for target in ${KARUKAN_ABIS}; do
  abi="${ABI_FOR[${target}]:-}"
  if [[ -z "${abi}" ]]; then
    echo "error: unknown target ${target}" >&2
    exit 1
  fi
  echo "==> Building libkarukan.so for ${abi}"
  rustup target add "${target}" >/dev/null 2>&1 || true

  clang="${BIN}/${CLANG_FOR[${target}]}-clang"
  # cc-rs and cargo look these up per target, with the triple's dashes turned into underscores.
  upper="$(echo "${target}" | tr 'a-z-' 'A-Z_')"
  under="$(echo "${target}" | tr '-' '_')"
  export "CC_${under}=${clang}"
  export "CXX_${under}=${clang}++"
  export "AR_${under}=${BIN}/llvm-ar"
  export "CARGO_TARGET_${upper}_LINKER=${clang}"

  (cd "${ROOT}/native/karukan-jni" && cargo build --release --target "${target}")

  mkdir -p "${OUT}/${abi}"
  install -m 644 \
    "${ROOT}/native/karukan-jni/target/${target}/release/libkarukan.so" \
    "${OUT}/${abi}/libkarukan.so"
done

echo
echo "Done."
du -sh "${OUT}" 2>/dev/null || true
echo
echo "The engine is built but has no model. To bundle one:"
echo "    scripts/fetch_karukan_model.sh          # downloads the weights"
echo "    ./gradlew :app:assembleDebug -Pzinna.karukan.model=true"
echo "Without that flag the model is left out and the feature stays unavailable at runtime."
