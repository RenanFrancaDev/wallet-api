# Testes unitarios: rapidos, sem banco e sem Docker. Equivalente ao "npm test".
# Uso: .\scripts\test.ps1
Set-Location (Split-Path -Parent $PSScriptRoot)
& .\mvnw.cmd -B -ntp test
exit $LASTEXITCODE
