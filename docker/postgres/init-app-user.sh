#!/bin/sh
set -eu

: "${POSTGRES_APP_USER:?POSTGRES_APP_USER is required}"
: "${POSTGRES_APP_PASSWORD:?POSTGRES_APP_PASSWORD is required}"

if [ "$POSTGRES_APP_USER" = "$POSTGRES_USER" ] || [ "$POSTGRES_APP_PASSWORD" = "$POSTGRES_PASSWORD" ]; then
  echo "Application and database administrator credentials must be distinct" >&2
  exit 1
fi

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 \
  --file=/usr/local/share/init-app-user.sql
