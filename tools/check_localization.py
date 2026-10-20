#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright © 2026 SirOtter0 and INFINILECT contributors.
"""Check authored UI resources and obvious presentation literals. No network or books.

This bounded source guard complements UI/contract tests and human review. It is
not a Kotlin compiler and cannot prove the provenance of every dynamic string.
"""
from collections import Counter
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "app/src/commonMain/composeResources"
PARAMETER = re.compile(r"%([1-9][0-9]*)\$s")


def read(file):
    text = file.read_text(encoding="utf-8")
    assert "<!DOCTYPE" not in text, file
    return ET.fromstring(text)


def check():
    expected = None
    plural_keys = None
    for locale in ("values", "values-es", "values-ca"):
        entries = list(read(RESOURCES / locale / "strings.xml"))
        values = {e.attrib["name"]: e.text or "" for e in entries}
        assert len(values) == len(entries), f"Duplicate key in {locale}"
        assert all(values.values()), f"Empty text in {locale}"
        if expected is None:
            expected = values
        assert values.keys() == expected.keys(), f"Missing/extra key in {locale}"
        for key in values:
            assert Counter(PARAMETER.findall(values[key])) == Counter(PARAMETER.findall(expected[key])), (locale, key)
        plurals = list(read(RESOURCES / locale / "plurals.xml"))
        names = {e.attrib["name"] for e in plurals}
        assert len(names) == len(plurals)
        if plural_keys is None:
            plural_keys = names
        assert names == plural_keys
        for e in plurals:
            assert {i.attrib["quantity"] for i in e} == {"one", "other"}, (locale, e.attrib)
            for item in e:
                assert Counter(PARAMETER.findall(item.text or "")) == Counter(["1"]), (locale, e.attrib)
    definitions = (ROOT / "app/src/commonMain/kotlin/org/infinilect/app/ui/LocalizedStrings.kt").read_text()
    keys = re.findall(r"^    (\w+)\(Res.string\.(\w+),", definitions, re.MULTILINE)
    assert {name for _, name in keys} == expected.keys()
    assert all(key.lower() == resource for key, resource in keys)

    # Literal glyphs and branding are deliberate; URLs are license/source references.
    decorative = {"INFINILECT", "Aa", "CB", "DOC", "A↔文", "✓", "?"}
    literal = re.compile(r'(?:\bText\(\s*|\b(?:contentDescription|onClickLabel|onLongClickLabel|paneTitle)\s*=\s*)"([^"\n]*)"')
    paths = []
    for source_set in ("commonMain", "androidMain", "desktopMain", "jvmSharedMain"):
        paths += list((ROOT / "app/src" / source_set / "kotlin").rglob("*.kt"))
    paths += list((ROOT / "androidApp/src/main/kotlin").rglob("*.kt"))
    paths += list((ROOT / "desktopApp/src/main/kotlin").rglob("*.kt"))
    findings = []
    for file in sorted(paths):
        for line_no, line in enumerate(file.read_text().splitlines(), 1):
            if line.lstrip().startswith("//"):
                continue
            for match in literal.finditer(line):
                value = match.group(1)
                if value in decorative or value.startswith("github.com/") or "${" in value:
                    continue
                if re.search(r"[A-Za-z]{2}", value):
                    findings.append(f"{file.relative_to(ROOT)}:{line_no}: {value}")
    assert not findings, "Unreviewed presentation literals:\n" + "\n".join(findings)
    print(f"PASS: {len(expected)} strings and {len(plural_keys)} plurals in en/es/ca; {len(paths)} Kotlin files scanned")


if __name__ == "__main__":
    check()
