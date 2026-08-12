#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

mvn -f "$REPO_ROOT/java-agent/pom.xml" \
  -pl core/core-probe,core/core-jvm-attach \
  -am package -DskipTests
mvn -f "$REPO_ROOT/mcp-server/pom.xml" verify "$@"
