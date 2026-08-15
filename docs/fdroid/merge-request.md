New app: zinna-IME — an offline Japanese input method.

Conversion is mozc, compiled natively into the app. The app declares no permissions at all,
INTERNET included.

Two things in this recipe are unusual and worth explaining up front.

**bazel is built from source.** mozc is a bazel project. Debian ships bazel-bootstrap 7.7.1, two
majors behind what mozc pins, and I verified it cannot resolve mozc's module graph at all — it
fails during bzlmod resolution on googletest and aspect_bazel_lib. Chaining 7.7.1 → 8.6.0 also
fails, because 8.6.0's MODULE.bazel uses a `hub_name` attribute 7.7.1 does not know. So
`scripts/bootstrap_bazel.sh` builds bazel 9.0.2 from its distribution archive, which needs no
bazel — the same path Debian itself uses to package bazel. The archive is pinned by SHA-256.

**The archive ships some generated sources.** bazel's distribution archive contains
`derived/src/java`: Java sources generated from bazel's own protos, shipped already generated.
Generating them instead is not possible from the public tree — one of bazel's protos imports
`devtools/starlark/protolark/proto/protolark.proto`, a Google-internal file that is not published
anywhere. The archive also carries `derived/maven` (third-party libraries from Maven Central) and
`derived/jars` (code bazel generated from protos inside the same archive). None of it is a
prebuilt bazel.

Everything the build downloads is pinned: the bazel archive and two of the three dictionaries by
SHA-256, mozc and the third dictionary by commit. A rebuild of the same tag produces the same
bytes.

The NDK comes from the buildserver via `ANDROID_NDK_HOME`. mozc pins r29, but that is not actually
required — I measured 27.2.12479018 building every ABI cleanly. If that version is unavailable,
the `ndk:` value can change.

Build time is the thing I would expect questions about: bootstrapping bazel and then building
mozc took in the mid-teens of minutes on my machine.
