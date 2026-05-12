#!/usr/bin/env bash
#
# release.sh — create a GitHub release for the most recent v* tag, using
# its section from RELEASE_NOTES.md as the body. Builds the plugin JAR
# via Ant and attaches it to the release as a versioned asset.
#
# Usage:  ./release.sh   (no args)
#
# Requires:
#   - gh CLI installed and authenticated on this host
#   - ant installed (for the JAR build)
#   - the tag already created (and pushed to origin) for the version you're releasing
#   - RELEASE_NOTES.md with sections shaped like:  "# vX.Y.Z — Title text"
#
# Exit codes:
#   0 success, 1 usage / parsing / cancel, 2 gh failure, 3 build failure

set -euo pipefail

REPO="OpenHistoricalMap/ohm-josm-tag-validator"
NOTES_FILE="RELEASE_NOTES.md"
JAR_PATH="dist/OHM_Tag_Validator.jar"

VERSION=$(git tag --list 'v*' --sort=-v:refname | head -1)
if [[ -z "$VERSION" ]]; then
    echo "No v* tags found in this repo." >&2
    exit 1
fi

# Find this version's section start. Header shape: "# vX.Y.Z<space>...".
START=$(grep -n "^# ${VERSION} " "$NOTES_FILE" | head -1 | cut -d: -f1 || true)
if [[ -z "$START" ]]; then
    echo "No section for $VERSION found in $NOTES_FILE." >&2
    echo "Expected a line like:  # $VERSION — Some title" >&2
    exit 1
fi

# Title: everything after the leading "# " on the header line.
TITLE=$(sed -n "${START}p" "$NOTES_FILE" | sed 's/^# //')

# Find the next "# v" header after START (or EOF).
END=$(awk -v s="$START" 'NR>s && /^# v[0-9]/ { print NR; exit }' "$NOTES_FILE")

NOTES_TMP=$(mktemp)
trap "rm -f $NOTES_TMP" EXIT

if [[ -n "$END" ]]; then
    sed -n "$((START+1)),$((END-1))p" "$NOTES_FILE" > "$NOTES_TMP"
else
    sed -n "$((START+1)),\$p" "$NOTES_FILE" > "$NOTES_TMP"
fi

# Strip lone "---" separator lines (used between version sections).
grep -v '^---$' "$NOTES_TMP" > "${NOTES_TMP}.s" && mv "${NOTES_TMP}.s" "$NOTES_TMP"

# Trim leading blank lines.
awk 'BEGIN { in_content=0 } NF { in_content=1 } in_content { print }' \
    "$NOTES_TMP" > "${NOTES_TMP}.s" && mv "${NOTES_TMP}.s" "$NOTES_TMP"

# Trim trailing blank lines.
awk 'NF { for (i=0;i<held;i++) print ""; held=0; print; next } { held++ }' \
    "$NOTES_TMP" > "${NOTES_TMP}.s" && mv "${NOTES_TMP}.s" "$NOTES_TMP"

# Versioned JAR asset name. The build always writes to the same
# unversioned path; we rename the upload to a versioned filename so each
# release's downloads page makes the version obvious.
JAR_ASSET="ohm-tags-${VERSION}.jar"

echo "Will:"
echo "  1. Build JAR via 'ant clean dist' (writes $JAR_PATH)."
echo "  2. Run: gh release create $VERSION -R $REPO -t \"$TITLE\" -F <notes-file>"
echo "  3. Upload $JAR_PATH as $JAR_ASSET to the new release."
echo
echo "(Notes body extracted from $NOTES_FILE's section for $VERSION.)"
echo
echo "----- notes body preview -----"
cat "$NOTES_TMP"
echo "----- end preview -----"
echo
read -r -p "Proceed? [y/N] " confirm
case "$confirm" in
    y|Y|yes|YES) ;;
    *) echo "Aborted."; exit 1 ;;
esac

echo
echo "Building JAR..."
if ! ant clean dist >/dev/null; then
    echo "ant build failed." >&2
    exit 3
fi
if [[ ! -f "$JAR_PATH" ]]; then
    echo "Expected JAR not found at $JAR_PATH after build." >&2
    exit 3
fi
echo "Built: $JAR_PATH ($(du -h "$JAR_PATH" | cut -f1))"

if ! gh release create "$VERSION" -R "$REPO" -t "$TITLE" -F "$NOTES_TMP" \
        "$JAR_PATH#$JAR_ASSET"; then
    echo "gh release create failed." >&2
    exit 2
fi

echo "Release $VERSION created with JAR attached as $JAR_ASSET."
