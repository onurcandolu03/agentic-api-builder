#!/usr/bin/env bash
set -euo pipefail

# Compile/run the isolated harness and fixed validation fixtures; never download jars or invoke project scripts.
harness_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
if [[ $# -gt 2 || ( $# -ge 1 && "$1" != "customer-filter-demo" && "$1" != "migration-input" && "$1" != "workflow" && "$1" != "validation" && "$1" != "implementation" && "$1" != "operation-implementation" && "$1" != "operation-validation" && "$1" != "validation-runtime" ) || ( $# -eq 2 && "$1" != "validation" && "$1" != "implementation" && "$1" != "operation-implementation" && "$1" != "operation-validation" && "$1" != "validation-runtime" ) ]]; then
  printf 'Usage: test.sh [customer-filter-demo|workflow|migration-input|operation-validation [cases]|validation-runtime [case]|validation [comma-separated-maven-cases]|implementation [filter]|operation-implementation [filter]]\n' >&2
  exit 2
fi
if [[ -n "${HARNESS_JACKSON_CLASSPATH:-}" ]]; then
  harness_classpath="$HARNESS_JACKSON_CLASSPATH"
else
  harness_cache="$HOME/.m2/repository"
  harness_classpath="$harness_cache/tools/jackson/core/jackson-core/3.1.5/jackson-core-3.1.5.jar"
  harness_classpath+=":$harness_cache/tools/jackson/core/jackson-databind/3.1.5/jackson-databind-3.1.5.jar"
  harness_classpath+=":$harness_cache/com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar"
fi
harness_classpath+=":${HARNESS_YAML_CLASSPATH:-$HOME/.m2/repository/org/yaml/snakeyaml/2.6/snakeyaml-2.6.jar}"
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
if [[ "${1:-}" == "customer-filter-demo" ]]; then
  java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.CustomerFilterDemoTest "$harness_temp" "$harness_root/.."
  exit 0
fi
if [[ "${1:-}" == "operation-validation" ]]; then
  java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.OperationValidationTest "$harness_temp" "$harness_root/.." "${2:-}"
  exit 0
fi
if [[ "${1:-}" == "validation-runtime" ]]; then
  if [[ $# -eq 2 ]]; then
    java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.ValidationRuntimeTest "$harness_temp" "$harness_root/.." "$2"
  else
    java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.ValidationRuntimeTest "$harness_temp" "$harness_root/.."
  fi
  exit 0
fi
if [[ "${1:-}" == "operation-implementation" ]]; then
  java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.OperationImplementationTest "$harness_temp" "$harness_root/.." "${2:-}"
  exit 0
fi
if [[ "${1:-}" == "implementation" ]]; then
  java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.ImplementationRuntimeTest "$harness_temp" "$harness_root/.." "${2:-}"
  exit 0
fi
if [[ "${1:-}" == "validation" ]]; then
  java -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.ValidationProcessLifecycleTest
  if [[ $# -eq 2 ]]; then
    java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.MavenValidationTest "$harness_temp" "$harness_root/.." "$2"
  else
    java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.MavenValidationTest "$harness_temp" "$harness_root/.."
  fi
  if [[ $# -eq 1 ]]; then
    java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.ValidationRuntimeTest "$harness_temp" "$harness_root/.." success
  fi
  exit 0
fi
if [[ "${1:-}" == "workflow" ]]; then
  java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.WorkflowTest "$harness_temp" "$harness_root/.."
  java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.OperationPlanningTest "$harness_temp" "$harness_root/.."
  java -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.ArtifactContractTest
  java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.SourceContractTest "$harness_root/.." "$harness_temp"
  java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.ImplementationRuntimeTest "$harness_temp" "$harness_root/.." 'small fixture success route'
  exit 0
fi
if [[ "${1:-}" == "migration-input" ]]; then
  java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.MigrationConfigLoaderTest "$harness_temp"
  java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.LiveLauncherTest "$harness_temp" "$harness_root/.." input-guards
  exit 0
fi
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.HarnessTest "$harness_temp"
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.SourceContractTest "$harness_root/.." "$harness_temp"
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.ArtifactContractTest
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.BrokerTest "$harness_temp"
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.RuntimeTest "$harness_temp" "$harness_root/.."
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.ImplementationRuntimeTest "$harness_temp" "$harness_root/.."
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.ValidationRuntimeTest "$harness_temp" "$harness_root/.."
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.LiveLauncherTest "$harness_temp" "$harness_root/.."
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.MigrationConfigLoaderTest "$harness_temp"
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.ProviderTransportTest "$harness_temp"
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.WorkflowTest "$harness_temp" "$harness_root/.."
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.OperationPlanningTest "$harness_temp" "$harness_root/.."
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.OperationImplementationTest "$harness_temp" "$harness_root/.."
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.OperationValidationTest "$harness_temp" "$harness_root/.."
