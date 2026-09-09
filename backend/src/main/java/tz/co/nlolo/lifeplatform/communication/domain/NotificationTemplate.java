package tz.co.nlolo.lifeplatform.communication.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * What one message says, for one tenant, on one channel, in one language.
 *
 * <p>Identified by a surrogate key with a unique index over
 * {@code (tenant_id, template_key, channel, language)} — see communication/V2, which had to fix
 * V1's declaration that {@code template_key} alone was the primary key while also giving the
 * table channel and language columns.
 */
@Entity
@Table(name = "notification_template", schema = "communication")
public class NotificationTemplate {

    @Id
    @Column(name = "template_id")
    private UUID templateId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "template_key", nullable = false)
    private String templateKey;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String language;

    @Column(name = "body_template", nullable = false)
    private String bodyTemplate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected NotificationTemplate() {}

    public NotificationTemplate(UUID tenantId, String templateKey, String channel, String language, String bodyTemplate) {
        this.templateId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.templateKey = templateKey;
        this.channel = channel;
        this.language = language;
        this.bodyTemplate = bodyTemplate;
    }

    /**
     * Correct the wording, without changing what the message is for.
     *
     * <p>Refuses an edit that introduces a placeholder the sender does not supply. The renderer
     * throws on an unfilled hole at send time, which is far too late: the message is already owed
     * to a customer and the only remaining outcome is a FAILED dispatch. Catching it here turns
     * that into a rejected form submission, which is somebody noticing immediately.
     *
     * <p>Dropping a placeholder is allowed. A shorter message is a legitimate editorial choice;
     * inventing a token nothing fills is not.
     */
    public void reword(String newBodyTemplate) {
        Set<String> before = TemplateRenderer.placeholdersIn(bodyTemplate);
        Set<String> after = TemplateRenderer.placeholdersIn(newBodyTemplate);
        if (!before.containsAll(after)) {
            Set<String> invented = new java.util.TreeSet<>(after);
            invented.removeAll(before);
            throw new IllegalArgumentException(
                "Template " + templateKey + " cannot introduce placeholders nothing supplies: " + invented);
        }
        this.bodyTemplate = newBodyTemplate;
    }

    public UUID getTemplateId() { return templateId; }
    public UUID getTenantId() { return tenantId; }
    public String getTemplateKey() { return templateKey; }
    public String getChannel() { return channel; }
    public String getLanguage() { return language; }
    public String getBodyTemplate() { return bodyTemplate; }
    public Instant getCreatedAt() { return createdAt; }
}
