#!/bin/bash
# Roda a coleção do Postman no terminal (newman), contra a aplicação já no ar. A chave vem do .env e não é impressa.
# Uso: ./scripts/postman.sh   (com a aplicação rodando: ./scripts/start.sh em outro terminal)
set -uo pipefail
cd "$(dirname "$0")/.."

if ! curl -s -m 3 http://localhost:8080/actuator/health | grep -q UP; then
  echo "A aplicação não está respondendo em http://localhost:8080. Suba com ./scripts/start.sh em outro terminal." >&2
  exit 1
fi
CHAVE=$(grep '^WALLET_API_KEY=' .env | head -1 | cut -d= -f2- | tr -d '\r\n')
exec npx --yes newman@6.2.1 run docs/postman/wallet-api.postman_collection.json \
  -e docs/postman/wallet-api.local.postman_environment.json --env-var "apiKey=$CHAVE"
