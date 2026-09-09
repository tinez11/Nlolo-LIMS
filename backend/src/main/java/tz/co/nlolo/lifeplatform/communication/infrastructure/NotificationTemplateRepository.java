package tz.co.nlolo.lifeplatform.communication.infrastructure;

import tz.co.nlolo.lifeplatform.communication.domain.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {

    /** The send path's lookup: the four columns V2 made the real identity. */
    Optional<NotificationTemplate> findByTenantIdAndTemplateKeyAndChannelAndLanguage(
        UUID tenantId, String templateKey, String channel, String language);

    /**
     * This tenant's own rows plus the platform defaults, in one read.
     *
     * <p>The caller picks a winner per (key, channel, language) — a tenant's own row overrides the
     * default. Both are fetched together rather than in two queries because the console needs the
     * whole effective set to render, and a tenant with no overrides at all (the common case, and
     * every new tenant) must still see sixteen templates rather than an empty screen.
     */
    List<NotificationTemplate> findByTenantIdInOrderByTemplateKeyAscChannelAscLanguageAsc(
        List<UUID> tenantIds);
}
