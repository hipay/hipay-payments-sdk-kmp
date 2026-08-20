#!/usr/bin/env bash
#
# Builds the HiPayPayments XCFramework and refreshes the local SPM package.
#
# Development loop (edit Kotlin -> rebuild -> run demo):
#   1. Edit Kotlin sources under hipaycore/src/
#   2. Run this script:            ./scripts/build-xcframework.sh
#   3. In Xcode, the HiPay SPM package picks up the new binary automatically
#      (File > Packages > Reset Package Caches only if Xcode holds a stale copy)
#   4. Run the demo app on the simulator
#
# Output: swift/HiPayPayments.xcframework (git-ignored build artifact)

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

"$ROOT/gradlew" -p "$ROOT" :hipaycore:assembleHiPayPaymentsReleaseXCFramework

rsync -a --delete \
  "$ROOT/hipaycore/build/XCFrameworks/release/HiPayPayments.xcframework/" \
  "$ROOT/HiPay_Payments_SDK_iOS/HiPayPayments.xcframework/"

echo "OK: HiPay_Payments_SDK_iOS/HiPayPayments.xcframework refreshed"
