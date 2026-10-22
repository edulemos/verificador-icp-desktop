$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$releaseRoot = Join-Path $projectRoot 'release'
$jarPath = Join-Path $projectRoot 'desktop\target\verificador-desktop.jar'
$inputDirectory = Join-Path $releaseRoot 'input'
$runtimeDirectory = Join-Path $releaseRoot 'runtime'
$destination = Join-Path $releaseRoot 'portable'
if (-not (Test-Path -LiteralPath $jarPath)) { throw 'Execute build.bat antes de empacotar.' }
if (Test-Path -LiteralPath (Join-Path $destination 'VerificadorICP')) { throw 'A pasta portable\VerificadorICP já existe. Escolha outro destino antes de empacotar novamente.' }
$jlink = (Get-Command jlink -ErrorAction Stop).Source
$jpackage = (Get-Command jpackage -ErrorAction Stop).Source
New-Item -ItemType Directory -Path $inputDirectory -Force | Out-Null
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $inputDirectory 'verificador-desktop.jar') -Force
if (-not (Test-Path -LiteralPath $runtimeDirectory)) {
    & $jlink --add-modules java.base,java.desktop,java.logging,java.naming,java.xml,java.xml.crypto,java.net.http,java.management,java.instrument,java.sql,java.prefs,java.security.jgss,jdk.crypto.ec,jdk.crypto.mscapi,jdk.unsupported --strip-debug --no-header-files --no-man-pages --output $runtimeDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao gerar runtime Java.' }
}
& $jpackage --type app-image --input $inputDirectory --main-jar verificador-desktop.jar --name VerificadorICP --app-version 0.1.0 --vendor 'Verificador ICP Desktop' --dest $destination --runtime-image $runtimeDirectory --java-options '-Xmx1024m'
if ($LASTEXITCODE -ne 0) { throw 'Falha ao gerar aplicativo portátil.' }
Write-Output "Aplicativo portátil: $destination\VerificadorICP\VerificadorICP.exe"
