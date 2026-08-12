param(
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

$agentArgs = @(
    "-f", (Join-Path $repoRoot "java-agent/pom.xml"),
    "-pl", "core/core-probe,core/core-jvm-attach",
    "-am", "package",
    "-DskipTests"
)
& mvn @agentArgs
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$serverArgs = @(
    "-f", (Join-Path $repoRoot "mcp-server/pom.xml"),
    "verify"
)
if ($SkipTests) {
    $serverArgs += "-DskipTests"
    $serverArgs += "-DskipITs"
}
& mvn @serverArgs
exit $LASTEXITCODE
