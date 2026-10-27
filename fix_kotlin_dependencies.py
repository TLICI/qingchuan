#!/usr/bin/env python3
"""Align Kotlin runtime dependencies in the Qingchuan Android app.

Usage (PowerShell, from the project root):
    py -3 .\fix_kotlin_dependencies.py

Only app/build.gradle is edited. No network access or credentials are used.
The original bytes are backed up before an atomic replacement.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
from datetime import datetime

COORDINATE = 'org.jetbrains.kotlin:kotlin-bom:1.8.22'
LINE = "    implementation platform('" + COORDINATE + "')"


def find_root(explicit: str | None) -> Path:
    candidates = [Path(explicit).expanduser()] if explicit else [Path.cwd(), Path(__file__).resolve().parent]
    for candidate in candidates:
        if (candidate / 'app' / 'build.gradle').is_file():
            return candidate.resolve()
    raise ValueError('Project not found. Put this script next to build.cmd and run it from that directory.')


def patch(root: Path) -> bool:
    target = root / 'app' / 'build.gradle'
    original = target.read_bytes()
    has_bom = original.startswith(b'\xef\xbb\xbf')
    encoding = 'utf-8-sig' if has_bom else 'utf-8'
    text = original.decode(encoding)

    active = '\n'.join(line for line in text.splitlines() if not line.lstrip().startswith('//'))
    if re.search(r"implementation\s*(?:\(\s*)?platform\s*\(\s*['\"]" + re.escape(COORDINATE) + r"['\"]\s*\)", active):
        print('Already patched: Kotlin BOM 1.8.22 is present.')
        print('Next: run .\\build.cmd')
        return False
    if 'org.jetbrains.kotlin:kotlin-bom:' in active:
        raise ValueError('A different Kotlin BOM is already declared. No changes made; review that version first.')

    matches = list(re.finditer(r'(?m)^dependencies[ \t]*\{[ \t]*(?:\r?\n|$)', text))
    if len(matches) != 1:
        raise ValueError('Expected one top-level dependencies block in app/build.gradle. No changes made.')

    newline = '\r\n' if '\r\n' in text else '\n'
    index = matches[0].end()
    prefix = '' if text[:index].endswith(('\n', '\r')) else newline
    updated = text[:index] + prefix + LINE + newline + text[index:]
    new_bytes = updated.encode(encoding)
    stamp = datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    backup = target.with_name(target.name + '.before-kotlin-fix-' + stamp + '.bak')
    shutil.copy2(target, backup)

    fd, tmp_name = tempfile.mkstemp(prefix=target.name + '.', suffix='.tmp', dir=target.parent)
    temporary = Path(tmp_name)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(new_bytes)
        shutil.copymode(target, temporary)
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)

    print('Patched app/build.gradle: Kotlin BOM 1.8.22 added.')
    print('Backup:', backup)
    print('SDK AAR, application ID, build.py and Wrapper settings were not changed.')
    print('Next: run .\\build.cmd')
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', help='Optional Android project root directory')
    args = parser.parse_args()
    try:
        patch(find_root(args.project))
    except (OSError, UnicodeError, ValueError) as exc:
        print('ERROR:', str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
