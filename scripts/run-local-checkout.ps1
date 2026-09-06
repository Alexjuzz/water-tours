$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
$settingsFile = Join-Path (Split-Path -Parent $projectDir) '.env'
if (!(Test-Path -LiteralPath $settingsFile)) { throw 'Local .env file was not found beside the project directory.' }
foreach ($line in [IO.File]::ReadAllLines($settingsFile)) {
    if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=\s*(.*)$') {
        $settingName = $matches[1]
        $settingValue = $matches[2].Trim()
        if ($settingValue -match '^"(.*)"\s*(?:#.*)?$') { $settingValue = $matches[1] }
        elseif ($settingValue -match "^'(.*)'\s*(?:#.*)?$") { $settingValue = $matches[1] }
        else { $settingValue = $settingValue -replace '\s+#.*$', '' }
        [Environment]::SetEnvironmentVariable($settingName, $settingValue, 'Process')
    }
}
Push-Location -LiteralPath $projectDir
try {
    & ./mvnw.cmd -B spring-boot:run '-Dspring-boot.run.arguments=--spring.profiles.active=redis,local-checkout --server.port=8080'
    if ($LASTEXITCODE -ne 0) { throw 'Local checkout stopped with an error.' }
} finally { Pop-Location }
