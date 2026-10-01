#!/usr/bin/env bash
# Isolated graph validation. Does not load credentials, contact services or persist data.
set -euo pipefail
cd "$(dirname "$0")/.."
dependency_check_dir=$(mktemp -d "${TMPDIR:-/tmp}/mop-dependency-pure.XXXXXXXX")
trap 'rm -rf "$dependency_check_dir"' EXIT
javac --release 17 -Xlint:all,-serial -Werror -d "$dependency_check_dir" \
  server/domain/src/main/java/cn/mop/domain/DomainException.java \
  server/domain/src/main/java/cn/mop/domain/assetdependency/*.java \
  server/domain/src/test/java/cn/mop/domain/assetdependency/AssetDependencyChecks.java
java -Xss256k -cp "$dependency_check_dir" cn.mop.domain.assetdependency.AssetDependencyChecks
