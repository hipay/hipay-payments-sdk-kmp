#!/usr/bin/env bash
#
# Builds the HiPayFullservice XCFramework and refreshes the local SPM package.
#
# Development loop (edit Kotlin -> rebuild -> run demo):
#   1. Edit Kotlin sources under hipayfullservice/src/
#   2. Run this script:            ./scripts/build-xcframework.sh
#   3. In Xcode, the HiPay SPM package picks up the new binary automatically
#      (File > Packages > Reset Package Caches only if Xcode holds a stale copy)
#   4. Run the demo app on the simulator
#
# Output: swift/HiPayFullservice.xcframework (git-ignored build artifact)

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

"$ROOT/gradlew" -p "$ROOT" :hipayfullservice:assembleHiPayFullserviceReleaseXCFramework

rsync -a --delete \
  "$ROOT/hipayfullservice/build/XCFrameworks/release/HiPayFullservice.xcframework/" \
  "$ROOT/swift/HiPayFullservice.xcframework/"

echo "OK: swift/HiPayFullservice.xcframework refreshed"
