#!/usr/bin/env python3
"""Fill missing Android strings through Azure Translator F0's free quota."""

from __future__ import annotations

import argparse
import glob
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
MAX_BATCH_CHARACTERS = 40_000
MONTHLY_FREE_CHARACTER_LIMIT = 1_900_000
USAGE_FILE = Path("scripts/translation_usage.json")


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
    if language == "zh":
        return {None: "zh-Hans", "CN": "zh-Hans", "TW": "zh-Hant"}.get(region, f"zh-{region}")
    return language


def protect_placeholders(value: str) -> tuple[str, list[str]]:
    placeholders: list[str] = []

    def replace(match: re.Match[str]) -> str:
        token = f"ZXQKEEPPLACEHOLDER{len(placeholders)}QXZ"
        placeholders.append(match.group(0))
        return token

    return PLACEHOLDER.sub(replace, value), placeholders


def translate_batch(values: list[str], language: str, api_key: str, region: str) -> list[str]:
    protected = [protect_placeholders(value) for value in values]
    query = "api-version=3.0&from=en&to=" + urllib.parse.quote(language, safe="-")
    url = "https://api.cognitive.microsofttranslator.com/translate?" + query
    body = json.dumps([{"Text": value} for value, _ in protected]).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "Ocp-Apim-Subscription-Key": api_key,
            "Ocp-Apim-Subscription-Region": region,
        },
        method="POST",
    )
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=40) as response:
                result = json.loads(response.read().decode("utf-8"))
            if len(result) != len(values):
                raise ValueError("Azure Translator returned a different number of strings")
            output = []
            for item, (_, placeholders) in zip(result, protected, strict=True):
                translated = item["translations"][0]["text"]
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


def load_monthly_usage() -> dict[str, int | str]:
    month = time.strftime("%Y-%m", time.gmtime())
    if USAGE_FILE.exists():
        usage = json.loads(USAGE_FILE.read_text(encoding="utf-8"))
        if usage.get("month") == month:
            return {"month": month, "characters": int(usage.get("characters", 0))}
    return {"month": month, "characters": 0}


def save_monthly_usage(usage: dict[str, int | str]) -> None:
    USAGE_FILE.write_text(json.dumps(usage, indent=2) + "\n", encoding="utf-8")


def billable_characters(value: str) -> int:
    # UTF-16 units conservatively cover supplementary characters in Azure's count.
    return len(value.encode("utf-16-le")) // 2


def batches(names: list[str], values: dict[str, str], remaining: int):
    names_batch: list[str] = []
    chars = 0
    for name in names:
        protected, _ = protect_placeholders(values[name])
        size = billable_characters(protected)
        if size > remaining:
            raise RuntimeError("Azure F0 monthly translation safety limit reached")
        if names_batch and (len(names_batch) >= BATCH_SIZE or chars + size > MAX_BATCH_CHARACTERS or chars + size > remaining):
            yield names_batch
            names_batch = []
            chars = 0
        names_batch.append(name)
        chars += size
    if names_batch:
        yield names_batch


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
    api_key = os.environ.get("AZURE_TRANSLATOR_KEY", "").strip()
    if not api_key:
        print("AZURE_TRANSLATOR_KEY is required", file=sys.stderr)
        return 2
    region = os.environ.get("AZURE_TRANSLATOR_REGION", "").strip()
    if not region:
        print("AZURE_TRANSLATOR_REGION is required", file=sys.stderr)
        return 2
    usage = load_monthly_usage()
    for folder, missing in gaps.items():
        language = target_language(folder)
        names = list(missing)
        added = 0
        for batch_names in batches(names, missing, MONTHLY_FREE_CHARACTER_LIMIT - int(usage["characters"])):
            source_values = [missing[name] for name in batch_names]
            request_characters = sum(billable_characters(protect_placeholders(value)[0]) for value in source_values)
            if int(usage["characters"]) + request_characters > MONTHLY_FREE_CHARACTER_LIMIT:
                raise RuntimeError("Azure F0 monthly translation safety limit reached")
            results = translate_batch(source_values, language, api_key, region)
            append_translations(folder, dict(zip(batch_names, results, strict=True)))
            usage["characters"] = int(usage["characters"]) + request_characters
            save_monthly_usage(usage)
            added += len(results)
        print(f"{folder.name}: added {added} strings")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
