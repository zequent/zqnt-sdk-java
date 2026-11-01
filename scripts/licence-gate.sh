#!/usr/bin/env bash
# Licence gate: lists every dependency of edge and client (all scopes, so test-only too) with
# license-maven-plugin and checks it against licences/policy.yaml. Writes THIRD_PARTY.md; with
# --check (CI) it fails instead when THIRD_PARTY.md is out of date.
#
# Extra Maven arguments (for example --settings) go in MAVEN_ARGS, which Maven reads itself.
set -euo pipefail

LICENSE_PLUGIN=org.codehaus.mojo:license-maven-plugin:2.5.0

cd "$(dirname "$0")/.."
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

mvn -B -q "$LICENSE_PLUGIN:aggregate-add-third-party" \
  -Dlicense.fileTemplate="$PWD/licences/third-party.ftl" \
  -Dlicense.outputDirectory="$WORK" \
  -Dlicense.thirdPartyFilename=dependencies.tsv \
  -Dlicense.excludedScopes=system \
  -Dlicense.useMissingFile=false

uv run --quiet --no-project --with pyyaml==6.0.3 python scripts/licence_gate.py \
  --ecosystem java "$@" "$WORK/dependencies.tsv"
