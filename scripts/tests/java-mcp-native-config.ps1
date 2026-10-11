[CmdletBinding()]
param(
    [string]$GitBash = 'C:\Program Files\Git\bin\bash.exe',
    [string]$ServerJar
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
if (-not $ServerJar) {
    $ServerJar = Join-Path $repositoryRoot 'mcp-server/application/target/mcp-java-dev-tools-server-0.1.9.jar'
}
$ServerJar = (Resolve-Path -LiteralPath $ServerJar).Path
$configOutput = & $GitBash -c 'source "$1/scripts/helpers/skills-sync-common.sh"; MCP_SERVER_JAR_INPUT="$(cygpath -u -- "$2")"; CLIENT=claude; print_mcp_config' -- $repositoryRoot $ServerJar
if ($LASTEXITCODE -ne 0) { throw 'Git Bash client configuration generation failed.' }
$configText = $configOutput -join "`n"
$configMatch = [regex]::Match($configText, '(?s)\{\s*"mcpServers".*?\n\}')
if (-not $configMatch.Success) { throw 'Generated client configuration was not found.' }
$configuration = $configMatch.Value | ConvertFrom-Json
$server = $configuration.mcpServers.'mcp-java-dev-tools'
$nativeJar = $server.args[1]
if (-not [System.IO.Path]::IsPathRooted($nativeJar) -or -not (Test-Path -LiteralPath $nativeJar)) {
    throw "Generated JAR path is not a usable native absolute path: $nativeJar"
}

# Launch directly from Windows; Git Bash argument conversion cannot repair this path.
$startInfo = New-Object System.Diagnostics.ProcessStartInfo
$startInfo.FileName = (Get-Command $server.command -ErrorAction Stop).Source
$startInfo.Arguments = '-jar "' + $nativeJar + '"'
$startInfo.WorkingDirectory = $repositoryRoot
$startInfo.UseShellExecute = $false
$startInfo.CreateNoWindow = $true
$startInfo.RedirectStandardInput = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$process = New-Object System.Diagnostics.Process
$process.StartInfo = $startInfo
$started = $false
try {
    if (-not $process.Start()) { throw 'Native Java process did not start.' }
    $started = $true
    $stderr = $process.StandardError.ReadToEndAsync()
    $initialize = @{
        jsonrpc = '2.0'; id = 1; method = 'initialize'
        params = @{
            protocolVersion = '2025-03-26'; capabilities = @{}
            clientInfo = @{ name = 'windows-installer-acceptance'; version = '1.0.0' }
        }
    } | ConvertTo-Json -Depth 5 -Compress
    $process.StandardInput.WriteLine($initialize)
    $process.StandardInput.Flush()
    $response = $process.StandardOutput.ReadLineAsync()
    if (-not $response.Wait(30000)) { throw 'Native Java initialize timed out.' }
    $message = $response.Result | ConvertFrom-Json
    if ($message.id -ne 1 -or -not $message.result.protocolVersion) {
        throw "Native Java initialize failed: $($response.Result)"
    }
    $process.StandardInput.Close()
    if (-not $process.WaitForExit(15000)) { throw 'Native Java did not shut down after stdin closed.' }
    if ($process.ExitCode -ne 0) { throw "Native Java exited $($process.ExitCode): $($stderr.Result)" }
    Write-Output "PASS: native Java initialized and exited 0 using generated JAR path $nativeJar"
} finally {
    if ($started -and -not $process.HasExited) {
        $process.Kill()
        $process.WaitForExit()
    }
    $process.Dispose()
}
