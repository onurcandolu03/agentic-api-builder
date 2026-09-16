#!/usr/bin/env bash
set -euo pipefail

# Compile/run the isolated harness and fixed validation fixtures; never download jars or invoke project scripts.
harness_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
if [[ -n "${HARNESS_JACKSON_CLASSPATH:-}" ]]; then
  harness_classpath="$HARNESS_JACKSON_CLASSPATH"
else
  harness_cache="$HOME/.m2/repository"
  harness_classpath="$harness_cache/tools/jackson/core/jackson-core/3.1.5/jackson-core-3.1.5.jar"
  harness_classpath+=":$harness_cache/tools/jackson/core/jackson-databind/3.1.5/jackson-databind-3.1.5.jar"
  harness_classpath+=":$harness_cache/com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar"
fi
IFS=: read -r -a harness_jars <<< "$harness_classpath"
for harness_jar in "${harness_jars[@]}"; do
  [[ -f "$harness_jar" ]] || { printf 'Missing cached harness dependency: %s\n' "$harness_jar" >&2; exit 1; }
done
harness_temp_parent="$(cd "${TMPDIR:-/tmp}" && pwd -P)"
harness_temp="$(mktemp -d "$harness_temp_parent/controlled-harness-check.XXXXXXXX")"
trap 'rm -rf -- "$harness_temp"' EXIT
mkdir "$harness_temp/classes"
javac --release 21 -cp "$harness_classpath" -d "$harness_temp/classes" \
  "$harness_root"/src/dev/agentic/harness/*.java \
  "$harness_root"/test/dev/agentic/harness/*.java
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.HarnessTest "$harness_temp"
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.SourceContractTest "$harness_root/.." "$harness_temp"
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.ArtifactContractTest
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.BrokerTest "$harness_temp"
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.RuntimeTest "$harness_temp" "$harness_root/.."
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.ImplementationRuntimeTest "$harness_temp" "$harness_root/.."
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.ValidationRuntimeTest "$harness_temp" "$harness_root/.."
