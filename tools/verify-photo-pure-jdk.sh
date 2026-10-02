#!/usr/bin/env bash
# Isolated source checks. Does not start services, use credentials or migrate data.
set -euo pipefail
cd "$(dirname "$0")/.."
photo_check_dir=$(mktemp -d "${TMPDIR:-/tmp}/mop-photo-pure.XXXXXXXX")
trap 'rm -rf "$photo_check_dir"' EXIT
javac --release 17 -Xlint:all,-serial -Werror -d "$photo_check_dir" \
  server/domain/src/main/java/cn/mop/domain/*.java \
  server/adapters/src/main/java/cn/mop/adapters/oss/OssStorageSettings.java \
  server/adapters/src/main/java/cn/mop/adapters/oss/StorageProviderSelection.java \
  server/api/src/main/java/cn/mop/api/PhotoPolicy.java \
  server/worker/src/main/java/cn/mop/worker/WorkerRuntimeSettings.java \
  tests/pure-java/PhotoRulesCheck.java \
  tests/pure-java/MopPhotoPolicyCheck.java \
  tests/pure-java/WorkerRuntimeSettingsCheck.java
java -cp "$photo_check_dir" PhotoRulesCheck
java -Djava.awt.headless=true -cp "$photo_check_dir" MopPhotoPolicyCheck
java -cp "$photo_check_dir" cn.mop.worker.WorkerRuntimeSettingsCheck
