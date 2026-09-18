#!/bin/bash
# Build release notes for a tag from its commit range, grouped by conventional
# commit type.
#
# Why not `gh release create --generate-notes`: GitHub builds those notes from
# merged pull requests only, and the categories in .github/release.yml match PR
# *labels*. This repository pushes straight to main, so that path yields a bare
# "Full Changelog" line and nothing else — v0.2.0's categorized list was written
# by hand after the fact, and v0.2.1 shipped empty before this script existed.
#
# Squash-merged PRs are covered too: their subject carries "(#123)", which
# GitHub turns into a link on render. True merge commits are skipped because the
# commits they bring in are already in the range and get listed individually.
#
# Usage:
#   scripts/release-notes.sh v0.2.1 [previous-tag] > notes.md
#
# The previous tag defaults to the nearest one before TAG; with none (first
# release) the range starts at the root commit.

set -euo pipefail

TAG="${1:?usage: release-notes.sh <tag> [previous-tag]}"
PREVIOUS="${2:-}"

if ! git rev-parse -q --verify "$TAG^{commit}" >/dev/null; then
  echo "error: $TAG is not a commit-ish in this checkout" >&2
  exit 1
fi

if [ -z "$PREVIOUS" ]; then
  PREVIOUS=$(git describe --tags --abbrev=0 "$TAG^" 2>/dev/null || true)
fi

if [ -n "$PREVIOUS" ]; then
  RANGE="$PREVIOUS..$TAG"
else
  RANGE="$TAG"
fi

REPO_URL=$(git remote get-url origin 2>/dev/null | sed -e 's/\.git$//' -e 's#git@github\.com:#https://github.com/#')

# Category order follows .github/release.yml so the two stay recognisably the
# same product. "Other" is last and has no type filter: an entry must never be
# dropped just because its subject does not parse as a conventional commit.
TITLES=(
  "🚀 Features"
  "🐛 Bug Fixes"
  "⚡ Performance"
  "📚 Documentation"
  "🔧 CI & Tooling"
  "♻️ Refactoring"
  "✅ Tests"
  "📦 Other Changes"
)
TYPES=(
  "feat"
  "fix"
  "perf"
  "docs"
  "ci chore build"
  "refactor"
  "test"
  ""
)

entries=()
while IFS=$'\t' read -r hash subject; do
  [ -n "$hash" ] || continue
  entries+=("$hash	$subject")
done < <(git log --no-merges --reverse --format='%h%x09%s' "$RANGE")

if [ "${#entries[@]}" -eq 0 ]; then
  echo "error: no commits in range $RANGE" >&2
  exit 1
fi

# Records which entries a category already claimed, so the trailing "Other"
# bucket can pick up exactly the ones no typed category matched.
claimed=()
for _ in "${entries[@]}"; do claimed+=("no"); done

commit_type() {
  # type(scope)!: subject  ->  type
  printf '%s' "$1" | sed -n -E 's/^([a-z]+)(\([^)]*\))?!?:.*/\1/p'
}

if [ -n "$REPO_URL" ] && [ -n "$PREVIOUS" ]; then
  printf '**Full Changelog**: %s/compare/%s...%s\n' "$REPO_URL" "$PREVIOUS" "$TAG"
elif [ -n "$REPO_URL" ]; then
  printf '**Full Changelog**: %s/commits/%s\n' "$REPO_URL" "$TAG"
fi

for index in "${!TITLES[@]}"; do
  wanted="${TYPES[$index]}"
  body=""
  for entry_index in "${!entries[@]}"; do
    [ "${claimed[$entry_index]}" = "no" ] || continue
    hash="${entries[$entry_index]%%	*}"
    subject="${entries[$entry_index]#*	}"
    if [ -n "$wanted" ]; then
      type=$(commit_type "$subject")
      [ -n "$type" ] || continue
      case " $wanted " in
        *" $type "*) ;;
        *) continue ;;
      esac
    fi
    claimed[entry_index]="yes"
    body+="- $subject ($hash)"$'\n'
  done
  [ -n "$body" ] || continue
  printf '\n## %s\n\n%s' "${TITLES[$index]}" "$body"
done
