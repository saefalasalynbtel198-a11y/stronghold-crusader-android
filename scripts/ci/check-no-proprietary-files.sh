#!/usr/bin/env bash
# Fails if any proprietary game file type is tracked in git. Game data must never be committed.
set -euo pipefail
pattern='\.(exe|dll|gm1|tgx|bik|map|aiv|sav|raw|rar|7z|hlp|ani|cur)$'
if offenders=$(git ls-files | grep -Ei "$pattern"); then
  echo "::error::Proprietary game file types are tracked in git:"
  echo "$offenders"
  exit 1
fi
echo "OK: no proprietary game files tracked."
