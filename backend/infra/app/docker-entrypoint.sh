#!/bin/sh
# Starts the platform. When EXTRA_CA_FILE names a readable certificate -- the local dress rehearsal, where Caddy signs
# with a CA of its own -- it is added to a COPY of the JVM trust store first, so the backend can fetch Keycloak's
# signing keys over https. In production the variable is unset and nothing is added.
set -e
if [ -n "${EXTRA_CA_FILE:-}" ]; then
  # Caddy writes its root certificate only after it starts, which may be after this container does.
  i=0
  while [ ! -r "$EXTRA_CA_FILE" ] && [ "$i" -lt 30 ]; do i=$((i + 1)); sleep 2; done
  if [ -r "$EXTRA_CA_FILE" ]; then
    cp "$JAVA_HOME/lib/security/cacerts" /tmp/cacerts
    keytool -importcert -noprompt -alias extra-ca -file "$EXTRA_CA_FILE" -keystore /tmp/cacerts -storepass changeit
    JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.trustStore=/tmp/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
    export JAVA_TOOL_OPTIONS
  else
    echo "EXTRA_CA_FILE=$EXTRA_CA_FILE never appeared; starting without it -- https to Keycloak will fail" >&2
  fi
fi
exec java -jar /app/app.jar
