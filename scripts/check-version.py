#!/usr/bin/env python3
"""Проверка версионирования FogMap (см. docs/versioning.md).

Проверяет:
1. Файл `version` — строгий semver MAJOR.MINOR.PATCH (одна ASCII-строка).
2. `CHANGELOG.md` — верхняя секция совпадает с базой из `version`.
3. Подсказка классификации по `git status --porcelain`:
   только docs/openspec/md -> likely NO_BUMP; изменения в app/ -> PATCH минимум.

Код возврата: 0 — ок, 1 — ошибка (невалидный формат или рассинхрон с CHANGELOG).
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SEMVER = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
CHANGELOG_HEAD = re.compile(r"^## \[?(\d+\.\d+\.\d+)\]?")

CODE_HINTS = ("app/", "build.gradle", "gradle.properties", "settings.gradle")


def git_status() -> str:
    try:
        out = subprocess.run(
            ["git", "status", "--porcelain"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=30,
        )
        return out.stdout if out.returncode == 0 else ""
    except Exception:
        return ""


def main() -> int:
    version_file = ROOT / "version"
    try:
        raw = version_file.read_bytes().decode("ascii", errors="strict").strip()
    except (UnicodeDecodeError, OSError) as e:
        print(f"ERROR: файл version не читается как ASCII-строка: {e}", flush=True)
        return 1
    if raw.startswith("\ufeff"):
        print("ERROR: файл version содержит BOM", flush=True)
        return 1
    if "\n" in raw or not SEMVER.match(raw):
        print(f"ERROR: файл version не semver MAJOR.MINOR.PATCH: {raw!r}", flush=True)
        return 1
    print(f"version: {raw}")

    changelog = ROOT / "CHANGELOG.md"
    if not changelog.exists():
        print("ERROR: нет CHANGELOG.md", flush=True)
        return 1
    head = None
    for line in changelog.read_text(encoding="utf-8").splitlines():
        m = CHANGELOG_HEAD.match(line.strip())
        if m:
            head = m.group(1)
            break
    if head is None:
        print("ERROR: в CHANGELOG.md нет секции ## [x.y.z]", flush=True)
        return 1
    # Верхней может быть Unreleased — тогда сверяем первую версионную секцию.
    print(f"changelog head: {head}")
    if head != raw:
        print(
            f"ERROR: рассинхрон: version={raw}, верх CHANGELOG={head}. "
            "При бампе обнови оба файла за раз.",
            flush=True,
        )
        return 1

    status = git_status()
    changed = [ln for ln in status.splitlines() if ln.strip()]
    if not changed:
        print("дерево чистое: решение NO_BUMP (нечего классифицировать).")
        return 0
    code = [ln for ln in changed if ln[3:].startswith(CODE_HINTS)]
    if code:
        print("подсказка: есть изменения в коде (app/...) — минимум PATCH, "
              "при новых фичах MINOR, при ломающих MAJOR. Решение за агентом "
              "по docs/versioning.md.")
    else:
        print("подсказка: только не-кодовые файлы — вероятный NO_BUMP "
              "(проверь, что нет нового поведения).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
