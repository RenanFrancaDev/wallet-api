#!/bin/bash
# Encerra a aplicação (a que escuta na porta 8080), mas só se for mesmo a nossa. Com --banco, desliga o
# banco também (parado, o container mantém os dados; eles só se perdem se o container for removido ou recriado).
# Uso: ./scripts/stop.sh [--banco]
set -uo pipefail
cd "$(dirname "$0")/.."

encerrar_pid() {
  local pid="$1" linha="$2"
  if echo "$linha" | grep -Eq 'spring-boot|WalletApiApplication|wallet-api'; then
    kill "$pid" 2>/dev/null && echo "Aplicação encerrada (PID $pid)."
  else
    echo "A porta 8080 está ocupada por outro programa; não encerrei."
  fi
}

if command -v powershell.exe > /dev/null 2>&1; then
  # Git Bash no Windows: o PID do Windows não é o do Bash, então a consulta vai pelo PowerShell.
  # O PowerShell devolve só uma palavra (ASCII), e a mensagem é escrita aqui, para os acentos não se perderem.
  RESULTADO=$(powershell.exe -NoProfile -Command "\$c = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue; if (-not \$c) { 'LIVRE' } else { \$c | ForEach-Object { \$p = Get-CimInstance Win32_Process -Filter ('ProcessId=' + \$_.OwningProcess); if (\$p.CommandLine -match 'spring-boot|WalletApiApplication|wallet-api') { Stop-Process -Id \$_.OwningProcess -Force; 'ENCERRADA' } else { 'OUTRO' } } }" | tr -d '')
  case "$RESULTADO" in
    LIVRE) echo "A aplicação não está rodando." ;;
    ENCERRADA) echo "Aplicação encerrada." ;;
    *) echo "A porta 8080 está ocupada por outro programa; não encerrei." ;;
  esac
else
  PID=$(lsof -ti tcp:8080 -sTCP:LISTEN 2>/dev/null | head -1 || true)
  if [ -z "$PID" ]; then
    echo "A aplicação não está rodando."
  else
    encerrar_pid "$PID" "$(ps -o args= -p "$PID")"
  fi
fi

if [ "${1:-}" = "--banco" ]; then
  echo "==> Desligando o banco"
  docker compose stop oracle
fi
