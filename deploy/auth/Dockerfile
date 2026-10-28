# Keycloak in production mode with the login theme baked in (2026-10-13, deployment pilot §3).
#
# `kc.sh build` ahead of time, so the server starts --optimized instead of re-deriving its configuration on every
# boot. The theme is copied before that build: a folder theme is read at runtime, but baking it into the image keeps
# the server a single artefact with nothing mounted beside it.
ARG KEYCLOAK_VERSION=26.0.7

# The platform's Keycloak extensions (2026-10-28): the agents' and customers' reset by text. Built here, tests
# included, so the image never carries a jar its source does not produce.
FROM maven:3.9.9-eclipse-temurin-21 AS extensions
WORKDIR /src
COPY keycloak/extensions /src
RUN mvn -B -q package

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION} AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true
COPY keycloak/themes/lifeplatform /opt/keycloak/themes/lifeplatform
COPY --from=extensions /src/target/lifeplatform-keycloak.jar /opt/keycloak/providers/lifeplatform-keycloak.jar
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
