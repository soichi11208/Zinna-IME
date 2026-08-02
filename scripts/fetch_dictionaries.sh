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

# ncaq/dic-nico-intersection-pixiv — titles appearing in both Niconico Pedia and Pixiv
# Encyclopedia, already in Mozc user-dictionary TSV format (reading / word / part-of-speech).
NICO_PIXIV_URL="https://raw.githubusercontent.com/ncaq/dic-nico-intersection-pixiv/master/public/dic-nico-intersection-pixiv-google.txt"
NICO_PIXIV_FILE="dic-nico-intersection-pixiv.txt"

mkdir -p "${DEST}"

echo "==> Fetching dic-nico-intersection-pixiv"
tmp="$(mktemp)"
trap 'rm -f "${tmp}"' EXIT
curl -fsSL -o "${tmp}" "${NICO_PIXIV_URL}"

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
JA_EN_URL="https://github.com/KEINOS/google-ime-user-dictionary-ja-en/archive/master.zip"
JA_EN_FILE="katakana-english.txt"

echo "==> Fetching google-ime-user-dictionary-ja-en"
work="$(mktemp -d)"
trap 'rm -f "${tmp}"; rm -rf "${work}"' EXIT
curl -fsSL -o "${work}/master.zip" "${JA_EN_URL}"
unzip -q "${work}/master.zip" -d "${work}"

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
JAWIKI_URL="https://raw.githubusercontent.com/utuhiro78/mozcdic-ut-jawiki/main/mozcdic-ut-jawiki.txt.bz2"
JAWIKI_FILE="jawiki.txt"

echo "==> Fetching mozcdic-ut-jawiki"
curl -fL --http1.1 --retry 5 --retry-delay 2 --progress-bar \
  -o "${work}/jawiki.txt.bz2" "${JAWIKI_URL}"

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
