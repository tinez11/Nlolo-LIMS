#!/bin/sh
# Postgres's official image executes *.sh files in docker-entrypoint-initdb.d
# directly (unlike *.sql files, which are piped to psql with no variable
# substitution available) -- this wrapper lets APP_DB_PASSWORD flow in from
# the environment the same way infra/docker-compose.yml already sets it for
# the `app` service (SPRING_DATASOURCE_PASSWORD / APP_DB_PASSWORD).
set -e

APP_ROLE_PASSWORD="${APP_DB_PASSWORD:-devapppassword}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v app_role_password="$APP_ROLE_PASSWORD" \
  -f /docker-entrypoint-initdb.d/01-create-app-role.sql.template
