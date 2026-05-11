#!/usr/bin/env bash
#
# release.sh — create a GitHub release for the most recent v* tag, using
# its section from RELEASE_NOTES.md as the body.
#
# Usage:  ./release.sh   (no args)
#
# Requires:
#   - gh CLI installed and authenticated on this host
#   - the tag already created (and pushed to origin) for the version you're releasing
#   - RELEASE_NOTES.md with sections shaped like:  "# vX.Y.Z — Title text"
#
# Exit codes:
#   0 success, 1 usage / parsing / cancel, 2 gh failure

set -euo pipefail

REPO="OpenHistoricalMap/ohm-josm-tag-validator"
NOTES_FILE="RELEASE_NOTES.md"

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

echo "Will run this command:"
echo
echo "  gh release create $VERSION -R $REPO -t \"$TITLE\" -F $NOTES_TMP"
echo
echo "(-F target is a temp file holding the section for $VERSION extracted from $NOTES_FILE.)"
echo
echo "----- notes body preview -----"
cat "$NOTES_TMP"
echo "----- end preview -----"
echo
read -r -p "Run this command? [y/N] " confirm
case "$confirm" in
    y|Y|yes|YES) ;;
    *) echo "Aborted."; exit 1 ;;
esac

if ! gh release create "$VERSION" -R "$REPO" -t "$TITLE" -F "$NOTES_TMP"; then
    echo "gh release create failed." >&2
    exit 2
fi

echo "Release $VERSION created."
