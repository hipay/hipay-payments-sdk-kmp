#!/usr/bin/env bash
# Every public entry point of a Compose-Multiplatform module must declare its surface before it can
# report anything, otherwise the funnel attributes that event to the native integration.
#
# This is a static check because it cannot be a test. The surface lives in a process-global flag whose
# reader, `cmsIdentity()`, is internal to :hipaycore, and there are no friend paths between modules —
# so from a CMP module's own test source set there is no way to read what a call left behind.
#
# What it protects: a CMP host asks whether Apple Pay is available BEFORE showing the button, and a
# favourable answer raises the funnel's first event. That path did not declare the surface, so `init`
# went out as `sdk_kmp_ios_native` while the rest of the same journey went out as `sdk_kmp_ios_cmp`.
# One payment, two identities, and nothing in the SDK could notice — it took reading BigQuery.
set -euo pipefail

cd "$(dirname "$0")/.."

fail=0
DECLARE='HiPayIntegrationSurface.declareComposeMultiplatform()'

# Public entry points that can reach the monitor. A suspend function here either reports directly or
# calls something that does; the cheap and safe rule is that every one of them declares first.
check_file() {
    local file="$1"
    if [ ! -f "$file" ]; then
        echo "ERROR: $file not found. Did the module move? Update this check rather than deleting it." >&2
        fail=1
        return
    fi
    local entries declared
    entries=$(grep -c '^public actual suspend fun ' "$file" || true)
    if [ "$entries" -eq 0 ]; then
        echo "ERROR: $file — no public entry point found. This check no longer understands the file." >&2
        fail=1
        return
    fi
    # Count the declarations, not their position: each entry point is a separate function body, so
    # one declaration per entry point is the only way the count can match.
    declared=$(grep -c "$DECLARE" "$file" || true)
    if [ "$declared" -lt "$entries" ]; then
        echo "ERROR: $file — $entries public entry point(s), only $declared declare the CMP surface." >&2
        echo "       Add '$DECLARE' as the FIRST statement of the one that does not," >&2
        echo "       or its first reported event is attributed to the native integration." >&2
        fail=1
        return
    fi
    echo "OK: $(basename "$file") — $entries entry point(s), $declared declaring the surface"
}

check_file "hipay-applepay-cmp/src/iosMain/kotlin/com/hipay/card/applepay/cmp/HiPayApplePayPayment.ios.kt"

if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "OK: every CMP entry point declares its surface before reporting"
