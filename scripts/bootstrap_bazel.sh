#!/usr/bin/env bash
#
# Builds bazel from source, using no bazel.
#
# mozc is a bazel project, so building it needs bazel, and bazel is normally installed as a
# prebuilt binary. That is fine on a developer's machine and not fine for F-Droid, whose policy is
# that binary dependencies come either from a Debian package or from source. Debian does package
# bazel, but only 7.7.1 — two majors behind what mozc pins, and far enough back that its bzlmod
# cannot even resolve mozc's dependencies. So bazel is built here instead.
#
# The distribution archive, not a git checkout. Bazel publishes it precisely for this, and it ships
# `derived/src/java`: the Java sources generated from bazel's own protos, already generated. A git
# checkout has to generate them, which needs a protoc new enough for edition 2023, a gRPC Java
# plugin that understands it, and a set of googleapis protos that the open-source tree does not
# contain at all — one of bazel's own protos imports a Google-internal file that simply is not
# published. That path is not maintained upstream and does not work.
#
# What the archive contains is source and generated source, plus third-party Java libraries under
# `derived/maven` that come from Maven Central — an origin F-Droid names as allowed. It carries no
# prebuilt bazel.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Pinned, and verified by hash. A build that silently picked up a different bazel would produce a
# different mozc.data, which is the opposite of what a reproducible build is for.
BAZEL_VERSION="9.0.2"
BAZEL_DIST_SHA256="d80b8f708e7a5840fd09b853a4343cace7e4c3504d4e8ac67367d4cb6224436e"
BAZEL_DIST_URL="https://github.com/bazelbuild/bazel/releases/download/${BAZEL_VERSION}/bazel-${BAZEL_VERSION}-dist.zip"

DIST_DIR="${ROOT}/third_party/bazel-dist"
ARCHIVE="${ROOT}/third_party/bazel-${BAZEL_VERSION}-dist.zip"
BAZEL_BIN="${DIST_DIR}/output/bazel"

# Already built. Bootstrapping takes minutes, and every caller wants the binary rather than the
# ritual, so a second run is a no-op.
if [[ -x "${BAZEL_BIN}" ]]; then
  echo "==> bazel already bootstrapped: $("${BAZEL_BIN}" --version 2>/dev/null | head -1)"
  exit 0
fi

command -v javac > /dev/null || {
  echo "error: no JDK on PATH. Install default-jdk-headless." >&2
  exit 1
}

if [[ ! -f "${ARCHIVE}" ]]; then
  echo "==> Fetching bazel ${BAZEL_VERSION} distribution archive"
  mkdir -p "$(dirname "${ARCHIVE}")"
  curl -fL --progress-bar -o "${ARCHIVE}.tmp" "${BAZEL_DIST_URL}"
  mv "${ARCHIVE}.tmp" "${ARCHIVE}"
fi

echo "==> Verifying archive"
echo "${BAZEL_DIST_SHA256}  ${ARCHIVE}" | sha256sum -c -

echo "==> Extracting"
rm -rf "${DIST_DIR}"
mkdir -p "${DIST_DIR}"
unzip -q "${ARCHIVE}" -d "${DIST_DIR}"

# local_jdk rather than the remote one the archive would otherwise download: the point of this
# script is to depend on nothing that arrives as a prebuilt binary, and that includes a JDK.
#
# The progress flags matter more than they look. bazel redraws its status several times a second,
# and with curses off every redraw is a fresh block of lines. Left alone this bootstrap alone
# writes past GitLab's 4 MB log ceiling, after which the rest of the build — including whatever
# actually goes wrong later — is silently dropped.
echo "==> Bootstrapping bazel ${BAZEL_VERSION} (this takes a while)"
cd "${DIST_DIR}"
# -w on top of the progress flags. bazel's own dependencies include grpc, and gcc 14 emits
# -Wmaybe-uninitialized diagnostics for its templates that run to several thousand characters
# each; a few hundred of those overrun a CI log budget on their own. Warnings about third-party
# code nobody here is going to fix are noise either way.
# A ceiling on how much bazel thinks it may use. Left alone it sizes its own parallelism from the
# machine, and gcc compiling grpc's templates wants on the order of a gigabyte per action — enough
# to be killed on a shared runner with four cores and not much more than eight gigabytes. Capping
# the memory it plans around trades wall-clock for finishing at all.
env EXTRA_BAZEL_ARGS="--tool_java_runtime_version=local_jdk --curses=no --show_progress_rate_limit=30 --copt=-w --host_copt=-w --local_resources=memory=HOST_RAM*.5" \
  bash ./compile.sh

# compile.sh exits 0 even when it has produced nothing, so the binary is what gets checked.
if [[ ! -x "${BAZEL_BIN}" ]]; then
  echo "error: bootstrap reported success but produced no binary at ${BAZEL_BIN}" >&2
  exit 1
fi

echo "==> Done: $("${BAZEL_BIN}" --version)"
