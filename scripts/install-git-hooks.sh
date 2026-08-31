#!/usr/bin/env bash
# Point this clone at the repository-managed hooks directory.
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
git -C "$repo_root" config core.hooksPath .githooks
echo "Installed repository Git hooks (.githooks)."
