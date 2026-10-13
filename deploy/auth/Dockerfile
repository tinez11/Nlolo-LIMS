# Keycloak in production mode with the login theme baked in (2026-10-13, deployment pilot §3).
#
# `kc.sh build` ahead of time, so the server starts --optimized instead of re-deriving its configuration on every
# boot. The theme is copied before that build: a folder theme is read at runtime, but baking it into the image keeps
# the server a single artefact with nothing mounted beside it.
ARG KEYCLOAK_VERSION=26.0.7

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION} AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true
COPY keycloak/themes/lifeplatform /opt/keycloak/themes/lifeplatform
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
