[日本語版 (Japanese)](README.md)

# zinna-IME

An open-source Japanese input method for Android. Conversion is [mozc](https://github.com/google/mozc)
built natively into the app; input is flick, qwerty, or one of each, and layouts and themes are
data the user can replace.

Named after the zinnia `scripts/gen_launcher_icon.py` generates both the vector
and the raster icons from a single description of the flower.

Fully offline.onversion runs entirely against the on-device `libmozc.so` and the bundled
`mozc.data`. `AndroidManifest.xml` declares no `uses-permission` at all, so there is no route to
the network even if a future dependency wanted one.

Versioning: 0.0.1–0.0.9 is open alpha, 0.1.0–0.9.x open beta, 1.0.0 the first stable release.

# Donations
​Donations for ongoing development... actually, no, if you could just buy me a cup of coffee, I'd really appreciate it.
Selfishly enough, I only accept XMR and LTC, but hey, you're all Cypherpunks, so...
​XMR: 83kVmZuxUBzDUnhAiJM8LLDH2sdWiJ6Zi7t7XkF3AK7CQB6ervBetQx2rR7apYHKSySbRFDwF1n7BhpJEu598pau1SJRhyv
LTC: ltcmweb1qqd974nfw2hn2hua5mefltrxe3yeunceqqwd4je76q9pqmw48q0nz2qel738v9anp6rj2mx3yrttwrfjhrsr2p05s6vp98w203u7nk2afucg330wq

## Building

### Requirements

- JDK 17 or later
- Python 3.12 or later (required by mozc's build scripts)
- [bazelisk](https://github.com/bazelbuild/bazelisk) or bazel
- Android SDK (platform 35 / build-tools 35.0.0)

### Steps

```bash
git clone --depth 1 https://github.com/google/mozc.git third_party/mozc

./scripts/build_mozc.sh

python3 scripts/gen_flick_layout.py
python3 scripts/gen_qwerty_layout.py

# Fetch the bundled supplementary dictionaries (build time only; the app never uses the network)
./scripts/fetch_dictionaries.sh

echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
```

`scripts/build_mozc.sh` invokes bazel twice, and the split is not incidental: `mozc.data` is an
architecture-independent blob produced by host tools, and those host tools are marked incompatible
under `--config oss_android`. Asking for both in one command fails during analysis.

## Layout of the repository

```
app/       the input method — InputMethodService, keyboard views, settings
mozc/      wrapper around the native engine — JNI shim, generated protobuf, libmozc.so, mozc.data
patches/   what we change in third_party/mozc, applied at build time
scripts/   native builds, layout and icon generation, dictionary fetching
third_party/mozc/       upstream checkout (gitignored)
```

### The boundary with mozc

Upstream's `android/jni/mozcjni.cc` exports exactly one symbol:

```
Java_com_google_android_apps_inputmethod_libs_mozc_session_MozcJNI_initialize
```

which registers the remaining natives through `RegisterNatives`. That pins the Java class name to
`com.google.android.apps.inputmethod.libs.mozc.session.MozcJNI`. Rather than rename it upstream,
a shim class of that exact name lives here (`mozc/src/main/java/.../MozcJNI.java`). Application
code never touches it; everything goes through `dev.oss.ime.mozc.MozcEngine`, which owns the
lifecycle and the threading rules.

Changes to mozc itself live in `patches/` and are applied by `scripts/build_mozc.sh`, which checks
each one first so a re-run on an already-patched tree is a no-op rather than an error. The checkout
stays otherwise pristine and can be re-pointed at a newer upstream.

| Patch                                      | What it does                                                   |
| ------------------------------------------ | -------------------------------------------------------------- |
| `0001-rule-based-typing-correction`        | Adds a typo-correction model where OSS mozc ships a stub       |
| `0002-android-keystore-profile-encryption` | Encrypts mozc's own profile under an Android Keystore key      |
| `0003-trim-jni-output`                     | Drops the parts of each response that never cross into the app |
| `0004-left-context-particle-ranking`       | Uses the committed text to the left when ranking               |

### Flick input and the contract with mozc

The keyboard does not send kana. mozc's `FLICK_TO_HIRAGANA` table maps ASCII table keysnto
kana (`1`→あ, `_`→い, `*` cycles dakuten and small forms), and that table is what implements the
dakuten rules and the small-kana rules. Sending kana directly would bypass all of it, so the layout
JSON stores *table keys* and lets mozc compose.

Writing that mapping by hand fails in a way that looks like "one flick direction does nothing", so
`scripts/gen_flick_layout.py` generates it from upstream's `data/preedit/*.tsv` and validates the
values.

The table alone is not enough: it decides what a keystroke transliterates to, while
`CompositionMode` decides whether conversion then runs over the result. Leaving a latin plane in
HIRAGANA mode produces kanji candidates for "abc".

| Plane          | Table                                  | CompositionMode |
| -------------- | -------------------------------------- | --------------- |
| kana (flick)   | `FLICK_TO_HIRAGANA` (13)               | `HIRAGANA`      |
| latin (flick)  | `TOGGLE_FLICK_TO_HALFWIDTHASCII` (17)  | `HALF_ASCII`    |
| symbols/digits | `TOGGLE_FLICK_TO_NUMBER` (42)          | `HIRAGANA`      |
| kana (qwerty)  | `QWERTY_MOBILE_TO_HIRAGANA` (20)       | `HIRAGANA`      |
| latin (qwerty) | `QWERTY_MOBILE_TO_HALFWIDTHASCII` (22) | `HALF_ASCII`    |

Repeated presses of the same key repeat the character rather than cycling through it. The 12-key
planes reach every letter and symbol by flick, so mozc's toggling only ever got in the way of
typing ああ or 11; the client ends it with `STOP_KEY_TOGGLING` when the same table key arrives
twice. The dakuten key is exempt, because its は→ば→ぱ cycle *is* that toggling.

### Keyboard styles

The kana and latin halves are chosen independently: flick for both, qwerty for both, or kana on the
flick pad with the alphabet on qwerty. The layouts each point inside their own family, and
`KeyboardStyle` redirects the plane-switch keys according to the setting. Symbol pages are
deliberately never redirected — they are reached from within a family, and sending qwerty's `?123`
to the flick number pad would be a plane switch nobody asked for.

### Bundled dictionaries

[dic-nico-intersection-pixiv](https://github.com/ncaq/dic-nico-intersection-pixiv) (titles appearing
in both Niconico Pedia and Pixiv Encyclopedia) ships as standard, alongside
[google-ime-user-dictionary-ja-en](https://github.com/KEINOS/google-ime-user-dictionary-ja-en)
(katakana loanword to English spelling), so ぶらっく offers *black*.

The second one is derived from EDICT, which is a *translation* dictionary, and that mixes two very
different things: ぶらっく → black is wanted, くろ → black is not, and そ → "5th note in the tonic
solfa representation of the diatonic scale" is certainly not. `scripts/filter_katakana_english.py`
keeps only the transliterations, by a phonetic test — Japanese cannot end a syllable on most
consonants, so writing an English word in kana forces a vowel in after each one, and throwing those
vowels away lines the consonants up (`burakku` → b r k k → `brk`; `black` → b l k → `brk`). A
translation has no such relationship. That drops about 10,000 of 37,000 entries.

Both are fetched at build time by `scripts/fetch_dictionaries.sh` and shipped inside the APK.

They are imported as a user dictionaryather than baked into `mozc.data`. Putting them in the
system dictionary would mean patching mozc's bazel dictionary sources, and replacing a dictionary
then means rebuilding 18 MB of native data instead of swapping an asset. The import runs once in
the background on first launch; `IMPORT_USER_DICTIONARY` handles parsing, replacing the
same-named dictionary, reloading and saving.

### The user's own dictionary

Settings → user dictionary adds, edits and removes words one at a time, with a part of speech
chosen from the 45 that mozc accepts in TSV.

There is no per-word API: `SEND_USER_DICTIONARY_COMMAND` is reserved, and the only way in is
`IMPORT_USER_DICTIONARY`, which replaces a whole dictionary by name. So the authoritative list is
kept locally and pushed again in full after every edit. At the size of a personal dictionary the
rewrite costs nothing, and add, edit and delete all become the same operation — pushing an empty
body deletes the dictionary.

### Typo correction

OSS mozc has none. Upstream leaves the predictor's wiring in place — it calls
`SupplementalModelInterface::CorrectComposition`, looks the corrected reading up and marks the
result `TYPING_CORRECTION` — but keeps the model itself internal, so an OSS build gets a
`SupplementalModelStub` that always returns `nullopt`.

`patches/0001` adds a `TypingCorrectionModel` in its place and touches nothing else. It generates
three kinds of hypothesis:

| Kind                                               | Example    |
| -------------------------------------------------- | ---------- |
| Flick direction slip (wrong vowel on the same key) | ありがとお ありがと |
| Dropped っ                                          | がこ→ がう     |
| Adjacent transposition                             | にほんこ 日本語   |

Distance is measured on the *physical* key geometry, so a slip is ranked by how far the finger
actually was from the intended direction. `scripts/gen_flick_layout.py` emits that geometry
alongside the layouts, which is what keeps the two in step.

Mistaken or missing dakuten, handakuten and small kana are not corrected here.
`kana_modifier_insensitive_conversion` absorbs those, and it costs nothing extra because it widens
an existing dictionary lookup rather than adding one.

The hypothesis budget is six.he caller runs the full unigram / realtime / bigram / number
aggregation for every corrected reading, which measured at roughly 5 ms each on a desktop, so
twelve made prediction six times slower. They are ranked by plausibility rather than by generation
order: first-come ordering starved whichever generator ran last, and one of がう orりがとwodays be missing.

Both `Config.use_typing_correction` and `use_kana_modifier_insensitive_conversion` default to off,
so the client turns them on with `SET_CONFIG`.

### Candidate order

Candidates are shown in four bands, and the order within each band is left exactly as the engine
produced it:

1. exact conversions from mozc
2. exact conversions from the bundled and user dictionaries
3. mozc's predictions
4. the dictionaries' predictions

mozc's mobile prediction otherwise mixes conversions of what was typed with predictions about text
still to come, and ranks them together by cost — so typing でんわ offered 電話番号 above 電話. Which
band a candidate belongs to comes from `all_candidate_words`: `key` is set only when the
candidate's reading differs from the composition, and the `USER_DICTIONARY` attribute marks the
words the user supplied.

### Left context

The text already committed to the left of the cursor is handed to mozc as
`Context.preceding_text`, which it turns into a history segment and ranks against.

Upstream only reconstructs that history for numbers and ASCIIa Japanese word to the left is
discarded, which is the common case on a phone and means most phrases are converted from a standing
start. `patches/0004` extends it to kanji and katakana, and adds a rewriter that promotes a
candidate beginning with a particle when the phrase to the left is noun-like — 東京 followed by
にいきます was converting to に活きます, and 彼 followed by になった to 担った.

It is deliberately conservative: it only promotes a candidate the converter already ranked near the
top, so it settles a close call rather than inventing an answer. Trailing hiragana is not treated as
a noun, because a hiragana run at the end of committed text is usually a particle or an inflection.

Password fields are sent the field type and no text at all.


## Customisation

Layouts and themes are JSON, loaded user directory first, then assetsDropping a file with the
same id into the app's `files/layouts/` overrides the bundled one; deleting it restores the default.

```
files/layouts/flick_kana.json    key placement, flick assignments, actions
files/themes/default_dark.json   colours, key height, corner radius, haptics, key fill
```

### Appearance (per device)

A theme is a look you can hand to someone else; these are choices about this particular device, so
they live in SharedPreferences rather than in a theme file.

- Pure black a `#000000` panel, so an OLED switches those pixels off. The dark palette is used
  even under a light system theme, since light labels on black are what the mode is for.
- Keyboard background image the chosen image is *copied* into the app's own files. The IME
  runs in a process that never held the SAF grant, and the original can be deleted or unmounted.
  Opacity is adjustable, 45% by default.
- Keyboard height a multiplier on the theme's key height rather than an absolute value, so it
  composes with whatever theme is active.
- Flick guide by default a key shows a single bubble with what releasing now would type. The
  full cross of four directions is still available; it teaches the layout at the cost of covering
  the four neighbouring keys.

Keys are drawn without a filly default: the label already says where the key is, so an outline
around each one costs contrast and gets in the way of a background image. Set `flatKeys` to `false`
in a theme for the older filled look.

### The candidate strip when idle

With nothing to convert, the strip offers the clipboard and undo — the two things Gboard puts
there, and the two that are otherwise unreachable without leaving the keyboard.

Clipboard history is held in memory onlyand deliberately so. Everything else this keyboard
remembers is written to disk under a Keystore key, but the clipboard is different in kind: it is
where password managers put passwords, and none of it was typed here on purpose. Clips the source
marked sensitive are not recorded at all.

## Privacy

Everything the keyboard stores about what the user types is encrypted at rest with a key held in
the Android Keystore, where on devices with a secure element it cannot be extracted:

| File                            | Written by                 |
| ------------------------------- | -------------------------- |
| `files/user_dictionary.enc`     | the user dictionary editor |
| `files/mozc/user_dictionary.db` | mozc                       |
| `files/mozc/.history.db`        | mozc's conversion learning |

mozc already encrypts its history, but its Linux `PasswordManager` — which Android would otherwise
inherit, since Android is Linux — writes the key in plain text next to the data it protects.
`patches/0002` replaces that with a key injected from the Java side and sealed by the Keystore, and
extends the same encryption to the user dictionary, which had none.

`android:allowBackup="false"` keeps all of it out of cloud backup.

## Known gaps

- Gboard's second symbol page (`!?#`) is not implemented; that key currently returns to kana.

## Licence

- This project: Apache License 2.0
- `third_party/mozc`: BSD 3-Clause (Google Inc.)
- Bundled `mozc.data`: derived from mozc's OSS dictionary; see upstream
  `data/dictionary_oss/README.txt` for the licences of its constituent parts
- Bundled icons: [Bootstrap Icons](https://icons.getbootstrap.com), MIT

See [NOTICE](NOTICE) for the full attributions, including the terms of the bundled dictionaries.
