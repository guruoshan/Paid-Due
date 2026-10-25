#!/usr/bin/env python3
"""Fail-fast localization integrity checks for Android string resources."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
LOCALES = {
    "en": "values",
    "ar": "values-ar",
    "bn": "values-b+bn",
    "hi": "values-hi",
    "ur": "values-ur",
}
MOJIBAKE = re.compile(r"(?:Ã.|Â.|â€|à¤|à¦|Ø[\u0080-\u00ff]|Ù[\u0080-\u00ff])")
errors = []

def read_resources(directory):
    entries = {}
    for path in sorted((RES / directory).glob("*.xml")):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            errors.append(f"{path.relative_to(ROOT)}: invalid XML: {exc}")
            continue
        for node in root:
            if node.tag not in {"string", "plurals", "string-array", "integer"}:
                continue
            name = node.attrib.get("name")
            if not name:
                continue
            key = (node.tag, name)
            if key in entries:
                errors.append(f"{directory}: duplicate resource {node.tag}/{name}")
            entries[key] = (path, " ".join(node.itertext()))
            value = entries[key][1]
            if MOJIBAKE.search(value):
                errors.append(f"{path.relative_to(ROOT)}: possible mojibake in {name}: {value[:80]!r}")
    return entries

resources = {code: read_resources(directory) for code, directory in LOCALES.items()}
english = resources["en"]
for code, entries in resources.items():
    if code == "en":
        continue
    missing = sorted(set(english) - set(entries))
    extra = sorted(set(entries) - set(english))
    if missing:
        errors.append(f"{code}: missing {len(missing)} English resource keys: " +
                      ", ".join(f"{kind}/{name}" for kind, name in missing[:40]))
    if extra:
        print(f"INFO {code}: {len(extra)} locale-only keys (not an error)")

# Scan every production/test source and resource file for UTF-8/legacy-codepage mojibake.
source_roots = [ROOT / "app/src/main", ROOT / "app/src/test", ROOT / "app/src/androidTest"]
for source_root in source_roots:
    if not source_root.exists():
        continue
    for path in source_root.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in {".kt", ".java", ".xml", ".json", ".txt", ".properties"}:
            continue
        try:
            source = path.read_text(encoding="utf-8")
        except UnicodeDecodeError as exc:
            errors.append(f"{path.relative_to(ROOT)}: file is not valid UTF-8: {exc}")
            continue
        for number, line in enumerate(source.splitlines(), 1):
            if MOJIBAKE.search(line):
                errors.append(f"{path.relative_to(ROOT)}:{number}: possible encoding damage: {line[:100]!r}")

# Catch direct, user-visible string literals in Compose UI source. Resource references,
# user-provided dynamic values, test files, and non-UI/domain code are excluded.
literal_patterns = [
    re.compile(r'\bText\s*\(\s*"([^"\\]{2,})"'),
    re.compile(r'contentDescription\s*=\s*"([^"\\]{2,})"'),
    re.compile(r'\b(?:label|placeholder|title)\s*=\s*"([^"\\]{2,})"'),
    re.compile(r'\b(?:Toast\.makeText|SnackbarHostState)\s*\([^\n]*"([^"\\]{2,})"'),
]
ui_root = ROOT / "app/src/main/java"
for path in ui_root.rglob("*.kt"):
    rel = path.relative_to(ROOT).as_posix()
    if "/ui/" not in rel and "/app/ui/" not in rel:
        continue
    source = path.read_text(encoding="utf-8")
    for number, line in enumerate(source.splitlines(), 1):
        for pattern in literal_patterns:
            match = pattern.search(line)
            if match:
                literal = match.group(1).strip()
                if not literal or literal.startswith("$") or re.fullmatch(r"[0-9+.,:% /()-]+", literal):
                    continue
                errors.append(f"{rel}:{number}: possible hardcoded visible text {literal!r}")
                break

if errors:
    print("Localization integrity check FAILED:")
    for error in errors:
        print(f" - {error}")
    print(f"\n{len(errors)} issue(s) detected.")
    sys.exit(1)
print(f"Localization integrity check passed: {len(english)} English keys, five locale resource sets, no mojibake markers or detected hardcoded UI literals.")
