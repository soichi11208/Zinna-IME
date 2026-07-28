#!/usr/bin/env bash
#
# Downloads the karukan model weights for optional bundling.
#
# Separate from the build, and not run by it, for two reasons. The weights are tens of megabytes
# and most builds do not want them. And the model repositories on HuggingFace carry no licence
# statement at all, so redistributing the weights inside a published APK is a decision for whoever
# is publishing it — this script only puts them on your own disk.
#
# The files land outside the module. `-Pzinna.karukan.model=true` at build time is what copies them
# into the APK; see karukan/build.gradle.kts.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${ROOT}/third_party/karukan-model"

# jinen-v1-xsmall is the 26M-parameter model, ~30MB at Q5_K_M. The 90M "small" is the better
# converter and roughly three times the size; set KARUKAN_MODEL=small for it.
case "${KARUKAN_MODEL:-xsmall}" in
  xsmall)
    REPO="togatogah/jinen-v1-xsmall.gguf"
    GGUF="jinen-v1-xsmall-Q5_K_M.gguf"
    ;;
  small)
    REPO="togatogah/jinen-v1-small.gguf"
    GGUF="jinen-v1-small-Q5_K_M.gguf"
    ;;
  *)
    echo "error: KARUKAN_MODEL must be xsmall or small" >&2
    exit 1
    ;;
esac

mkdir -p "${DEST}"
for file in "${GGUF}" tokenizer.json; do
  target="${DEST}/${file}"
  if [[ -s "${target}" ]]; then
    echo "==> ${file} already present"
    continue
  fi
  echo "==> Fetching ${file}"
  # --http1.1 and a retry: HuggingFace's CDN drops HTTP/2 streams on large files often enough
  # that a plain curl fails part way through a 30MB download.
  curl -fL --http1.1 --retry 5 --retry-delay 2 --continue-at - \
    --progress-bar -o "${target}.part" \
    "https://huggingface.co/${REPO}/resolve/main/${file}"
  mv "${target}.part" "${target}"
done

# The engine loads these by name, so the variant is recorded rather than guessed at build time.
echo "${GGUF}" > "${DEST}/MODEL"

echo
ls -la "${DEST}"
echo
echo "To bundle these into the APK:"
echo "    ./gradlew :app:assembleDebug -Pzinna.karukan.model=true"
