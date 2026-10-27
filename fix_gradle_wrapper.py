#!/usr/bin/env python3
"""Patch only Qingchuan's redundant Wrapper URL check. No network or key access."""
from __future__ import annotations

import argparse
from datetime import datetime
from pathlib import Path
import sys

OLD = "cmd = [str(gradle), '--no-daemon', 'wrapper', '--gradle-version', GRADLE_VERSION, '--distribution-type', 'bin']"
NEW = "cmd = [str(gradle), '--no-daemon', 'wrapper', '--gradle-version', GRADLE_VERSION, '--distribution-type', 'bin', '--no-validate-url']"


def patch_project(project: Path) -> Path | None:
    target = project.resolve() / 'tools' / 'build.py'
    if not target.is_file():
        raise RuntimeError('tools/build.py not found. Run from the Qingchuan project folder.')
    original = target.read_bytes()
    has_bom = original.startswith(b'\xef\xbb\xbf')
    source = original.decode('utf-8-sig')
    if NEW in source and OLD not in source:
        print('Already patched. Run build.cmd next.')
        return None
    if source.count(OLD) != 1:
        raise RuntimeError('Expected Wrapper command not found exactly once; no files were changed.')
    patched = source.replace(OLD, NEW, 1)
    compile(patched, str(target), 'exec')
    encoded = patched.encode('utf-8')
    if has_bom:
        encoded = b'\xef\xbb\xbf' + encoded
    stamp = datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    backup = target.with_name(f'build.py.before-wrapper-fix-{stamp}.bak')
    with backup.open('xb') as stream:
        stream.write(original)
    try:
        target.write_bytes(encoded)
    except OSError:
        target.write_bytes(original)
        raise
    print('Patched tools/build.py: Wrapper URL probe disabled; checksum logic unchanged.')
    print('Backup:', backup)
    print('Next: run build.cmd from the project folder.')
    return backup


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, default=Path.cwd(), help='Project root (default: current folder)')
    args = parser.parse_args()
    try:
        patch_project(args.project)
        return 0
    except (OSError, UnicodeError, SyntaxError, RuntimeError) as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
