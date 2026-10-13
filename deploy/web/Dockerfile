# Caddy serving the built console (2026-10-13). Build context is the repository root: the console's API types are
# generated from backend/api/openapi, so both halves of the repo are needed.
#
# AUTH_DOMAIN is compiled INTO the bundle -- Vite replaces import.meta.env at build time -- so this image belongs to
# one pair of hostnames. Renaming either means building again, which the runbook says.
ARG NODE_VERSION=22.12.0-alpine
ARG CADDY_VERSION=2.11.7-alpine

FROM node:${NODE_VERSION} AS build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json frontend/
RUN cd frontend && npm ci
COPY backend/api backend/api
COPY frontend frontend
ARG AUTH_DOMAIN
ENV VITE_KEYCLOAK_BASE_URL=https://${AUTH_DOMAIN} \
    VITE_KEYCLOAK_CLIENT_ID=lifeplatform-spa \
    VITE_API_BASE_URL=/api \
    VITE_REALM_PICKER=false
# Fails the build rather than quietly baking "https://undefined" into every sign-in.
RUN test -n "${AUTH_DOMAIN}" || (echo "AUTH_DOMAIN build arg is required" >&2; exit 1)
RUN cd frontend && npm run generate:api && npm run build

FROM caddy:${CADDY_VERSION}
COPY --from=build /src/frontend/dist /srv
COPY deploy/web/Caddyfile /etc/caddy/Caddyfile
