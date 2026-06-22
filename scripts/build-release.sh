#!/usr/bin/env bash
#
# Builds the SPM REMOTE-release artifacts for the iOS distribution channel (story 9.1):
#   1. the HiPayFullservice XCFramework (reuses build-xcframework.sh),
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
# Repo policy (deferred to architecture-repos.md): the asset URL host is a
# placeholder until the distribution-repo topology is decided. Override with:
#   REPO_SLUG=owner/repo ./scripts/build-release.sh
#
# Output: build-output-local/spm/
#   HiPayFullservice.xcframework.zip, checksum.txt, Package.swift (remote)
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

# --- Repo placeholder (deferred: architecture-repos.md) ----------------------
REPO_SLUG="${REPO_SLUG:-OWNER/REPO}"
ASSET="HiPayFullservice.xcframework.zip"
URL="https://github.com/${REPO_SLUG}/releases/download/${TAG}/${ASSET}"
if [ "$REPO_SLUG" = "OWNER/REPO" ]; then
  echo "WARNING: REPO_SLUG is the placeholder 'OWNER/REPO' — the final distribution repo is" >&2
  echo "         pending architecture-repos.md. The generated URL is illustrative, not final." >&2
fi

# --- 1+2. Build XCFramework, then zip it (SPM layout) ------------------------
echo "==> Building XCFramework (build-xcframework.sh)…"
"$ROOT/scripts/build-xcframework.sh"

mkdir -p "$OUT"
rm -f "$OUT/$ASSET"
echo "==> Zipping XCFramework for SPM (ditto --keepParent)…"
ditto -c -k --sequesterRsrc --keepParent \
  "$ROOT/swift/HiPayFullservice.xcframework" \
  "$OUT/$ASSET"

# --- 3. SwiftPM checksum -----------------------------------------------------
echo "==> Computing SwiftPM checksum…"
CHECKSUM="$(swift package --package-path "$ROOT/swift" compute-checksum "$OUT/$ASSET")"
echo "$CHECKSUM" > "$OUT/checksum.txt"

# --- 4. Generate the remote Package.swift from the template ------------------
echo "==> Generating remote Package.swift…"
TEMPLATE="$ROOT/swift/Package.remote.swift.template"
[ -f "$TEMPLATE" ] || { echo "ERROR: missing $TEMPLATE" >&2; exit 1; }
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
