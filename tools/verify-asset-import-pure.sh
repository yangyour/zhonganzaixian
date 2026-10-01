#!/usr/bin/env bash
# Parsed-row validation only. No file parsing, credentials, network or import execution.
set -euo pipefail
cd "$(dirname "$0")/.."
import_check_dir=$(mktemp -d "${TMPDIR:-/tmp}/mop-import-pure.XXXXXXXX")
trap 'rm -rf "$import_check_dir"' EXIT
javac --release 17 -Xlint:all -Werror -d "$import_check_dir" \
  server/domain/src/main/java/cn/mop/domain/assetimport/*.java \
  server/persistence/src/test/java/cn/mop/persistence/assetimport/AssetImportPrecheckChecks.java
java -cp "$import_check_dir" cn.mop.persistence.assetimport.AssetImportPrecheckChecks
