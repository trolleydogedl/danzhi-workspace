#!/usr/bin/env bash
# Runs host-only tests. This does not build or execute Android code.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="/workspace"
ANDROID="$ROOT/android"
cd "$HERE"
mkdir -p tests/classes reports
CORE_FILES=$(find "$ANDROID/app/src/main/java/cn/fudan/danzhi/core" -name '*.java' | sort)
# shellcheck disable=SC2086
javac --release 8 -Xlint:-options -d tests/classes $CORE_FILES tests/CoreTests.java
java -cp tests/classes CoreTests | tee reports/core-tests.txt
javac -d tests/classes tools/ParseJava.java
java -cp tests/classes ParseJava "$ANDROID/app/src" | tee reports/java-syntax.txt
python3 - <<'PY'
from pathlib import Path
import re
html=Path("/workspace/android/app/src/main/assets/www/index.html").read_text()
Path("tests/ui.js").write_text("\n".join(re.findall(r"<script\b[^>]*>(.*?)</script>",html,re.S|re.I)))
PY
node --check tests/ui.js
printf 'PASS: node --check on scripts extracted from the candidate HTML\n' > reports/js-syntax.txt
python3 tests/test_ui.py | tee reports/ui-tests.txt
printf '\nHost test run complete. Android SDK build / ART / real accounts were NOT exercised.\n'
