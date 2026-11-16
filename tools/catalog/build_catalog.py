#!/usr/bin/env python3
"""
Builds the offline medicine-name catalogs bundled with the app.

Sources (public data):
  * en.json  <- openFDA NDC Directory (U.S. FDA, public domain)
                https://download.open.fda.gov/drug/ndc/drug-ndc-0001-of-0001.json.zip
  * pt.json  <- ANVISA "Dados Abertos - Medicamentos registrados no Brasil"
                https://dados.anvisa.gov.br/dados/DADOS_ABERTOS_MEDICAMENTOS.csv

Output: app/src/main/assets/catalog/{en,pt}.json (sorted JSON arrays of names: brand
names plus active ingredients) and tools/catalog/SOURCES.md.

Usage:  python3 tools/catalog/build_catalog.py        (stdlib only, needs network)
        Set OPENFDA_ZIP / ANVISA_CSV to local copies to skip the downloads.
"""
import csv
import os
import datetime
import io
import json
import re
import sys
import unicodedata
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "app" / "src" / "main" / "assets" / "catalog"
SOURCES = Path(__file__).resolve().parent / "SOURCES.md"

OPENFDA_URL = "https://download.open.fda.gov/drug/ndc/drug-ndc-0001-of-0001.json.zip"
ANVISA_URL = "https://dados.anvisa.gov.br/dados/DADOS_ABERTOS_MEDICAMENTOS.csv"

LOWER_WORDS_PT = {"de", "da", "do", "das", "dos", "e", "com", "em", "para", "a", "o"}
LOWER_WORDS_EN = {"and", "with", "of", "in", "for", "the", "to"}


def fetch(url: str, local_env: str) -> bytes:
    local = os.environ.get(local_env)
    if local:
        return Path(local).read_bytes()
    req = urllib.request.Request(url, headers={"User-Agent": "medwi-catalog-builder"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.read()


def title_case(text: str, lower_words: set) -> str:
    words = text.lower().split()
    out = []
    for i, w in enumerate(words):
        if i > 0 and w in lower_words:
            out.append(w)
        else:
            out.append(w[:1].upper() + w[1:])
    return " ".join(out)


def clean(name: str, lower_words: set) -> str | None:
    name = re.sub(r"\s+", " ", (name or "").strip().strip('"').strip())
    if len(name) < 2 or len(name) > 60:
        return None
    # Keep the source casing when it is mixed (brand names such as "eXtina").
    if name.isupper() or name.islower():
        name = title_case(name, lower_words)
    return name


def fold(name: str) -> str:
    """Comparison key: case- and accent-insensitive."""
    nfkd = unicodedata.normalize("NFKD", name.casefold())
    return "".join(c for c in nfkd if not unicodedata.combining(c))


def dedupe(names) -> list[str]:
    """One entry per folded key, preferring accented and non-shouting spellings."""
    best = {}
    for n in names:
        k = fold(n)
        score = (sum(ord(c) > 127 for c in n), not n.isupper(), sum(c.isupper() for c in n) * -1)
        if k not in best or score > best[k][0]:
            best[k] = (score, n)
    return sorted((v[1] for v in best.values()), key=fold)


def build_en() -> list[str]:
    data = json.loads(zipfile.ZipFile(io.BytesIO(fetch(OPENFDA_URL, "OPENFDA_ZIP"))).read("drug-ndc-0001-of-0001.json"))
    names = set()
    for r in data["results"]:
        for field in ("brand_name", "generic_name"):
            n = clean(r.get(field, ""), LOWER_WORDS_EN)
            if n:
                names.add(n)
    return dedupe(names)


def build_pt() -> list[str]:
    raw = fetch(ANVISA_URL, "ANVISA_CSV")
    text = raw.decode("latin-1") if b"\xef\xbb\xbf" not in raw[:3] else raw.decode("utf-8-sig")
    reader = csv.DictReader(io.StringIO(text), delimiter=";")
    names = set()
    for r in reader:
        if (r.get("SITUACAO_REGISTRO") or "").strip().upper() not in ("VÁLIDO", "VALIDO", "ATIVO"):
            continue
        values = [r.get("NOME_PRODUTO", "")] + (r.get("PRINCIPIO_ATIVO") or "").split(",")
        for v in values:
            n = clean(v, LOWER_WORDS_PT)
            if n:
                names.add(n)
    return dedupe(names)


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    today = datetime.date.today().isoformat()
    counts = {}
    for lang, builder in (("en", build_en), ("pt", build_pt)):
        names = builder()
        if len(names) < 1000:
            print(f"{lang}: only {len(names)} names, refusing to overwrite", file=sys.stderr)
            return 1
        (OUT / f"{lang}.json").write_text(json.dumps(names, ensure_ascii=False, separators=(",", ":")), "utf-8")
        counts[lang] = len(names)
        print(f"{lang}.json: {len(names)} names")
    SOURCES.write_text(
        "# Catalog sources\n\n"
        f"Generated on {today} by `build_catalog.py`.\n\n"
        f"| File | Names | Source | License |\n|---|---|---|---|\n"
        f"| `en.json` | {counts['en']} | [openFDA NDC Directory]({OPENFDA_URL}) | Public domain (U.S. FDA) |\n"
        f"| `pt.json` | {counts['pt']} | [ANVISA Dados Abertos]({ANVISA_URL}) | Brazilian open government data |\n",
        "utf-8",
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
