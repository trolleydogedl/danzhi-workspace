#!/usr/bin/env python3
"""Deterministic digest of candidate source/config/tests (not reports or build output)."""
from pathlib import Path
import hashlib
ROOT = Path(__file__).resolve().parents[1]
def source_digest(root: Path = ROOT) -> str:
    files = list((root / 'android/app/src').rglob('*'))
    files += [root / p for p in ['android/build.gradle','android/app/build.gradle','android/settings.gradle','android/gradle.properties']]
    files += list((root / 'tests').glob('*.java')) + list((root / 'tests').glob('*.py'))
    result = hashlib.sha256()
    for p in sorted(set(p for p in files if p.is_file())):
        result.update(p.relative_to(root).as_posix().encode('utf-8') + b'\0')
        result.update(hashlib.sha256(p.read_bytes()).digest())
    return result.hexdigest()
if __name__ == '__main__':
    print(source_digest())
