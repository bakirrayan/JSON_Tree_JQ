#!/usr/bin/env bash
# create_tag.sh — bump semver tag, update CHANGELOG, commit, and push main + tag
#
# Usage:
#   ./bash_scripts/create_tag.sh           # auto-bump patch (1.0.0 → 1.0.1)
#   ./bash_scripts/create_tag.sh patch     # same as above
#   ./bash_scripts/create_tag.sh minor     # bump minor  (1.0.0 → 1.1.0)
#   ./bash_scripts/create_tag.sh major     # bump major  (1.0.0 → 2.0.0)

set -euo pipefail

BUMP="patch"

for arg in "$@"; do
  case "$arg" in
    major|minor|patch) BUMP="$arg" ;;
    *)
      echo "Unknown argument: $arg"
      echo "Usage: $0 [major|minor|patch]"
      exit 1
      ;;
  esac
done

# ---------------------------------------------------------------------------
# 1. Resolve repo root and CHANGELOG path
# ---------------------------------------------------------------------------
REPO_ROOT=$(git rev-parse --show-toplevel)
CHANGELOG="$REPO_ROOT/CHANGELOG.md"

# ---------------------------------------------------------------------------
# 2. Determine current branch and remote
# ---------------------------------------------------------------------------
BRANCH=$(git rev-parse --abbrev-ref HEAD)
REMOTE="origin"

# ---------------------------------------------------------------------------
# 3. Compute next version from latest semver tag
# ---------------------------------------------------------------------------
LATEST=$(git tag --sort=-v:refname | grep -E '^v?[0-9]+\.[0-9]+\.[0-9]+$' | head -1)

if [[ -z "$LATEST" ]]; then
  echo "No existing semver tags found. Starting from 0.0.0."
  MAJOR=0; MINOR=0; PATCH_NUM=0
else
  CLEAN="${LATEST#v}"
  IFS='.' read -r MAJOR MINOR PATCH_NUM <<< "$CLEAN"
fi

echo "Latest tag: ${LATEST:-"(none)"}"

case "$BUMP" in
  major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH_NUM=0 ;;
  minor) MINOR=$((MINOR + 1)); PATCH_NUM=0 ;;
  patch) PATCH_NUM=$((PATCH_NUM + 1)) ;;
esac

NEW_TAG="${MAJOR}.${MINOR}.${PATCH_NUM}"
TODAY=$(date +%Y-%m-%d)
echo "New tag:    $NEW_TAG  ($TODAY)"

# ---------------------------------------------------------------------------
# 4. Collect commits since the last tag
# ---------------------------------------------------------------------------
if [[ -n "$LATEST" ]]; then
  COMMITS=$(git log "${LATEST}..HEAD" --oneline)
else
  COMMITS=$(git log --oneline)
fi

if [[ -z "$COMMITS" ]]; then
  echo "Warning: no new commits since ${LATEST:-root}. Proceeding anyway."
  COMMITS="No new commits."
fi

# ---------------------------------------------------------------------------
# 5. Build the new CHANGELOG section
# ---------------------------------------------------------------------------
COMMIT_LINES=""
while IFS= read -r line; do
  # Strip the short hash prefix and format as a list item
  MSG="${line#* }"
  COMMIT_LINES="${COMMIT_LINES}- ${MSG}"$'\n'
done <<< "$COMMITS"

# Derive the GitHub compare URL
REPO_URL=$(git remote get-url "$REMOTE" \
  | sed -E 's|git@github\.com:([^/]+)/([^.]+)\.git|https://github.com/\1/\2|; s|\.git$||')

if [[ -n "$LATEST" ]]; then
  COMPARE_URL="${REPO_URL}/compare/${LATEST}...${NEW_TAG}"
else
  COMPARE_URL="${REPO_URL}/releases/tag/${NEW_TAG}"
fi

NEW_SECTION="## [${NEW_TAG}] - ${TODAY}

### Changed
${COMMIT_LINES}
[${NEW_TAG}]: ${COMPARE_URL}"

# ---------------------------------------------------------------------------
# 6. Inject new section into CHANGELOG.md (after the header block)
# ---------------------------------------------------------------------------
# Find the line number of the first "## [" heading
FIRST_ENTRY=$(grep -n '^## \[' "$CHANGELOG" | head -1 | cut -d: -f1)

if [[ -z "$FIRST_ENTRY" ]]; then
  # No existing entry — just append
  printf '\n%s\n' "$NEW_SECTION" >> "$CHANGELOG"
else
  # Insert before the first existing entry
  HEAD=$((FIRST_ENTRY - 1))
  HEAD_CONTENT=$(head -n "$HEAD" "$CHANGELOG")
  TAIL_CONTENT=$(tail -n +"$FIRST_ENTRY" "$CHANGELOG")
  printf '%s\n%s\n\n%s\n' "$HEAD_CONTENT" "$NEW_SECTION" "$TAIL_CONTENT" > "$CHANGELOG"
fi

# Also update the reference link for the previous tag if it exists
if [[ -n "$LATEST" ]]; then
  # Replace the old tag's self-link with a compare link pointing to the new tag
  OLD_LINK_LINE="[${LATEST}]: ${REPO_URL}/releases/tag/${LATEST}"
  NEW_LINK_LINE="[${LATEST}]: ${REPO_URL}/compare/$(git tag --sort=-v:refname | grep -E '^v?[0-9]+\.[0-9]+\.[0-9]+$' | sed -n '2p')...${LATEST}"
  # Only rewrite if the simple releases link is still there
  if grep -qF "$OLD_LINK_LINE" "$CHANGELOG"; then
    sed -i "s|${OLD_LINK_LINE}|${NEW_LINK_LINE}|" "$CHANGELOG"
  fi
fi

echo "Updated CHANGELOG.md"

# ---------------------------------------------------------------------------
# 7. Commit the CHANGELOG update
# ---------------------------------------------------------------------------
git add "$CHANGELOG"
git commit -m "chore: release ${NEW_TAG}"
echo "Committed CHANGELOG update"

# ---------------------------------------------------------------------------
# 8. Create annotated tag
# ---------------------------------------------------------------------------
ANNOTATION="$(printf 'Release %s\n\nChanges since %s:\n%s' "$NEW_TAG" "${LATEST:-root}" "$COMMITS")"
git tag -a "$NEW_TAG" -m "$ANNOTATION"
echo "Created annotated tag: $NEW_TAG"

# ---------------------------------------------------------------------------
# 9. Push branch and tag
# ---------------------------------------------------------------------------
git push "$REMOTE" "$BRANCH"
echo "Pushed branch $BRANCH to $REMOTE"

git push "$REMOTE" "$NEW_TAG"
echo "Pushed tag $NEW_TAG to $REMOTE"
