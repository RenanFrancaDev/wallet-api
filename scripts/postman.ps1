# Roda a colecao do Postman no terminal (newman), contra a aplicacao ja no ar. A chave vem do .env e nao e
# impressa. Versao PowerShell do postman.sh.
# Uso: .\scripts\postman.ps1   (com a aplicacao rodando: .\scripts\start.ps1 em outro terminal)
Set-Location (Split-Path -Parent $PSScriptRoot)

try {
    $saude = Invoke-RestMethod -Uri http://localhost:8080/actuator/health -TimeoutSec 3
} catch {
    $saude = $null
}
if (-not $saude -or $saude.status -ne 'UP') {
    Write-Host 'A aplicacao nao esta respondendo em http://localhost:8080. Suba com .\scripts\start.ps1 em outro terminal.' -ForegroundColor Red
    exit 1
}
$linha = Get-Content .env | Where-Object { $_ -like 'WALLET_API_KEY=*' } | Select-Object -First 1
$chave = $linha.Substring('WALLET_API_KEY='.Length).Trim()
npx --yes newman@6.2.1 run docs/postman/wallet-api.postman_collection.json `
    -e docs/postman/wallet-api.local.postman_environment.json --env-var "apiKey=$chave"
exit $LASTEXITCODE
