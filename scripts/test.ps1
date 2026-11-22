# Testes unitarios: rapidos, sem banco e sem Docker. Equivalente ao "npm test" (versao PowerShell do test.sh).
# Uso: .\scripts\test.ps1
Set-Location (Split-Path -Parent $PSScriptRoot)
& .\mvnw.cmd -B -ntp test
exit $LASTEXITCODE
