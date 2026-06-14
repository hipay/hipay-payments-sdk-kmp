#!/usr/bin/env bash
#
# PCI anti-logging gate (story 2.4 AC / architecture enforcement (4)):
# no logging primitive may appear on the card path. Wired into the Gradle
# `check` task — a hit fails the build.
#
# Scanned:
#   - hipayfullservice/src/*/kotlin/com/hipay/card/**   (all source sets)
#   - hipaycard/src/*/kotlin/com/hipay/card/**          (Android Compose card module, story 7.1)
#   - swift/Sources/HiPayCard/**
#   - install(Logging anywhere in core/http (the vault path goes through it)

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Covers Kotlin (println/print(/Log.x), Swift (NSLog/os_log/OSLog/print(/
# debugPrint/dump(), the os.Logger type, and the Ktor Logging plugin.
PATTERN='println|print\(|debugPrint|dump\(|NSLog|os_log|OSLog|Logger|\bLog\.[dewiv]\b|install\(Logging'
FAIL=0

scan() {
  local dir="$1"
  [ -d "$dir" ] || return 0
  # strip line comments before matching; keep file:line for reporting
  local hits
  hits=$(grep -rnE "$PATTERN" "$dir" --include='*.kt' --include='*.swift' 2>/dev/null \
    | grep -vE ':[0-9]+:\s*(//|\*|/\*)' || true)
  if [ -n "$hits" ]; then
    echo "PCI VIOLATION — logging primitive on the card path:"
    echo "$hits"
    FAIL=1
  fi
}

for src_set in "$ROOT"/hipayfullservice/src/*/kotlin/com/hipay/card \
               "$ROOT"/hipaycard/src/*/kotlin/com/hipay/card; do
  scan "$src_set"
done
scan "$ROOT/swift/Sources/HiPayCard"

# Ktor Logging plugin must never be installed on the shared HTTP path
plugin_hits=$(grep -rnE 'install\(Logging' "$ROOT/hipayfullservice/src" 2>/dev/null || true)
if [ -n "$plugin_hits" ]; then
  echo "PCI VIOLATION — Ktor Logging plugin installed:"
  echo "$plugin_hits"
  FAIL=1
fi

if [ "$FAIL" -ne 0 ]; then
  exit 1
fi
echo "OK: no logging primitive on the card path"
