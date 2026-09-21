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
# The payment paths do not call the gateway directly: they go through the controller's own
# `submitOrder`, which also records the payment for recovery. So a "send" is a call to that helper,
# and the single gateway call inside it is the shared implementation — asserted below, so a second
# direct send cannot slip in unchecked.
check_kotlin() {
    local file="$1"
    local sends attaches shared
    shared=$(grep -c 'gateway\.requestNewOrder(order, signature)' "$file" || true)
    if [ "$shared" -ne 1 ]; then
        echo "ERROR: $file — expected exactly one gateway send inside submitOrder, found $shared." >&2
        echo "       This check no longer understands the file; update it rather than deleting it." >&2
        fail=1
        return
    fi
    read -r sends attaches <<EOF
$(awk '
        /private suspend fun submitOrder\(/ { next }
        /order\.withOptions\(/ { seen = NR }
        /submitOrder\(/ {
            sends++
            if (seen && NR - seen <= 3) ok++
        }
        END { print sends + 0, ok + 0 }
    ' "$file")
EOF
    if [ "$sends" -eq 0 ]; then
        echo "ERROR: $file — no order send found. Did the call shape change?" >&2
        echo "       This check no longer understands the file; update it rather than deleting it." >&2
        fail=1
        return
    fi
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

# --- CMP public facade: the HOST must be able to supply the options in the first place ------------
# Checking the order sends above is not enough, and this is not hypothetical: `CmpCardController`
# attached the options correctly while `HiPayCardController` — the expect/actual class CMP hosts
# actually call — had no `options` parameter at all. Every check passed and the feature was simply
# unreachable on that channel. A missing forward in an actual is worse still: it compiles, because
# the parameter is merely unused, and the value is dropped in silence.
check_cmp_facade() {
    local file="$1" expected="$2" what="$3" found
    found=$(grep -c "$what" "$file" || true)
    if [ "$found" -ne "$expected" ]; then
        echo "ERROR: $file — expected $expected '$what', found $found." >&2
        echo "       Both pay() and payWithSavedCard() must carry the caller's OrderOptions." >&2
        fail=1
    else
        echo "OK: ${file##*/} — $found/$expected ($what)"
    fi
}

check_cmp_facade hipaycard-cmp/src/commonMain/kotlin/com/hipay/card/cmp/HiPayCardEntry.kt \
    2 "options: OrderOptions? = null"
check_cmp_facade hipaycard-cmp/src/androidMain/kotlin/com/hipay/card/cmp/HiPayCardEntry.android.kt \
    2 "options = options,"
check_cmp_facade hipaycard-cmp/src/iosMain/kotlin/com/hipay/card/cmp/HiPayCardEntry.ios.kt \
    2 "options = options,"

# --- The Swift surface lives in a submodule ------------------------------------------------------
# Without it the file reads are empty and every check below would report "no longer attaches its
# options" — a wrong cause. Say what is actually missing instead, and never pass silently: skipping
# two of the four surfaces would leave the gate green while half of it went unverified.
if [ ! -d HiPay_Payments_SDK_iOS/Sources ]; then
    echo "ERROR: HiPay_Payments_SDK_iOS/Sources is absent — the Swift surface cannot be checked." >&2
    echo "       Run: git submodule update --init HiPay_Payments_SDK_iOS" >&2
    exit 1
fi

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

# --- Swift: the one order builder actually attaches what its callers forwarded --------------------
# The check above proves the callers pass `options:`; this proves the value is not then dropped on
# the floor. Both Swift card paths go through this single call, so losing it costs every order at
# once while every caller still looks correct.
SWIFT_ORDER="HiPay_Payments_SDK_iOS/Sources/HiPayCore/HiPayPayment.swift"
if grep -q 'order.withOptions(options:' "$SWIFT_ORDER"; then
    echo "OK: ${SWIFT_ORDER##*/} — the built order attaches its options"
else
    echo "ERROR: $SWIFT_ORDER — the order no longer attaches the caller's options." >&2
    echo "       Callers forwarding 'options:' is not enough; the order must carry them." >&2
    fail=1
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
