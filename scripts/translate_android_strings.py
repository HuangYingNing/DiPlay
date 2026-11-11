#!/usr/bin/env python3
"""Fill missing Android strings through Google Cloud Translation Basic (v2)."""

from __future__ import annotations

import argparse
import html
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo


RES = Path("common/src/main/res")
LOCALE_PATTERN = re.compile(r"values-([a-z]{2})(?:-r([A-Z]{2}))?$")
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[dsf]")
BATCH_SIZE = 32
MAX_BATCH_CHARACTERS = 8_000
DAILY_CHARACTER_LIMIT = 14_000
MONTHLY_FREE_CHARACTER_LIMIT = 450_000
USAGE_FILE = Path("scripts/translation_usage.json")
TRANSLATE_URL = "https://translation.googleapis.com/language/translate/v2"


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
        return {None: "zh-CN", "CN": "zh-CN", "TW": "zh-TW"}.get(region, f"zh-{region}")
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
    body = json.dumps({
        "q": [value for value, _ in protected],
        "source": "en",
        "target": language,
        "format": "text",
    }).encode("utf-8")
    request = urllib.request.Request(
        TRANSLATE_URL,
        data=body,
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "X-Goog-Api-Key": api_key,
        },
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
        except urllib.error.HTTPError as error:
            # Do not print request headers or the API key when reporting API failures.
            detail = error.read().decode("utf-8", errors="replace")[:500]
            if error.code < 500 and error.code != 429:
                raise RuntimeError(f"Google Translation API returned HTTP {error.code}: {detail}") from None
            if attempt == 3:
                raise RuntimeError(f"Google Translation API returned HTTP {error.code}: {detail}") from None
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, KeyError, IndexError, ValueError):
            if attempt == 3:
                raise
        time.sleep(2 ** attempt)
    raise RuntimeError("Unreachable translation retry state")


def load_usage() -> dict[str, int | str]:
    now = datetime.now(ZoneInfo("America/Los_Angeles"))
    month = now.strftime("%Y-%m")
    day = now.strftime("%Y-%m-%d")
    usage: dict[str, int | str] = {"month": month, "characters": 0, "day": day, "daily_characters": 0}
    if USAGE_FILE.exists():
        saved = json.loads(USAGE_FILE.read_text(encoding="utf-8"))
        if saved.get("month") == month:
            usage["characters"] = int(saved.get("characters", 0))
        if saved.get("day") == day:
            usage["daily_characters"] = int(saved.get("daily_characters", 0))
    return usage


def save_usage(usage: dict[str, int | str]) -> None:
    USAGE_FILE.write_text(json.dumps(usage, indent=2) + "\n", encoding="utf-8")


def billable_characters(value: str) -> int:
    # UTF-16 units conservatively count supplementary characters.
    return len(value.encode("utf-16-le")) // 2


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
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "\\'")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="report missing keys without translating")
    args = parser.parse_args()
    english = read_strings(RES / "values")
    folders = sorted(
        path for path in RES.iterdir()
        if path.is_dir() and LOCALE_PATTERN.fullmatch(path.name)
    )
    gaps = {
        folder: {key: english[key] for key in sorted(english.keys() - read_strings(folder).keys())}
        for folder in folders
    }
    gaps = {folder: missing for folder, missing in gaps.items() if missing}
    if args.check:
        for folder, missing in gaps.items():
            print(f"{folder.name}: {len(missing)} missing keys")
        return 1 if gaps else 0
    api_key = os.environ.get("GOOGLE_TRANSLATE_API_KEY", "").strip()
    if not api_key:
        print("GOOGLE_TRANSLATE_API_KEY is required", file=sys.stderr)
        return 2
    usage = load_usage()
    daily_remaining = DAILY_CHARACTER_LIMIT - int(usage["daily_characters"])
    monthly_remaining = MONTHLY_FREE_CHARACTER_LIMIT - int(usage["characters"])
    remaining = min(daily_remaining, monthly_remaining)
    if remaining <= 0:
        print("Google Translation safety limit reached; no request was sent.")
        return 0

    for folder, missing in gaps.items():
        language = target_language(folder)
        batch_names: list[str] = []
        batch_chars = 0
        added = 0
        for name, source in missing.items():
            request_source, _ = protect_placeholders(source)
            size = billable_characters(request_source)
            if size > remaining:
                print(f"{folder.name}: daily or monthly safety limit reached; remaining keys wait for a later run.")
                return 0
            if batch_names and (len(batch_names) >= BATCH_SIZE or batch_chars + size > MAX_BATCH_CHARACTERS or batch_chars + size > remaining):
                if batch_chars > remaining:
                    print(f"{folder.name}: daily or monthly safety limit reached; remaining keys wait for a later run.")
                    return 0
                source_values = [missing[key] for key in batch_names]
                results = translate_batch(source_values, language, api_key)
                append_translations(folder, dict(zip(batch_names, results, strict=True)))
                usage["characters"] = int(usage["characters"]) + batch_chars
                usage["daily_characters"] = int(usage["daily_characters"]) + batch_chars
                save_usage(usage)
                remaining -= batch_chars
                added += len(results)
                batch_names = []
                batch_chars = 0
                if size > remaining:
                    print(f"{folder.name}: daily or monthly safety limit reached; remaining keys wait for a later run.")
                    return 0
            batch_names.append(name)
            batch_chars += size

        if batch_names:
            if batch_chars > remaining:
                print(f"{folder.name}: daily or monthly safety limit reached; remaining keys wait for a later run.")
                return 0
            results = translate_batch([missing[key] for key in batch_names], language, api_key)
            append_translations(folder, dict(zip(batch_names, results, strict=True)))
            usage["characters"] = int(usage["characters"]) + batch_chars
            usage["daily_characters"] = int(usage["daily_characters"]) + batch_chars
            save_usage(usage)
            remaining -= batch_chars
            added += len(results)
        print(f"{folder.name}: added {added} strings")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
