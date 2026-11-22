#!/bin/bash
# O que o CI roda: formato, lint, compilação estrita, unitários, integração (Oracle real no Docker) e o piso de
# cobertura. Para o banco de desenvolvimento antes (o Docker não tem memória para dois Oracles) e o religa no fim,
# mesmo se algo falhar. Leva uns 4 minutos.
# Uso: ./scripts/verify.sh
set -uo pipefail
cd "$(dirname "$0")/.."

RELIGAR=0
if [ -n "$(docker compose ps oracle --status running -q 2>/dev/null)" ]; then
  echo "==> Parando o banco de desenvolvimento (para a memória do Docker)"
  docker compose stop oracle
  RELIGAR=1
fi
religar() {
  if [ "$RELIGAR" = "1" ]; then
    echo "==> Religando o banco de desenvolvimento"
    docker compose up -d --no-recreate oracle
  fi
}
trap religar EXIT

./mvnw -B -ntp verify
