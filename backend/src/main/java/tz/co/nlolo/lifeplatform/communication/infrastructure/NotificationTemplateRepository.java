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

    /** The console's list, ordered so the same key's channels and languages sit together. */
    List<NotificationTemplate> findByTenantIdOrderByTemplateKeyAscChannelAscLanguageAsc(UUID tenantId);
}
