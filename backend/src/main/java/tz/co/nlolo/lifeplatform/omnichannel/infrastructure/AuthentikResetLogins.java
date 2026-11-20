package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.AuthentikClient;
import tz.co.nlolo.lifeplatform.omnichannel.application.ResetLogins;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/**
 * The logins /reset may reach, in Authentik (2026-11-19, Authentik cut-over ticket 07): an open login whose username is
 * the phone, in the agent or customer workspace and never in staff, with the party it belongs to. The backend's service
 * account only sees logins it created -- the agents' and customers' it opened -- which is all a reset should reach.
 *
 * <p>The recovery link comes back built with the address the backend calls Authentik on (internal in production); the
 * browser is given the same link on Authentik's public address.
 */
@Component
public class AuthentikResetLogins implements ResetLogins {

    private static final String LIFETIME = "minutes=15";

    private final AuthentikClient authentik;
    private final URI publicUrl;

    public AuthentikResetLogins(AuthentikClient authentik, @Value("${app.authentik.public-url}") String publicUrl) {
        this.authentik = authentik;
        this.publicUrl = URI.create(publicUrl);
    }

    @Override
    public Optional<ResetLogin> find(String phone) {
        return authentik.findUserByUsername(phone)
            .filter(AuthentikClient.User::active)
            .filter(u -> !u.groups().contains("staff"))
            .filter(u -> u.groups().contains("agent") || u.groups().contains("customer"))
            .filter(u -> u.attributes().get("party_id") != null)
            .map(u -> new ResetLogin(u.uuid(), UUID.fromString(String.valueOf(u.attributes().get("party_id")))));
    }

    @Override
    public String recoveryLink(UUID userId) {
        URI link = URI.create(authentik.recoveryLink(authentik.user(userId).pk(), LIFETIME));
        String rest = link.getRawPath() + (link.getRawQuery() == null ? "" : "?" + link.getRawQuery());
        return publicUrl.getScheme() + "://" + publicUrl.getRawAuthority() + rest;
    }
}
