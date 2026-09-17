#!/usr/bin/env bash
set -euo pipefail

# Host launcher only: cached dependencies, fixed compilation, no project build scripts.
harness_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
harness_cache="$HOME/.m2/repository"
harness_classpath="${HARNESS_JACKSON_CLASSPATH:-$harness_cache/tools/jackson/core/jackson-core/3.1.5/jackson-core-3.1.5.jar:$harness_cache/tools/jackson/core/jackson-databind/3.1.5/jackson-databind-3.1.5.jar:$harness_cache/com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar}"
harness_classpath+=":${HARNESS_YAML_CLASSPATH:-$harness_cache/org/yaml/snakeyaml/2.6/snakeyaml-2.6.jar}"
IFS=: read -r -a harness_jars <<< "$harness_classpath"
for harness_jar in "${harness_jars[@]}"; do
  [[ -f "$harness_jar" ]] || { printf 'Missing cached harness dependency\n' >&2; exit 1; }
done
harness_temp_parent="$(cd "${TMPDIR:-/tmp}" && pwd -P)"
harness_temp="$(mktemp -d "$harness_temp_parent/controlled-live-classes.XXXXXXXX")"
trap 'rm -rf -- "$harness_temp"' EXIT
mkdir "$harness_temp/classes"
javac --release 21 -cp "$harness_classpath" -d "$harness_temp/classes" "$harness_root"/src/dev/agentic/harness/*.java
java -Djava.io.tmpdir="$harness_temp_parent" -cp "$harness_temp/classes:$harness_classpath" dev.agentic.harness.LiveLauncher "$@"
