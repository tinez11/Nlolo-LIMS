package tz.co.nlolo.lifeplatform.iam.infrastructure;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The decoder SecurityConfig really builds, against a stand-in identity provider: an in-process server publishing
 * discovery and a JWKS for a key this test signs with. Proves which tokens the backend accepts -- the signature alone
 * is not enough; the token must also come from the configured issuer (2026-11-19, step 0 of the Authentik migration:
 * NimbusJwtDecoder.withIssuerLocation(...).build() installs only the timestamp check).
 */
class SecurityConfigDecoderTest {

    private static HttpServer server;
    private static RSAKey key;
    private static String issuer;

    @BeforeAll
    static void startIdentityProvider() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/realms/lifeplatform";
        String discovery = "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer + "/certs\","
            + "\"id_token_signing_alg_values_supported\":[\"RS256\"]}";
        String jwks = new JWKSet(key.toPublicJWK()).toString();
        server.createContext("/realms/lifeplatform/.well-known/openid-configuration", ex -> respond(ex, discovery));
        server.createContext("/.well-known/openid-configuration/realms/lifeplatform", ex -> respond(ex, discovery));
        server.createContext("/realms/lifeplatform/certs", ex -> respond(ex, jwks));
        server.start();
    }

    @AfterAll
    static void stopIdentityProvider() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String signedToken(String tokenIssuer) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(tokenIssuer)
            .subject("00000000-0000-0000-0000-000000000001")
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Test
    void aTokenFromTheConfiguredIssuerIsAccepted() throws Exception {
        JwtDecoder decoder = SecurityConfig.lazyDecoder(issuer);

        assertThat(decoder.decode(signedToken(issuer)).getIssuer().toString()).isEqualTo(issuer);
    }

    @Test
    void aTokenNamingAnotherIssuerIsRefusedEvenWhenTheSignatureIsGood() {
        JwtDecoder decoder = SecurityConfig.lazyDecoder(issuer);

        assertThatThrownBy(() -> decoder.decode(signedToken("https://other.example/realms/lifeplatform")))
            .isInstanceOf(JwtValidationException.class)
            .hasMessageContaining("iss");
    }
}
