# Encerra a aplicacao (a que escuta na porta 8080), mas so se for mesmo a nossa. Com -Banco, desliga o
# banco tambem (parado, o container mantem os dados; eles so se perdem se o container for removido ou recriado).
# Uso: .\scripts\stop.ps1 [-Banco]
param([switch]$Banco)
Set-Location (Split-Path -Parent $PSScriptRoot)

$conexoes = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if (-not $conexoes) {
    Write-Host 'A aplicacao nao esta rodando.'
} else {
    foreach ($c in $conexoes) {
        $p = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $c.OwningProcess)
        if ($p.CommandLine -match 'spring-boot|WalletApiApplication|wallet-api') {
            Stop-Process -Id $c.OwningProcess -Force
            Write-Host ('Aplicacao encerrada (PID ' + $c.OwningProcess + ').')
        } else {
            Write-Host 'A porta 8080 esta ocupada por outro programa; nao encerrei.'
        }
    }
}

if ($Banco) {
    Write-Host '==> Desligando o banco'
    cmd /c 'docker compose stop oracle 2>&1'
}
