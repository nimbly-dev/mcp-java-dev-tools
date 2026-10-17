[CmdletBinding()]
param(
    [string]$GitBash = 'C:\Program Files\Git\bin\bash.exe',
    [string]$ServerJar,
    [string]$WorkspaceRoot,
    [string]$WorkingDirectory
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
if (-not $ServerJar) {
    $ServerJar = Join-Path $repositoryRoot 'mcp-server/application/target/mcp-java-dev-tools-server-0.1.9.jar'
}
$ServerJar = (Resolve-Path -LiteralPath $ServerJar).Path
if (-not $WorkspaceRoot) { $WorkspaceRoot = $repositoryRoot }
if (-not $WorkingDirectory) { $WorkingDirectory = Split-Path -Parent $ServerJar }
$WorkspaceRoot = (Resolve-Path -LiteralPath $WorkspaceRoot).Path
$configOutput = & $GitBash -c 'source "$1/scripts/helpers/skills-sync-common.sh"; MCP_SERVER_JAR_INPUT="$(cygpath -u -- "$2")"; MCP_WORKSPACE_ROOT_INPUT="$(cygpath -u -- "$3")"; CLIENT=claude; print_mcp_config' -- $repositoryRoot $ServerJar $WorkspaceRoot
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
$startInfo.Arguments = ($server.args | ForEach-Object { '"' + $_ + '"' }) -join ' '
$startInfo.WorkingDirectory = $WorkingDirectory
$startInfo.UseShellExecute = $false
$startInfo.CreateNoWindow = $true
$startInfo.RedirectStandardInput = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
# Launch with only Java and Windows system utilities available; no Node or checkout paths.
$startInfo.EnvironmentVariables['PATH'] = (Split-Path -Parent $startInfo.FileName) + ';' + $env:SystemRoot + '\System32'
foreach ($key in @($startInfo.EnvironmentVariables.Keys)) {
    if ($key -match '^(MCP_|MCPJVM_|SPRING_)' -or $key -in @('INIT_CWD', 'PWD', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')) {
        $startInfo.EnvironmentVariables.Remove($key)
    }
}
$process = New-Object System.Diagnostics.Process
$process.StartInfo = $startInfo
$started = $false
function Invoke-McpRequest($id, $method, $parameters) {
    $request = @{ jsonrpc = '2.0'; id = $id; method = $method; params = $parameters }
    $process.StandardInput.WriteLine(($request | ConvertTo-Json -Depth 20 -Compress))
    $process.StandardInput.Flush()
    do {
        $line = $process.StandardOutput.ReadLineAsync()
        if (-not $line.Wait(30000)) { throw "MCP request $id timed out." }
        if ($null -eq $line.Result) { throw "MCP stdout closed before response $id." }
        $message = $line.Result | ConvertFrom-Json
        if ($message.jsonrpc -ne '2.0') { throw 'Non-protocol stdout detected.' }
    } while ($message.id -ne $id)
    if ($message.error) { throw ($message.error | ConvertTo-Json -Compress) }
    return $message.result
}
function Invoke-McpTool($id, $name, $arguments) {
    $result = Invoke-McpRequest $id 'tools/call' @{ name = $name; arguments = $arguments }
    $payload = $result.structuredContent
    if (-not $payload) { $payload = $result.content[0].text | ConvertFrom-Json }
    if ($result.isError -or $payload.status -ne 'succeeded') {
        throw ($payload | ConvertTo-Json -Depth 10 -Compress)
    }
    return $payload
}
try {
    if (-not $process.Start()) { throw 'Native Java process did not start.' }
    $started = $true
    $stderr = $process.StandardError.ReadToEndAsync()
    $initialize = Invoke-McpRequest 1 'initialize' @{
            protocolVersion = '2025-03-26'; capabilities = @{}
            clientInfo = @{ name = 'windows-installer-acceptance'; version = '1.0.0' }
    }
    if ($initialize.protocolVersion -ne '2025-03-26') { throw 'MCP initialization failed.' }
    $process.StandardInput.WriteLine('{"jsonrpc":"2.0","method":"notifications/initialized"}')
    $tools = Invoke-McpRequest 2 'tools/list' @{}
    $expected = @('operation_catalog', 'operation_describe', 'operation_execute')
    if (@($tools.tools).Count -ne 3 -or (Compare-Object $expected @($tools.tools.name))) {
        throw 'Expected exactly the three CDE Tools.'
    }
    $catalog = Invoke-McpTool 3 'operation_catalog' @{ api = 'jvm_lifecycle' }
    if (@($catalog.entries).Count -ne 3) { throw 'Expected three lifecycle operations.' }
    $description = Invoke-McpTool 4 'operation_describe' @{ operationId = 'jvm_lifecycle.attach' }
    if (-not $description.operation.safety.confirmationRequired) { throw 'Attach confirmation contract missing.' }
    $discovery = Invoke-McpTool 5 'operation_execute' @{ operationId = 'jvm_lifecycle.list_jvms'; arguments = @{} }
    if ($discovery.result.reasonCode -ne 'jvm_discovery_unverified') { throw 'Packaged JVM discovery failed.' }
    $statusResource = Invoke-McpRequest 6 'resources/read' @{ uri = 'mcp-java-dev-tools://status' }
    $status = $statusResource.contents[0].text | ConvertFrom-Json
    if ([IO.Path]::GetFullPath($status.workspaceRoot) -ne $WorkspaceRoot) { throw 'Generated workspace was not bound.' }
    if ($status.workspaceRootSource -ne 'arg') { throw 'Workspace must come from the generated argument.' }
    $process.StandardInput.Close()
    $remainingStdout = $process.StandardOutput.ReadToEndAsync()
    if (-not $process.WaitForExit(15000)) { throw 'Native Java did not shut down after stdin closed.' }
    if ($process.ExitCode -ne 0) { throw "Native Java exited $($process.ExitCode): $($stderr.Result)" }
    foreach ($line in ($remainingStdout.Result -split "`n" | Where-Object { $_.Trim() })) {
        if (($line | ConvertFrom-Json).jsonrpc -ne '2.0') { throw 'Non-protocol shutdown stdout detected.' }
    }
    Write-Output 'PASS: generated native Java command; explicit workspace; CDE catalog/describe/execute; protocol-only stdout; stdin-close exit 0; Node absent from PATH'
} finally {
    if ($started -and -not $process.HasExited) {
        $process.Kill()
        $process.WaitForExit()
    }
    $process.Dispose()
}
