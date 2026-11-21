#!/usr/bin/env bash
# Fails if any proprietary game file type is tracked in git. Game data must never be committed.
set -euo pipefail
pattern='\.(exe|dll|gm1|tgx|bik|map|aiv|sav|raw|rar|7z|hlp|ani|cur)$'
if offenders=$(git ls-files | grep -Ei "$pattern"); then
  echo "::error::Proprietary game file types are tracked in git:"
  echo "$offenders"
  exit 1
fi
if keys=$(git ls-files | grep -Ei '\.(jks|keystore|p12|pfx|pk8)$|\.(jks|keystore)\.b64$'); then
  echo "::error::Signing key files are tracked in git (they belong only in GitHub Actions secrets):"
  echo "$keys"
  exit 1
fi
echo "OK: no proprietary game files tracked."
echo "OK: no signing key files tracked."
