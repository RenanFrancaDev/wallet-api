# O que o CI roda: formato, lint, compilacao estrita, unitarios, integracao (Oracle real no Docker) e o piso de
# cobertura. Para o banco de desenvolvimento antes (o Docker nao tem memoria para dois Oracles) e o religa no fim,
# mesmo se algo falhar. Leva uns 4 minutos. Versao PowerShell do verify.sh.
# Uso: .\scripts\verify.ps1
Set-Location (Split-Path -Parent $PSScriptRoot)

$religar = $false
$rodando = (docker compose ps oracle --status running -q 2>$null | Out-String).Trim()
if ($rodando) {
    Write-Host '==> Parando o banco de desenvolvimento (para a memoria do Docker)'
    cmd /c 'docker compose stop oracle 2>&1'
    $religar = $true
}
try {
    & .\mvnw.cmd -B -ntp verify
    $codigo = $LASTEXITCODE
} finally {
    if ($religar) {
        Write-Host '==> Religando o banco de desenvolvimento'
        cmd /c 'docker compose up -d --no-recreate oracle 2>&1'
    }
}
exit $codigo
