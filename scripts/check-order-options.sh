#!/usr/bin/env bash
# Every place a controller sends an order must attach the caller's optional gateway parameters first.
#
# This is a static check because it cannot be a test. `OrderRequest.extraFields` is private and
# `toFields()` is internal to :hipaycore, and there are no friend paths between modules — so from the
# controllers' own test source sets there is no way to read what options landed on a request. The
# controller tests cover everything that IS visible (`oneClick`, `cardToken`, `eci`, the order id);
# this covers the one line they cannot see.
#
# What it protects: someone adds a third payment path, or edits an existing one, and forgets to carry
# the options over. The payment still works, the host sees nothing wrong, and a per-order notification
# URL is silently dropped. That is the same failure shape as the `oneClick` regression, which reached
# a release for exactly this reason — a value that only the gateway could have noticed was missing.
set -euo pipefail

cd "$(dirname "$0")/.."

fail=0

# --- Kotlin: every order send must be preceded, within 3 lines, by the options attachment ---------
check_kotlin() {
    local file="$1"
    local sends attaches
    sends=$(grep -c 'gateway\.requestNewOrder(order, signature)' "$file" || true)
    if [ "$sends" -eq 0 ]; then
        echo "ERROR: $file — no order send found. Did the call shape change?" >&2
        echo "       This check no longer understands the file; update it rather than deleting it." >&2
        fail=1
        return
    fi
    # Look back 3 lines from each send for `order.withOptions(`.
    attaches=$(awk '
        /order\.withOptions\(/ { seen = NR }
        /gateway\.requestNewOrder\(order, signature\)/ {
            if (seen && NR - seen <= 3) ok++
        }
        END { print ok + 0 }
    ' "$file")
    if [ "$attaches" -ne "$sends" ]; then
        echo "ERROR: $file — $sends order send(s), but only $attaches attach the options." >&2
        echo "       Every order must carry the caller's OrderOptions: add" >&2
        echo "       'options?.let { order.withOptions(it) }' before the send." >&2
        fail=1
    else
        echo "OK: ${file##*/} — $sends order send(s), all attaching the options"
    fi
}

check_kotlin hipaycard/src/main/kotlin/com/hipay/card/HiPayCardEntryController.kt
check_kotlin hipaycard-cmp/src/commonMain/kotlin/com/hipay/card/cmp/CmpCardController.kt

# --- Swift: the card controller forwards `options:` inside every order request --------------------
# Counted per CALL, not per file: `pay` also delegates to `payWithSavedCard` with `options: options`,
# so a file-wide count reads three forwardings for two requests and cries wolf.
SWIFT_CARD="HiPay_Payments_SDK_iOS/Sources/HiPayCard/HiPayCardEntryController.swift"
read -r sends forwards <<<"$(awk '
    /requestCardOrder\(/ { inCall = 1; sends++; found = 0; next }
    inCall && /options:/ { found = 1 }
    inCall && /^[[:space:]]*\)/ { inCall = 0; if (found) forwards++ }
    END { print sends + 0, forwards + 0 }
' "$SWIFT_CARD")"
if [ "$sends" -eq 0 ]; then
    echo "ERROR: $SWIFT_CARD — no requestCardOrder call found; update this check." >&2
    fail=1
elif [ "$forwards" -ne "$sends" ]; then
    echo "ERROR: $SWIFT_CARD — $sends order request(s), $forwards forwarding 'options:'." >&2
    echo "       Every order must carry the caller's HiPayOrderOptions." >&2
    fail=1
else
    echo "OK: ${SWIFT_CARD##*/} — $sends order request(s), all forwarding the options"
fi

# --- Swift: an Apple Pay order carries its options into the Kotlin order --------------------------
SWIFT_APPLE="HiPay_Payments_SDK_iOS/Sources/HiPayApplePay/HiPayApplePayPayment.swift"
if grep -q 'withOptions(options:' "$SWIFT_APPLE"; then
    echo "OK: ${SWIFT_APPLE##*/} — the wallet order carries its options"
else
    echo "ERROR: $SWIFT_APPLE — the wallet order no longer attaches its options." >&2
    echo "       Apple Pay ends in an ordinary order and must take the same parameters." >&2
    fail=1
fi

[ "$fail" -eq 0 ] || exit 1
echo "OK: optional gateway parameters reach every order"
