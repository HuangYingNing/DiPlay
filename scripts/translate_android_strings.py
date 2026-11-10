#!/usr/bin/env python3
"""Fill missing Android string translations with Google Cloud Translation Basic (v2)."""

from __future__ import annotations

import argparse
import glob
import html
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path


RES = Path("common/src/main/res")
LOCALE_PATTERN = re.compile(r"values-([a-z]{2})(?:-r([A-Z]{2}))?$")
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[dsf]")
BATCH_SIZE = 64


def read_strings(folder: Path) -> dict[str, str]:
    strings: dict[str, str] = {}
    for path in sorted(folder.glob("*.xml")):
        root = ET.parse(path).getroot()
        for node in root.iter("string"):
            if "name" in node.attrib:
                strings[node.attrib["name"]] = "".join(node.itertext())
    return strings


def target_language(folder: Path) -> str:
    match = LOCALE_PATTERN.fullmatch(folder.name)
    if not match:
        raise ValueError(f"Unsupported locale folder: {folder.name}")
    language, region = match.groups()
    if language == "zh" and region:
        return f"zh-{region}"
    return language


def protect_placeholders(value: str) -> tuple[str, list[str]]:
    placeholders: list[str] = []

    def replace(match: re.Match[str]) -> str:
        token = f"ZXQKEEPPLACEHOLDER{len(placeholders)}QXZ"
        placeholders.append(match.group(0))
        return token

    return PLACEHOLDER.sub(replace, value), placeholders


def translate_batch(values: list[str], language: str, api_key: str) -> list[str]:
    protected = [protect_placeholders(value) for value in values]
    url = "https://translation.googleapis.com/language/translate/v2?" + urllib.parse.urlencode({"key": api_key})
    body = json.dumps({
        "q": [value for value, _ in protected],
        "source": "en",
        "target": language,
        "format": "text",
    }).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=40) as response:
                result = json.loads(response.read().decode("utf-8"))
            translations = result["data"]["translations"]
            if len(translations) != len(values):
                raise ValueError("Google Translation returned a different number of strings")
            output = []
            for item, (_, placeholders) in zip(translations, protected, strict=True):
                translated = html.unescape(item["translatedText"])
                for index, original in enumerate(placeholders):
                    token = f"ZXQKEEPPLACEHOLDER{index}QXZ"
                    if translated.count(token) != 1:
                        raise ValueError("A translated string lost or changed a format placeholder")
                    translated = translated.replace(token, original)
                output.append(translated)
            return output
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, KeyError, IndexError, ValueError):
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)
    raise RuntimeError("Unreachable translation retry state")


def append_translations(folder: Path, missing: dict[str, str]) -> None:
    path = folder / "strings.xml"
    if path.exists():
        content = path.read_text(encoding="utf-8")
    else:
        content = "<resources>\n</resources>\n"
    if "</resources>" not in content:
        raise ValueError(f"Missing resources closing tag in {path}")
    entries = "".join(
        f'    <string name="{name}">{xml_escape(value)}</string>\n'
        for name, value in sorted(missing.items())
    )
    path.write_text(content.replace("</resources>", entries + "</resources>"), encoding="utf-8")


def xml_escape(value: str) -> str:
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="report missing keys without translating")
    args = parser.parse_args()
    english = read_strings(RES / "values")
    folders = sorted(
        path for path in RES.iterdir()
        if path.is_dir() and LOCALE_PATTERN.fullmatch(path.name)
    )
    gaps = {folder: {key: english[key] for key in sorted(english.keys() - read_strings(folder).keys())}
            for folder in folders}
    gaps = {folder: missing for folder, missing in gaps.items() if missing}
    if args.check:
        for folder, missing in gaps.items():
            print(f"{folder.name}: {len(missing)} missing keys")
        return 1 if gaps else 0
    api_key = os.environ.get("GOOGLE_TRANSLATE_API_KEY", "").strip()
    if not api_key:
        print("GOOGLE_TRANSLATE_API_KEY is required", file=sys.stderr)
        return 2
    for folder, missing in gaps.items():
        language = target_language(folder)
        names = list(missing)
        translated: dict[str, str] = {}
        for offset in range(0, len(names), BATCH_SIZE):
            batch_names = names[offset:offset + BATCH_SIZE]
            results = translate_batch([missing[name] for name in batch_names], language, api_key)
            translated.update(zip(batch_names, results, strict=True))
        append_translations(folder, translated)
        print(f"{folder.name}: added {len(translated)} strings")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
