# The platform image (2026-10-13 deployment pilot). One deployable: the Spring Modulith monolith, as Phase 0 chose.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
# The wrapper and the POM first, on their own layer: dependencies are then downloaded once and reused by every later
# build whose pom.xml has not changed. Copying the whole tree first instead re-downloaded all of Maven Central on
# every source edit, which made a release several minutes slower for no reason.
#
# mvnw carries CRLF on a Windows checkout (see the autocrlf quirk in docs/09-local-development.md); /bin/sh cannot run
# it that way and fails with an unreadable "not found". Normalised here rather than relied upon in the checkout.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY . .
RUN sed -i 's/\r$//' mvnw && ./mvnw -B -o -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache tzdata && addgroup -S app && adduser -S app -G app
# East Africa Time, not UTC. Development and every test ran in EAT, and six known sites still read the default zone
# (the UTC-vs-civil-day finding): under UTC a payment made after 21:00 local would be dated the previous day.
ENV TZ=Africa/Dar_es_Salaam \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=Africa/Dar_es_Salaam"
WORKDIR /app
COPY --from=build /workspace/target/lifeplatform.jar app.jar
COPY infra/app/docker-entrypoint.sh /app/docker-entrypoint.sh
RUN sed -i 's/\r$//' /app/docker-entrypoint.sh && chmod +x /app/docker-entrypoint.sh
USER app
# Only the application port. The management port (9090) is deliberately NOT exposed: `docker run -P` publishes every
# EXPOSEd port, which would hand /actuator/prometheus to the host. See application.yml's own note.
EXPOSE 8080
# Probes the management port, where health actually lives since actuator moved off 8080.
HEALTHCHECK --interval=15s --timeout=5s --start-period=150s --retries=5 \
  CMD wget -qO- http://localhost:9090/actuator/health/readiness | grep -q '"UP"' || exit 1
ENTRYPOINT ["/app/docker-entrypoint.sh"]
