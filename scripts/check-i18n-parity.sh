#!/usr/bin/env bash
#
# i18n key-parity gate (story 5.2): every CardEntryStringKey constant (the
# commonMain key authority from story 5.1) must have a value in each locale
# catalog. Sources: iOS Localizable.strings (en/fr/it), Android strings.xml,
# and the CMP locale-keyed Kotlin catalog in CmpStrings.kt.
#
# Wired into the Gradle `check` task — a missing or unknown key fails the build.
# Reports KEY NAMES only (consistent with the value-free convention).

set -euo pipefail
# Deterministic byte-collation for sort/comm (review P2).
export LC_ALL=C
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

ENUM_FILE="$ROOT/hipayfullservice/src/commonMain/kotlin/com/hipay/card/validation/CardEntryStringKey.kt"
IOS_RES="$ROOT/HiPay_Payments_SDK_iOS/Sources/HiPayCard/Resources"
ANDROID_RES="$ROOT/hipaycard/src/main/res"
CMP_STRINGS="$ROOT/hipaycard-cmp/src/commonMain/kotlin/com/hipay/card/cmp/CmpStrings.kt"
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

# Each CMP catalog (a `cmpStrings<Loc>` map in CmpStrings.kt, one
# `CardEntryStringKey.KEY to "value",` per line) must contain EXACTLY the enum key set.
# Extraction is anchored to entry-syntax lines (leading `CardEntryStringKey.`) so a comment
# mentioning a key inside a map block can never count as a catalog entry.
cmp_var_for() {
  case "$1" in
    en) echo "cmpStringsEn";;
    fr) echo "cmpStringsFr";;
    it) echo "cmpStringsIt";;
    *) echo "";;
  esac
}

check_cmp() {
  local loc="$1" varname="$2"
  if [ -z "$varname" ]; then
    echo "i18n PARITY ERROR [cmp:$loc] — no CMP catalog map registered for this locale (update cmp_var_for)"
    FAIL=1
    return
  fi
  local cat_keys missing extra empty
  cat_keys=$(awk -v v="$varname" '
    index($0, "val " v ":") { inblock = 1; next }
    inblock && /^[[:space:]]*\)[[:space:]]*$/ { inblock = 0 }
    inblock && /^[[:space:]]*CardEntryStringKey\./ {
      if (match($0, /CardEntryStringKey\.[A-Z][A-Z0-9_]*/))
        print substr($0, RSTART + 19, RLENGTH - 19)
    }
  ' "$CMP_STRINGS" | sort -u)
  missing=$(comm -23 <(printf '%s\n' "$keys") <(printf '%s\n' "$cat_keys"))
  extra=$(comm -13 <(printf '%s\n' "$keys") <(printf '%s\n' "$cat_keys"))
  if [ -n "$missing" ]; then
    echo "i18n PARITY ERROR [cmp:$loc] — missing keys:"
    printf '  %s\n' $missing
    FAIL=1
  fi
  if [ -n "$extra" ]; then
    echo "i18n PARITY ERROR [cmp:$loc] — unknown keys (not in CardEntryStringKey):"
    printf '  %s\n' $extra
    FAIL=1
  fi
  # Empty = an entry line whose value is "", tolerating a trailing comma and/or line comment.
  empty=$(awk -v v="$varname" '
    index($0, "val " v ":") { inblock = 1; next }
    inblock && /^[[:space:]]*\)[[:space:]]*$/ { inblock = 0 }
    inblock && /^[[:space:]]*CardEntryStringKey\.[A-Z][A-Z0-9_]*[[:space:]]+to[[:space:]]*"",?[[:space:]]*(\/\/.*)?$/ {
      if (match($0, /CardEntryStringKey\.[A-Z][A-Z0-9_]*/))
        print substr($0, RSTART + 19, RLENGTH - 19)
    }
  ' "$CMP_STRINGS" | sort -u)
  if [ -n "$empty" ]; then
    echo "i18n PARITY ERROR [cmp:$loc] — empty values for keys:"
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

# --- CMP catalog (hipaycard-cmp, locale-keyed Kotlin maps) ----------------
if [ -f "$CMP_STRINGS" ]; then
  for loc in "${LOCALES[@]}"; do
    check_cmp "$loc" "$(cmp_var_for "$loc")"
  done
  platforms="$platforms+CMP"
else
  echo "i18n PARITY ERROR [cmp] — missing catalog source: $CMP_STRINGS"
  FAIL=1
fi
# -------------------------------------------------------------------------

if [ "$FAIL" -ne 0 ]; then
  exit 1
fi
echo "OK: i18n key parity (${key_count} keys × ${#LOCALES[@]} locales × ${platforms})"
