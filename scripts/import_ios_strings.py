#!/usr/bin/env python3
"""Übernimmt ausgewählte Texte der iOS-App (Localizable.xcstrings, 16 Sprachen) in die Android-strings.xml.

Aufruf:  python3 scripts/import_ios_strings.py <Pfad zu Localizable.xcstrings> <Schlüsseldatei>
Die Schlüsseldatei enthält je Zeile eine iOS-Kennung (Kommentare mit #). Bereits vorhandene Android-Kennungen werden
nie überschrieben. Texte, die iPhone/iCloud/Apple erwähnen, werden abgelehnt (Android braucht eigene Formulierungen).
Formatplatzhalter: %lld → %d, %@ → %s (auch positionsweise). Pluralformen werden zu <plurals>.
"""
import json
import re
import sys
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
LOCALES = {
    "en": "values", "de": "values-de", "ar": "values-ar", "es": "values-es", "fr": "values-fr", "it": "values-it",
    "ja": "values-ja", "ko": "values-ko", "nl": "values-nl", "pl": "values-pl", "pt-PT": "values-pt-rPT",
    "ro": "values-ro", "ru": "values-ru", "tr": "values-tr", "uk": "values-uk", "zh-Hans": "values-zh-rCN",
}
CLDR = {
    "values": "one other", "values-de": "one other", "values-es": "one many other", "values-it": "one many other",
    "values-fr": "one many other", "values-nl": "one other", "values-pt-rPT": "one many other", "values-tr": "one other",
    "values-ja": "other", "values-ko": "other", "values-zh-rCN": "other", "values-ar": "zero one two few many other",
    "values-pl": "one few many other", "values-ru": "one few many other", "values-uk": "one few many other",
    "values-ro": "one few other",
}
FORBIDDEN = re.compile(r"iPhone|iPad|iCloud|Apple-|App Store|VoiceOver|PhotoMaid|Arrumo", re.I)


def convert(value: str) -> str:
    v = re.sub(r"%(\d+\$)?lld", lambda m: "%" + (m.group(1) or "") + "d", value)
    v = re.sub(r"%(\d+\$)?ld", lambda m: "%" + (m.group(1) or "") + "d", v)
    v = re.sub(r"%(\d+\$)?@", lambda m: "%" + (m.group(1) or "") + "s", v)
    v = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    v = v.replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    if v.startswith(("@", "?")):
        v = "\\" + v
    # Mehrere unbenannte Platzhalter müssen für aapt positionsweise sein.
    if len(re.findall(r"%[ds]", v)) > 1:
        n = iter(range(1, 100))
        v = re.sub(r"%([ds])", lambda m: f"%{next(n)}${m.group(1)}", v)
    return v


def main() -> None:
    catalog = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))["strings"]
    keys = [l.split("#")[0].strip() for l in Path(sys.argv[2]).read_text().splitlines()]
    keys = [k for k in keys if k]
    existing = set(re.findall(r'name="([^"]+)"', (RES / "values/strings.xml").read_text(encoding="utf-8")))
    blocks: dict[str, list[str]] = {d: [] for d in LOCALES.values()}
    for key in keys:
        if key in existing:
            print(f"vorhanden, übersprungen: {key}")
            continue
        entry = catalog.get(key)
        # Generierte iOS-Pluralformen liegen teils als eigene Kennungen „key#one“, „key#other“ … vor.
        split = {k.split("#", 1)[1]: v for k, v in catalog.items() if k.startswith(key + "#")}
        if entry is None and not split:
            sys.exit(f"fehlt in iOS: {key}")
        for lang, folder in LOCALES.items():
            if entry is None:
                plural = {q: v["localizations"][lang] for q, v in split.items()}
                if any(q not in ("zero", "one", "two", "few", "many", "other") for q in plural):
                    sys.exit(f"{key}: unbekannte Pluralform")
            else:
                loc = entry.get("localizations", {}).get(lang) or sys.exit(f"{key}: keine Übersetzung {lang}")
                plural = loc.get("variations", {}).get("plural")
            texts = {q: plural[q]["stringUnit"]["value"] for q in plural} if plural else {None: loc["stringUnit"]["value"]}
            for t in texts.values():
                if FORBIDDEN.search(t):
                    sys.exit(f"{key} ({lang}) nennt eine iOS-Eigenheit: {t!r}")
            if plural:
                items = "".join(f'\n        <item quantity="{q}">{convert(texts.get(q, texts["other"]))}</item>'
                                for q in CLDR[folder].split())
                blocks[folder].append(f'    <plurals name="{key}">{items}\n    </plurals>')
            else:
                blocks[folder].append(f'    <string name="{key}">{convert(texts[None])}</string>')
    for folder, lines in blocks.items():
        if not lines:
            continue
        path = RES / folder / "strings.xml"
        text = path.read_text(encoding="utf-8")
        marker = "</resources>"
        text = text.replace(marker, "    <!-- Aus der iOS-App übernommen (scripts/import_ios_strings.py) -->\n" + "\n".join(lines) + "\n" + marker)
        path.write_text(text, encoding="utf-8")
    print(f"{len(keys)} Kennungen verarbeitet")


if __name__ == "__main__":
    main()
