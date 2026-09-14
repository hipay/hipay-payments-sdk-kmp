#!/usr/bin/env bash
#
# Builds the SPM REMOTE-release artifacts for the iOS distribution channel (story 9.1):
#   1. the HiPayPayments XCFramework (reuses build-xcframework.sh),
#   2. a SPM-compatible zip of it (ditto, --keepParent),
#   3. its SwiftPM checksum (swift package compute-checksum),
#   4. a generated remote Package.swift (binaryTarget(url:checksum:)) from the template.
#
# This produces what a maintainer uploads to a GitHub Release on the gated path
# (publish.yml). It performs NO network upload and NO tag/Release push — the real
# publish is the maintainer's gated step (see _bmad-output/planning-artifacts/publishing.md).
#
# Version policy (story 8.2): the release tag = the single-source version in
# gradle.properties. Never hardcode it here.
#
# The asset lives on the iOS distribution repo (architecture-repos.md, R3 amended).
# Override only to test against a fork:
#   REPO_SLUG=owner/repo ./scripts/build-release.sh
#
# Output: build-output-local/spm/
#   HiPayPayments.xcframework.zip, checksum.txt, Package.swift (remote)
#
# Usage:
#   ./scripts/build-release.sh                 # uses gradle.properties version + placeholder repo
#   REPO_SLUG=hipay/foo TAG=0.1.0 ./scripts/build-release.sh

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build-output-local/spm"

# --- Preconditions -----------------------------------------------------------
command -v swift >/dev/null 2>&1 || {
  echo "ERROR: 'swift' toolchain not found — required for compute-checksum. Install Xcode / Swift." >&2
  exit 1
}
command -v ditto >/dev/null 2>&1 || {
  echo "ERROR: 'ditto' not found (macOS only). SPM zips must be ditto-made, not 'zip -r'." >&2
  exit 1
}

# --- Version (single source: gradle.properties, story 8.2) -------------------
# Tolerate optional whitespace around '=' (valid in Java .properties).
VERSION="$(sed -n 's/^version[[:space:]]*=[[:space:]]*//p' "$ROOT/gradle.properties" | head -1)"
[ -n "$VERSION" ] || { echo "ERROR: could not read 'version=' from gradle.properties" >&2; exit 1; }
TAG="${TAG:-$VERSION}"

# --- Distribution repo (settled: architecture-repos.md §2) -------------------
# Defaults to the real repo. A wrong slug produces a manifest whose binaryTarget
# URL 404s, and that only surfaces when a merchant tries to install the package —
# so an unset variable must not silently yield a placeholder.
REPO_SLUG="${REPO_SLUG:-hipay/hipay-payments-sdk-ios}"
case "$REPO_SLUG" in
  */*) ;;
  *) echo "ERROR: REPO_SLUG must be owner/repo, got '$REPO_SLUG'" >&2; exit 1 ;;
esac
ASSET="HiPayPayments.xcframework.zip"
URL="https://github.com/${REPO_SLUG}/releases/download/${TAG}/${ASSET}"

# --- 1+2. Build XCFramework, then zip it (SPM layout) ------------------------
echo "==> Building XCFramework (build-xcframework.sh)…"
"$ROOT/scripts/build-xcframework.sh"

mkdir -p "$OUT"
rm -f "$OUT/$ASSET"
echo "==> Zipping XCFramework for SPM (ditto --keepParent)…"
ditto -c -k --sequesterRsrc --keepParent \
  "$ROOT/HiPay_Payments_SDK_iOS/HiPayPayments.xcframework" \
  "$OUT/$ASSET"

# --- 3. SwiftPM checksum -----------------------------------------------------
echo "==> Computing SwiftPM checksum…"
CHECKSUM="$(swift package --package-path "$ROOT/HiPay_Payments_SDK_iOS" compute-checksum "$OUT/$ASSET")"
echo "$CHECKSUM" > "$OUT/checksum.txt"

# --- 4. Generate the remote Package.swift FROM Package.swift -----------------
echo "==> Generating remote Package.swift…"
DEV_MANIFEST="$ROOT/HiPay_Payments_SDK_iOS/Package.swift"
[ -f "$DEV_MANIFEST" ] || { echo "ERROR: missing $DEV_MANIFEST" >&2; exit 1; }

# Derived from the real manifest, not from a parallel copy of it. There used to be a
# `Package.remote.swift.template` maintained by hand, and the two drifted: `HiPayApplePay` was added
# to Package.swift and never to the template, so 1.1.0 came one step from tagging a manifest with no
# Apple Pay product. Nothing caught it — the generated manifest still parsed, and
# `swift package dump-package` proves a manifest parses, never that it is complete.
#
# The whole difference between the two is ONE line, so transform it instead of duplicating it: a
# local `path:` becomes a remote `url:` + `checksum:`. Adding an SPM product is then a change to a
# single file, and there is no second file left to forget.
#
# awk rather than sed: it preserves the original indentation and, more importantly, it counts the
# matches. Exactly one is required — zero means the manifest was reshaped and this script no longer
# understands it; more than one means the substitution would be ambiguous. Either way, stop.
awk -v url="$URL" -v checksum="$CHECKSUM" -v version="$TAG" '
    NR == 1 {
        print $0
        print ""
        print "// GENERATED for the " version " release by scripts/build-release.sh — do not edit, and do"
        print "// not commit it to a branch: it exists only on the release tag. The branches keep the local"
        print "// binaryTarget so day-to-day development builds against the XCFramework on disk."
        next
    }
    /path: "HiPayPayments\.xcframework"/ {
        match($0, /^[[:space:]]*/)
        indent = substr($0, 1, RLENGTH)
        printf "%surl: \"%s\",\n", indent, url
        printf "%schecksum: \"%s\"\n", indent, checksum
        found++
        next
    }
    { print }
    END {
        if (found != 1) {
            printf "ERROR: expected exactly one local binaryTarget path in Package.swift, found %d.\n", found > "/dev/stderr"
            print "       The manifest changed shape; update this transformation before releasing." > "/dev/stderr"
            exit 1
        }
    }
' "$DEV_MANIFEST" > "$OUT/Package.swift"

echo ""
echo "OK: SPM remote-release artifacts in $OUT"
echo "    version (gradle.properties): $VERSION"
echo "    release tag:                 $TAG"
echo "    asset:                       $ASSET"
echo "    checksum:                    $CHECKSUM"
echo "    asset URL (repo=$REPO_SLUG): $URL"
echo ""
echo "Next (maintainer, gated path — NOT done by this script):"
echo "  - attach $ASSET to the GitHub Release for tag $TAG"
echo "  - publish the distribution repo's Package.swift = $OUT/Package.swift"
