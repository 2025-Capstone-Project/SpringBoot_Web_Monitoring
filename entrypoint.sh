#!/bin/sh
set -e

# Optionally load /app/.env if present (for local/dev). Cloud Run should pass envs directly.
if [ -f /app/.env ]; then
  echo "Loading environment from /app/.env"
  set -a
  . /app/.env
  set +a
fi

# Respect Cloud Run's PORT (default 8080)
PORT="${PORT:-8080}"

exec java ${JAVA_OPTS} \
  -Djava.security.egd=file:/dev/./urandom \
  -Dserver.port=${PORT} \
  -jar /app/app.jar

