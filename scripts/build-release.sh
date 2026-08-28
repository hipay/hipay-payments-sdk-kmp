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

# --- 4. Generate the remote Package.swift from the template ------------------
echo "==> Generating remote Package.swift…"
TEMPLATE="$ROOT/HiPay_Payments_SDK_iOS/Package.remote.swift.template"
[ -f "$TEMPLATE" ] || { echo "ERROR: missing $TEMPLATE" >&2; exit 1; }

# The template is a hand-maintained mirror of Package.swift, so adding an SPM product means
# editing TWO files — and forgetting the second one is invisible: the generated manifest still
# parses, `swift package dump-package` still passes, and the tag simply ships without that
# product. That happened between 1.0.0 and 1.1.0 (HiPayApplePay was missing from the template
# for the whole Apple Pay epic). Compare the declared products and targets and refuse to
# generate a manifest that does not match the repository's own.
DEV_MANIFEST="$ROOT/HiPay_Payments_SDK_iOS/Package.swift"
decls() { grep -oE '(library\(name: "|name: ")[A-Za-z]+"' "$1" | grep -oE '"[A-Za-z]+"' | sort -u; }
if ! MISMATCH="$(diff <(decls "$DEV_MANIFEST") <(decls "$TEMPLATE"))"; then
    echo "ERROR: Package.remote.swift.template is out of step with Package.swift." >&2
    echo "       A product or target declared in one is absent from the other, so the tagged" >&2
    echo "       manifest would ship an incomplete package. Reconcile them, then re-run." >&2
    echo "       ('<' = only in Package.swift, '>' = only in the template)" >&2
    echo "$MISMATCH" >&2
    exit 1
fi
# Substitute placeholders. Delimiter '|' avoids the slashes in URLs; escape '&'
# (means "the matched text" in a sed replacement) so an exotic REPO_SLUG can't
# corrupt the output. CHECKSUM is hex, so it needs no escaping.
URL_ESC="${URL//&/\\&}"
sed -e "s|{{URL}}|${URL_ESC}|g" \
    -e "s|{{CHECKSUM}}|${CHECKSUM}|g" \
    "$TEMPLATE" > "$OUT/Package.swift"

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
