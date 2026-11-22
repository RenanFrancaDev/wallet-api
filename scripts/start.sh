#!/bin/bash
# Sobe tudo para desenvolver: confere o .env, liga o banco (Docker), espera ele ficar "healthy" e roda a
# aplicação em primeiro plano (Ctrl+C encerra). Equivalente ao "npm run dev".
# Uso: ./scripts/start.sh
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo "Falta o .env na raiz. Crie com: cp .env.example .env (e troque os valores)." >&2
  exit 1
fi
# Só confere o tamanho: o valor da chave nunca é impresso.
CHAVE=$(grep '^WALLET_API_KEY=' .env | head -1 | cut -d= -f2- | tr -d '\r\n' || true)
if [ "${#CHAVE}" -lt 16 ]; then
  echo "WALLET_API_KEY ausente ou com menos de 16 caracteres no .env: a aplicação não sobe sem ela." >&2
  exit 1
fi

if ! docker info > /dev/null 2>&1; then
  echo "O Docker não está respondendo. Abra o Docker Desktop e tente de novo." >&2
  exit 1
fi

if curl -s -m 3 http://localhost:8080/actuator/health > /dev/null 2>&1; then
  echo "A porta 8080 já está em uso (a aplicação já está rodando?). Para encerrar: ./scripts/stop.sh" >&2
  exit 1
fi

echo "==> Ligando o banco (docker compose up -d --no-recreate)"
# --no-recreate: nunca recria um container existente. O banco não tem volume nomeado, então recriar apagaria os dados
# (e o compose recria quando só muda a grafia do caminho, como c:\ e C:\ no Windows).
docker compose up -d --no-recreate

echo "==> Esperando o Oracle ficar healthy (na primeira vez leva uns 2 minutos)"
for i in $(seq 1 60); do
  SAUDE=$(docker compose ps oracle --format '{{.Health}}' 2>/dev/null | tr -d '\r\n' || true)
  [ "$SAUDE" = "healthy" ] && break
  sleep 5
done
if [ "${SAUDE:-}" != "healthy" ]; then
  echo "O banco não ficou healthy em 5 minutos. Veja: docker compose logs oracle" >&2
  exit 1
fi

echo "==> Banco pronto. Subindo a aplicação em http://localhost:8080 (Ctrl+C para encerrar)"
exec ./mvnw spring-boot:run
