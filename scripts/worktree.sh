#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 || ! "$1" =~ ^BC-[0-9]{4}$ || ! "$2" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]]; then
  echo "usage: scripts/worktree.sh BC-#### lower-case-slug" >&2
  exit 2
fi

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

if ! git show-ref --verify --quiet refs/heads/integration; then
  echo "integration branch does not exist. After the baseline commit, run: git branch integration" >&2
  exit 1
fi

ticket="$1"
slug="$2"
branch="feature/$ticket-$slug"
target="../beacon-$ticket"

if [[ -e "$target" ]]; then
  echo "worktree path already exists: $target" >&2
  exit 1
fi
if git show-ref --verify --quiet "refs/heads/$branch"; then
  echo "branch already exists: $branch" >&2
  exit 1
fi

git worktree add -b "$branch" "$target" integration
