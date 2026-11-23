# Sobe tudo para desenvolver: confere o .env, liga o banco (Docker), espera ele ficar "healthy" e roda a
# aplicacao em primeiro plano (Ctrl+C encerra). Equivalente ao "npm run dev".
# Uso: .\scripts\start.ps1
# Se o Windows bloquear scripts: powershell -ExecutionPolicy Bypass -File .\scripts\start.ps1
Set-Location (Split-Path -Parent $PSScriptRoot)

function Falhar($mensagem) {
    Write-Host $mensagem -ForegroundColor Red
    exit 1
}

if (-not (Test-Path .env)) {
    Falhar 'Falta o .env na raiz. Crie com: Copy-Item .env.example .env (e troque os valores).'
}
# So confere o tamanho: o valor da chave nunca e impresso.
$linha = Get-Content .env | Where-Object { $_ -like 'WALLET_API_KEY=*' } | Select-Object -First 1
$chave = if ($linha) { $linha.Substring('WALLET_API_KEY='.Length).Trim() } else { '' }
if ($chave.Length -lt 16) {
    Falhar 'WALLET_API_KEY ausente ou com menos de 16 caracteres no .env: a aplicacao nao sobe sem ela.'
}

# O docker escreve avisos no stderr; o cmd /c junta tudo e o PowerShell nao os trata como erro.
cmd /c 'docker info >nul 2>&1'
if ($LASTEXITCODE -ne 0) {
    Falhar 'O Docker nao esta respondendo. Abra o Docker Desktop e tente de novo.'
}

try {
    Invoke-WebRequest -Uri http://localhost:8080/actuator/health -UseBasicParsing -TimeoutSec 3 | Out-Null
    Falhar 'A porta 8080 ja esta em uso (a aplicacao ja esta rodando?). Para encerrar: .\scripts\stop.ps1'
} catch [System.Net.WebException] {
    # Sem resposta: a porta esta livre, que e o esperado.
}

Write-Host '==> Ligando o banco (docker compose up -d --no-recreate)'
# --no-recreate: nunca recria um container existente. O banco nao tem volume nomeado, entao recriar apagaria os dados
# (e o compose recria quando so muda a grafia do caminho, como c:\ e C:\ no Windows).
cmd /c 'docker compose up -d --no-recreate 2>&1'
if ($LASTEXITCODE -ne 0) { Falhar 'Nao foi possivel ligar o banco.' }

Write-Host '==> Esperando o Oracle ficar healthy (na primeira vez leva uns 2 minutos)'
$saude = ''
for ($i = 0; $i -lt 60; $i++) {
    $saude = (docker compose ps oracle --format '{{.Health}}' 2>$null | Out-String).Trim()
    if ($saude -eq 'healthy') { break }
    Start-Sleep -Seconds 5
}
if ($saude -ne 'healthy') {
    Falhar 'O banco nao ficou healthy em 5 minutos. Veja: docker compose logs oracle'
}

Write-Host '==> Banco pronto. Subindo a aplicacao em http://localhost:8080 (Ctrl+C para encerrar)'
& .\mvnw.cmd spring-boot:run
exit $LASTEXITCODE
