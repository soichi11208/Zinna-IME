#!/usr/bin/env python3
"""Turns the bundled dictionaries into a mozc system-dictionary file.

They used to be imported as a *user* dictionary, which is the wrong mechanism at this size: mozc
gives user-dictionary entries a large cost advantage so a handful of personal words always win, and
six bundled entries were enough to push 日本 out of first place for にほん. In the system dictionary
they compete on cost like every other word.

Reads the same TSVs scripts/fetch_dictionaries.sh produces (reading, surface, part of speech) and
writes mozc's five-column format:

    reading <TAB> lid <TAB> rid <TAB> cost <TAB> surface

Run by scripts/build_mozc.sh, which stages the result into third_party/mozc before bazel runs.
"""

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "third_party/dictionaries"

# 名詞,一般 in data/dictionary_oss/id.def — the id upstream gives its own katakana headwords.
NOUN_ID = 1851

# Upstream's own entries sit around 6000-8000. These go at the high end on purpose: they should be
# reachable, not competitive with core vocabulary. jawiki is weaker still — it carries no frequency
# information at all (every line arrives with the same placeholder cost), and 9000 still let its
# proper nouns take こうしょう from 交渉.
COST = {
    "dic-nico-intersection-pixiv.txt": 8000,
    "katakana-english.txt": 8000,
    "jawiki.txt": 12000,
}


def main() -> int:
    out = []
    seen = set()
    for name, cost in COST.items():
        path = SRC / name
        if not path.is_file():
            print(f"  skipping {name} (not fetched)", file=sys.stderr)
            continue
        n = 0
        with path.open(encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if len(parts) < 2:
                    continue
                reading, surface = parts[0], parts[1]
                if not reading or not surface or (reading, surface) in seen:
                    continue
                seen.add((reading, surface))
                out.append(f"{reading}\t{NOUN_ID}\t{NOUN_ID}\t{cost}\t{surface}")
                n += 1
        print(f"  {name}: {n} entries at cost {cost}", file=sys.stderr)

    sys.stdout.write("\n".join(out) + "\n")
    print(f"  total: {len(out)} entries", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
