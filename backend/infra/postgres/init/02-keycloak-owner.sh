#!/bin/bash
# Gives Keycloak's database its own login (2026-10-13, deployment pilot §2). Until now Keycloak connected as the
# postgres superuser, so a Keycloak compromise owned the business data too. Only when KEYCLOAK_DB_PASSWORD is set --
# the dev stack leaves it unset and keeps the old arrangement. Runs once, on an empty data volume, after 01-*.
set -e

if [ -z "${KEYCLOAK_DB_PASSWORD:-}" ]; then
  echo "02-keycloak-owner: KEYCLOAK_DB_PASSWORD not set, keycloak keeps the postgres login"
  exit 0
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v kc_password="$KEYCLOAK_DB_PASSWORD" \
  -c "CREATE ROLE keycloak LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'kc_password'" \
  -c "ALTER DATABASE keycloak OWNER TO keycloak"

# Keycloak creates its own tables in public, so it must own that schema in its own database.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname keycloak \
  -c "ALTER SCHEMA public OWNER TO keycloak"

echo "02-keycloak-owner: keycloak database now owned by the keycloak role"
