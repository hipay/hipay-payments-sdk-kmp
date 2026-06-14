#!/usr/bin/env bash
#
# i18n key-parity gate (story 5.2): every CardEntryStringKey constant (the
# commonMain key authority from story 5.1) must have a value in each locale
# catalog. Today: iOS Localizable.strings for en/fr/it. Story 7.3 adds the
# Android strings.xml source (see the marked extension point below).
#
# Wired into the Gradle `check` task — a missing or unknown key fails the build.
# Reports KEY NAMES only (consistent with the value-free convention).

set -euo pipefail
# Deterministic byte-collation for sort/comm (review P2).
export LC_ALL=C
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

ENUM_FILE="$ROOT/hipayfullservice/src/commonMain/kotlin/com/hipay/card/validation/CardEntryStringKey.kt"
IOS_RES="$ROOT/swift/Sources/HiPayCard/Resources"
ANDROID_RES="$ROOT/hipaycard/src/main/res"
LOCALES=(en fr it)
platforms="iOS"

FAIL=0

# Canonical key set = the enum constants, scoped to the enum body ONLY
# (so the messageKey() mapping below it is not mis-parsed).
keys=$(awk '
  /enum class CardEntryStringKey/ { inblock = 1; next }
  inblock && /^\}/ { inblock = 0 }
  inblock {
    line = $0
    sub(/\/\/.*/, "", line)        # strip line comment
    gsub(/[ \t,]/, "", line)       # strip spaces / tabs / commas
    if (line ~ /^[A-Z][A-Z0-9_]*$/) print line
  }
' "$ENUM_FILE" | sort -u)

if [ -z "$keys" ]; then
  echo "i18n PARITY ERROR — no keys parsed from $ENUM_FILE"
  exit 1
fi
key_count=$(printf '%s\n' "$keys" | wc -l | tr -d ' ')

# Each locale catalog must contain EXACTLY the enum key set.
check_strings() {
  local loc="$1" file="$2"
  if [ ! -f "$file" ]; then
    echo "i18n PARITY ERROR [$loc] — missing catalog: $file"
    FAIL=1
    return
  fi
  local cat_keys missing extra
  cat_keys=$(grep -oE '^[[:space:]]*"[^"]+"[[:space:]]*=' "$file" \
    | grep -oE '"[^"]+"' | tr -d '"' | sort -u || true)
  missing=$(comm -23 <(printf '%s\n' "$keys") <(printf '%s\n' "$cat_keys"))
  extra=$(comm -13 <(printf '%s\n' "$keys") <(printf '%s\n' "$cat_keys"))
  if [ -n "$missing" ]; then
    echo "i18n PARITY ERROR [$loc] — missing keys:"
    printf '  %s\n' $missing
    FAIL=1
  fi
  if [ -n "$extra" ]; then
    echo "i18n PARITY ERROR [$loc] — unknown keys (not in CardEntryStringKey):"
    printf '  %s\n' $extra
    FAIL=1
  fi
  # Values must be non-empty (AC2, review P1): catch  "KEY" = "";
  local empty
  empty=$(grep -oE '^[[:space:]]*"[^"]+"[[:space:]]*=[[:space:]]*""[[:space:]]*;' "$file" \
    | grep -oE '^[[:space:]]*"[^"]+"' | grep -oE '"[^"]+"' | tr -d '"' | sort -u || true)
  if [ -n "$empty" ]; then
    echo "i18n PARITY ERROR [$loc] — empty values for keys:"
    printf '  %s\n' $empty
    FAIL=1
  fi
}

# Each Android catalog (values[-xx]/strings.xml) must contain EXACTLY the enum key set.
# Keys are <string name="KEY">; an empty value is <string name="KEY"></string> or .../>.
android_dir_for() { case "$1" in en) echo "values";; *) echo "values-$1";; esac; }

check_android() {
  local loc="$1" file="$2"
  if [ ! -f "$file" ]; then
    echo "i18n PARITY ERROR [android:$loc] — missing catalog: $file"
    FAIL=1
    return
  fi
  local cat_keys missing extra empty
  cat_keys=$(grep -oE '<string[[:space:]]+name="[^"]+"' "$file" \
    | sed -E 's/.*name="([^"]+)".*/\1/' | sort -u || true)
  missing=$(comm -23 <(printf '%s\n' "$keys") <(printf '%s\n' "$cat_keys"))
  extra=$(comm -13 <(printf '%s\n' "$keys") <(printf '%s\n' "$cat_keys"))
  if [ -n "$missing" ]; then
    echo "i18n PARITY ERROR [android:$loc] — missing keys:"
    printf '  %s\n' $missing
    FAIL=1
  fi
  if [ -n "$extra" ]; then
    echo "i18n PARITY ERROR [android:$loc] — unknown keys (not in CardEntryStringKey):"
    printf '  %s\n' $extra
    FAIL=1
  fi
  empty=$(grep -oE '<string[[:space:]]+name="[^"]+"[[:space:]]*(></string>|/>)' "$file" \
    | sed -E 's/.*name="([^"]+)".*/\1/' | sort -u || true)
  if [ -n "$empty" ]; then
    echo "i18n PARITY ERROR [android:$loc] — empty values for keys:"
    printf '  %s\n' $empty
    FAIL=1
  fi
}

for loc in "${LOCALES[@]}"; do
  check_strings "$loc" "$IOS_RES/$loc.lproj/Localizable.strings"
done

# --- Android catalogs (story 7.3) ----------------------------------------
# Enforced once the Android catalogs exist; parses values[-xx]/strings.xml the
# same way and runs the identical comm against the enum $keys.
if [ -d "$ANDROID_RES" ]; then
  for loc in "${LOCALES[@]}"; do
    check_android "$loc" "$ANDROID_RES/$(android_dir_for "$loc")/strings.xml"
  done
  platforms="iOS+Android"
fi
# -------------------------------------------------------------------------

if [ "$FAIL" -ne 0 ]; then
  exit 1
fi
echo "OK: i18n key parity (${key_count} keys × ${#LOCALES[@]} locales × ${platforms})"
