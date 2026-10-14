#!/bin/sh
set -eu
repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
node "$repo_root/test/contracts/validate_examples.cjs"
