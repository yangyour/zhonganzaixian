#!/usr/bin/env bash
# Configuration calculations only. Does not authenticate evidence, publish or persist values.
set -euo pipefail
cd "$(dirname "$0")/.."
configuration_check_dir=$(mktemp -d "${TMPDIR:-/tmp}/mop-configuration-pure.XXXXXXXX")
trap 'rm -rf "$configuration_check_dir"' EXIT
javac --release 17 -Xlint:all,-serial -Werror -d "$configuration_check_dir" \
  server/domain/src/main/java/cn/mop/domain/DomainException.java \
  server/domain/src/main/java/cn/mop/domain/configuration/*.java \
  server/persistence/src/test/java/cn/mop/persistence/configuration/ConfigurationResolutionChecks.java
java -cp "$configuration_check_dir" cn.mop.persistence.configuration.ConfigurationResolutionChecks
