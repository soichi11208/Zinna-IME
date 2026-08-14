#!/usr/bin/env bash
#
# Downloads the supplementary dictionaries that get baked into mozc.data.
#
# Fetched at build time and compiled into the system dictionary by scripts/build_mozc.sh; the app
# itself never touches the network. Re-run to pick up a newer snapshot.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Not under assets: these are no longer shipped in the APK. Since the migration to the system
# dictionary they are inputs to scripts/gen_system_dictionary.py, which bakes them into mozc.data
# before the native build.
DEST="${ROOT}/third_party/dictionaries"

# Every source is pinned to a commit and checked against a hash. Tracking a branch would mean the
# dictionary changed under us between builds, so the same tag would produce a different mozc.data —
# which is exactly what a reproducible build must not do, and what F-Droid rebuilds would trip over.
# To move to a newer snapshot, bump the commit and the hash together.

# ncaq/dic-nico-intersection-pixiv — titles appearing in both Niconico Pedia and Pixiv
# Encyclopedia, already in Mozc user-dictionary TSV format (reading / word / part-of-speech).
NICO_PIXIV_COMMIT="db8953c31d30528515d9d1ccf103cd337f4a108d"
NICO_PIXIV_SHA256="f0814b6e2de5302d8c44831066b8511a1a341356d38d31e4508c9e31f711231f"
NICO_PIXIV_URL="https://raw.githubusercontent.com/ncaq/dic-nico-intersection-pixiv/${NICO_PIXIV_COMMIT}/public/dic-nico-intersection-pixiv-google.txt"
NICO_PIXIV_FILE="dic-nico-intersection-pixiv.txt"

mkdir -p "${DEST}"

echo "==> Fetching dic-nico-intersection-pixiv"
tmp="$(mktemp)"
trap 'rm -f "${tmp}"' EXIT
curl -fsSL -o "${tmp}" "${NICO_PIXIV_URL}"
echo "${NICO_PIXIV_SHA256}  ${tmp}" | sha256sum -c - > /dev/null

# A truncated download would silently ship a half dictionary, so sanity-check before installing.
entries="$(grep -cv '^#' "${tmp}" || true)"
if [[ "${entries}" -lt 10000 ]]; then
  echo "error: only ${entries} entries downloaded; refusing to install a truncated dictionary" >&2
  exit 1
fi

install -m 644 "${tmp}" "${DEST}/${NICO_PIXIV_FILE}"
echo "    ${entries} entries -> ${DEST}/${NICO_PIXIV_FILE}"

# KEINOS/google-ime-user-dictionary-ja-en — katakana loanword to English spelling, derived from
# EDICT. Ships as a directory of files split at Google IME's old 10,000-row limit, so they are
# concatenated back into one; mozc has no such limit.
# Cloned at a commit rather than downloaded as an archive: GitHub generates those archives on
# demand and their bytes are not guaranteed stable over time, so a hash pinned to one is a build
# that breaks for no reason later. Git's own content addressing does not have that problem.
JA_EN_COMMIT="7d241dafcf6ee1f9eafefc0ae7a929c095860246"
JA_EN_REPO="https://github.com/KEINOS/google-ime-user-dictionary-ja-en.git"
JA_EN_FILE="katakana-english.txt"

echo "==> Fetching google-ime-user-dictionary-ja-en"
work="$(mktemp -d)"
trap 'rm -f "${tmp}"; rm -rf "${work}"' EXIT
git -C "${work}" init -q ja-en
git -C "${work}/ja-en" remote add origin "${JA_EN_REPO}"
git -C "${work}/ja-en" fetch -q --depth 1 origin "${JA_EN_COMMIT}"
git -C "${work}/ja-en" checkout -q FETCH_HEAD

# The repository also carries .docx files that were saved with a .txt suffix, and other dictionaries
# we do not want. Take only the katakana-English directory, and only lines that actually parse as
# `reading <tab> word <tab> part-of-speech` — that rejects the Word documents wholesale.
#
# Then keep only the entries that are transliterations. The source is EDICT, a translation
# dictionary, so it also answers 黒 with "black" and そ with the definition of the solfa syllable —
# which surfaces as English prose in the middle of ordinary Japanese input. See the filter script.
merged="${work}/merged.txt"
find "${work}" -path "*Google-ime-jp-カタカナ英語辞典*" -name "*.txt" -exec cat {} + 2>/dev/null |
  awk -F"\t" 'NF >= 3 && $1 ~ /^[ぁ-ゖー]+$/ && $2 != "" {print $1 "\t" $2 "\t" $3}' |
  "${PYTHON:-python3}" "${ROOT}/scripts/filter_katakana_english.py" > "${merged}"

ja_en_entries="$(wc -l < "${merged}")"
if [[ "${ja_en_entries}" -lt 10000 ]]; then
  echo "error: only ${ja_en_entries} katakana-English entries; refusing to install" >&2
  exit 1
fi
install -m 644 "${merged}" "${DEST}/${JA_EN_FILE}"
echo "    ${ja_en_entries} entries -> ${DEST}/${JA_EN_FILE}"

# utuhiro78/mozcdic-ut-jawiki — readings and surfaces harvested from the Japanese Wikipedia.
# Distributed in mozc's *system* dictionary format (reading, lid, rid, cost, surface), and the lid,
# rid and cost columns are placeholders that merge-ut-dictionaries fills in later — in the raw file
# every one of them is 0000/0000/8000. So there is no part of speech to carry over; the entries are
# overwhelmingly proper nouns and are imported as such.
JAWIKI_COMMIT="88dddd9d7bc5657861aff3820fd2abc7d5186851"
JAWIKI_SHA256="9b12008554ddc63af8d51f508b944d3f480394714ecf8d5414cbef364e79686e"
JAWIKI_URL="https://raw.githubusercontent.com/utuhiro78/mozcdic-ut-jawiki/${JAWIKI_COMMIT}/mozcdic-ut-jawiki.txt.bz2"
JAWIKI_FILE="jawiki.txt"

echo "==> Fetching mozcdic-ut-jawiki"
curl -fL --http1.1 --retry 5 --retry-delay 2 --progress-bar \
  -o "${work}/jawiki.txt.bz2" "${JAWIKI_URL}"
echo "${JAWIKI_SHA256}  ${work}/jawiki.txt.bz2" | sha256sum -c - > /dev/null

jawiki="${work}/jawiki.tsv"
bunzip2 -c "${work}/jawiki.txt.bz2" |
  awk -F"\t" 'NF >= 5 && $1 != "" && $5 != "" {print $1 "\t" $5 "\t" "名詞"}' |
  sort -u > "${jawiki}"

jawiki_entries="$(wc -l < "${jawiki}")"
if [[ "${jawiki_entries}" -lt 500000 ]]; then
  echo "error: only ${jawiki_entries} jawiki entries; refusing to install a truncated dictionary" >&2
  exit 1
fi
install -m 644 "${jawiki}" "${DEST}/${JAWIKI_FILE}"
echo "    ${jawiki_entries} entries -> ${DEST}/${JAWIKI_FILE}"
