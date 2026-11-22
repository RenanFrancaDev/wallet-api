#!/bin/bash
# Testes unitários: rápidos, sem banco e sem Docker. Equivalente ao "npm test".
# Uso: ./scripts/test.sh
set -euo pipefail
cd "$(dirname "$0")/.."
exec ./mvnw -B -ntp test
