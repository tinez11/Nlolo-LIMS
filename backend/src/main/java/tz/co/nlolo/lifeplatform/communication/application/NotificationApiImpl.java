package tz.co.nlolo.lifeplatform.communication.application;

import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import tz.co.nlolo.lifeplatform.communication.api.NotificationChannel;
import tz.co.nlolo.lifeplatform.communication.api.NotificationDispatchView;
import tz.co.nlolo.lifeplatform.communication.api.NotificationTemplateNotFoundException;
import tz.co.nlolo.lifeplatform.communication.api.NotificationTemplateView;
import tz.co.nlolo.lifeplatform.communication.domain.NotificationDispatch;
import tz.co.nlolo.lifeplatform.communication.domain.NotificationTemplate;
import tz.co.nlolo.lifeplatform.communication.domain.ProcessedEvent;
import tz.co.nlolo.lifeplatform.communication.domain.TemplateRenderer;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationDispatchRepository;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationSender;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationTemplateRepository;
import tz.co.nlolo.lifeplatform.communication.infrastructure.ProcessedEventRepository;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class NotificationApiImpl implements NotificationApi {

    private static final Logger log = LoggerFactory.getLogger(NotificationApiImpl.class);

    /**
     * Swahili, for every customer, for now.
     *
     * <p>The schema carries a language per template and V1's own comment calls {@code sw} the
     * customer-facing default. What it does not carry is a language per PARTY, so there is
     * nothing to choose from — and picking English because it is the language this code is
     * written in would be the wrong default for the customers this platform serves. When party
     * gains a language preference, this constant is the single place that changes.
     */
    private static final String DEFAULT_LANGUAGE = "sw";

    /**
     * The tenant that owns the platform's default wording.
     *
     * <p>The nil uuid, because it cannot collide with a real tenant and reads unmistakably as
     * "not a tenant". A tenant with no row of its own for a message falls back to this one, so a
     * newly provisioned tenant can tell its customers things on day one instead of recording a
     * FAILED dispatch per notification until somebody runs SQL. See communication/V8.
     */
    private static final UUID PLATFORM_DEFAULT_TENANT = new UUID(0L, 0L);

    private final PartyApi partyApi;
    private final NotificationTemplateRepository templateRepository;
    private final NotificationDispatchRepository dispatchRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final List<NotificationSender> senders;

    public NotificationApiImpl(PartyApi partyApi,
                                NotificationTemplateRepository templateRepository,
                                NotificationDispatchRepository dispatchRepository,
                                ProcessedEventRepository processedEventRepository,
                                List<NotificationSender> senders) {
        this.partyApi = partyApi;
        this.templateRepository = templateRepository;
        this.dispatchRepository = dispatchRepository;
        this.processedEventRepository = processedEventRepository;
        this.senders = senders;
    }

    @Override
    @Transactional
    public void notify(UUID eventId, UUID partyId, String policyNumber, String templateKey,
                        Map<String, String> values) {
        UUID tenantId = TenantContext.get();

        // Dedup first, before anything observable happens. The ProcessedEvent row is written in
        // THIS transaction alongside the dispatches, so a crash between sending and recording
        // cannot leave the event marked done with nothing sent.
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Event {} already notified; sending nothing", eventId);
            return;
        }

        PartyDetailView party = partyApi.getPartyDetail(partyId);
        List<Reachable> reachable = reachableChannels(party);

        if (reachable.isEmpty()) {
            // A real state, not an error path: ContactInfo pattern-constrains a phone and
            // @Emails an address but requires neither, so a party can have no way to be reached
            // at all. Recorded rather than dropped -- somebody owed a message and unreachable is
            // an operational fact for the desk to chase, and silence would hide it completely.
            NotificationDispatch dispatch =
                new NotificationDispatch(tenantId, partyId, templateKey, NotificationChannel.SMS.name(), policyNumber);
            dispatch.markFailed("No phone number or email address on file for this party");
            // Still worded and kept: the portal inbox is the one place an unreachable customer can read it.
            dispatch.recordBody(eventId, findEffectiveTemplate(tenantId, templateKey, NotificationChannel.SMS.name())
                .map(t -> {
                    try {
                        return TemplateRenderer.render(t.getBodyTemplate(), values);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .orElse(null));
            dispatchRepository.save(dispatch);
            processedEventRepository.save(new ProcessedEvent(eventId));
            return;
        }

        for (Reachable target : reachable) {
            // Per channel, and a failure on one must not suppress the other: an unreachable
            // phone is no reason to withhold an email that would have arrived.
            sendOne(eventId, tenantId, partyId, policyNumber, templateKey, values, target);
        }
        processedEventRepository.save(new ProcessedEvent(eventId));
    }

    private void sendOne(UUID eventId, UUID tenantId, UUID partyId, String policyNumber, String templateKey,
                          Map<String, String> values, Reachable target) {
        NotificationDispatch dispatch =
            new NotificationDispatch(tenantId, partyId, templateKey, target.channel().name(), policyNumber);
        dispatch.recordBody(eventId, null);

        Optional<NotificationTemplate> template =
            findEffectiveTemplate(tenantId, templateKey, target.channel().name());
        if (template.isEmpty()) {
            // A template nobody seeded. Recorded rather than thrown, for the same reason as
            // everything else here -- but it is a deployment fault, not a customer's problem, so
            // the row says exactly which lookup missed.
            dispatch.markFailed("No " + target.channel() + "/" + DEFAULT_LANGUAGE
                + " template seeded for " + templateKey);
            dispatchRepository.save(dispatch);
            return;
        }

        String body;
        try {
            body = TemplateRenderer.render(template.get().getBodyTemplate(), values);
        } catch (IllegalArgumentException e) {
            // A hole in the message. Never sent half-rendered: see TemplateRenderer.
            dispatch.markFailed(e.getMessage());
            dispatchRepository.save(dispatch);
            return;
        }
        // What the customer was told, kept as told: the template may be reworded tomorrow.
        dispatch.recordBody(eventId, body);

        NotificationSender.SendResult result = senderFor(target.channel())
            .map(sender -> sender.send(target.destination(), body))
            .orElseGet(() -> NotificationSender.SendResult.failed(
                "No sender configured for " + target.channel()));

        if (result.sent()) {
            dispatch.markSent();
        } else {
            dispatch.markFailed(result.detail());
        }
        dispatchRepository.save(dispatch);
    }

    /**
     * Every channel this party can actually be reached on.
     *
     * <p>Both, when both are on file. Email is additional rather than alternative: an address is
     * a second chance at telling somebody their cover has not started, not a reason to skip the
     * channel most customers here actually read.
     */
    private static List<Reachable> reachableChannels(PartyDetailView party) {
        List<Reachable> reachable = new ArrayList<>();
        if (hasText(party.phoneNumber())) {
            reachable.add(new Reachable(NotificationChannel.SMS, party.phoneNumber().trim()));
        }
        if (hasText(party.email())) {
            reachable.add(new Reachable(NotificationChannel.EMAIL, party.email().trim()));
        }
        return reachable;
    }

    private Optional<NotificationSender> senderFor(NotificationChannel channel) {
        return senders.stream().filter(s -> s.supports(channel)).findFirst();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * This tenant's wording for a message, falling back to the platform default.
     *
     * <p>The fallback is what makes a newly provisioned tenant usable: without it every send
     * records FAILED until somebody hand-writes sixteen rows in SQL.
     */
    private Optional<NotificationTemplate> findEffectiveTemplate(UUID tenantId, String templateKey,
                                                                  String channel) {
        return templateRepository
            .findByTenantIdAndTemplateKeyAndChannelAndLanguage(tenantId, templateKey, channel, DEFAULT_LANGUAGE)
            .or(() -> templateRepository.findByTenantIdAndTemplateKeyAndChannelAndLanguage(
                PLATFORM_DEFAULT_TENANT, templateKey, channel, DEFAULT_LANGUAGE));
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationTemplateView> listTemplates() {
        UUID tenantId = TenantContext.get();
        // The EFFECTIVE set: what this tenant's customers would actually receive. A tenant's own
        // row beats the default for the same key/channel/language, and everything it has not
        // overridden still shows -- so the console lists sixteen templates for a tenant that has
        // customised none, which is every tenant on day one.
        Map<String, NotificationTemplateView> effective = new LinkedHashMap<>();
        for (NotificationTemplate template : templateRepository
                .findByTenantIdInOrderByTemplateKeyAscChannelAscLanguageAsc(
                    List.of(PLATFORM_DEFAULT_TENANT, tenantId))) {
            String identity = template.getTemplateKey() + '|' + template.getChannel() + '|' + template.getLanguage();
            // The tenant's own row wins. Defaults are listed first by the IN clause only by
            // accident of ordering, so the override is explicit rather than positional.
            if (template.getTenantId().equals(tenantId) || !effective.containsKey(identity)) {
                effective.put(identity, toView(template));
            }
        }
        return List.copyOf(effective.values());
    }

    @Override
    @Transactional
    public NotificationTemplateView rewordTemplate(UUID templateId, String bodyTemplate) {
        UUID tenantId = TenantContext.get();
        NotificationTemplate template = templateRepository.findById(templateId)
            // Tenant-checked rather than trusted from the path: a template id from another tenant
            // must read as "no such template", not as somebody else's message text. A platform
            // default is legitimately visible to everyone, so it passes this filter.
            .filter(t -> t.getTenantId().equals(tenantId) || t.getTenantId().equals(PLATFORM_DEFAULT_TENANT))
            .orElseThrow(() -> new NotificationTemplateNotFoundException(templateId));

        if (template.getTenantId().equals(PLATFORM_DEFAULT_TENANT)) {
            // COPY ON WRITE, and this is the important line in the method. Editing the default in
            // place would rewrite the wording every OTHER tenant receives -- a cross-tenant write
            // wearing an edit's clothes. Instead the tenant gets its own row, which then wins the
            // lookup for it alone and leaves everybody else on the default.
            //
            // The database refuses the alternative independently: V8's WITH CHECK pins every
            // written row to the caller's own tenant. This is the half that makes the refusal
            // unnecessary rather than merely survivable.
            NotificationTemplate override = new NotificationTemplate(tenantId, template.getTemplateKey(),
                template.getChannel(), template.getLanguage(), template.getBodyTemplate());
            override.reword(bodyTemplate);
            return toView(templateRepository.save(override));
        }

        template.reword(bodyTemplate);
        return toView(templateRepository.save(template));
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationDispatchView> listDispatches(UUID partyId, String policyNumber, String status) {
        UUID tenantId = TenantContext.get();
        // Narrowest indexed read first, then filter in memory. Every branch is already scoped to
        // one policy or one party, so the in-memory pass is over a handful of rows -- not the
        // whole outbox.
        List<NotificationDispatch> rows;
        if (policyNumber != null && !policyNumber.isBlank()) {
            rows = dispatchRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtDesc(tenantId, policyNumber);
        } else if (partyId != null) {
            rows = dispatchRepository.findByTenantIdAndPartyIdOrderByCreatedAtDesc(tenantId, partyId);
        } else {
            rows = dispatchRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        }
        return rows.stream()
            .filter(row -> status == null || status.isBlank() || status.equals(row.getStatus()))
            .map(NotificationApiImpl::toView)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<tz.co.nlolo.lifeplatform.communication.api.InboxMessageView> inbox(UUID partyId) {
        Map<Object, List<NotificationDispatch>> byMessage = new LinkedHashMap<>();
        for (NotificationDispatch row : dispatchRepository.findByTenantIdAndPartyIdOrderByCreatedAtDesc(TenantContext.get(), partyId)) {
            // Rows from before V15 carry no event: each stands alone, and only the ones that went are worth showing.
            if (row.getEventId() == null && !"SENT".equals(row.getStatus())) {
                continue;
            }
            byMessage.computeIfAbsent(row.getEventId() != null ? row.getEventId() : row.getDispatchId(),
                k -> new ArrayList<>()).add(row);
        }
        return byMessage.values().stream()
            .filter(copies -> copies.stream().anyMatch(c -> c.getBody() != null || "SENT".equals(c.getStatus())))
            .map(NotificationApiImpl::toInboxView)
            .toList();
    }

    @Override
    @Transactional
    public tz.co.nlolo.lifeplatform.communication.api.InboxMessageView markRead(UUID partyId, UUID messageId) {
        UUID tenantId = TenantContext.get();
        NotificationDispatch named = dispatchRepository.findById(messageId)
            .filter(d -> tenantId.equals(d.getTenantId()) && partyId.equals(d.getPartyId()))
            .orElseThrow(() -> new tz.co.nlolo.lifeplatform.communication.api.InboxMessageNotFoundException(messageId));
        List<NotificationDispatch> copies = named.getEventId() == null ? List.of(named)
            : dispatchRepository.findByTenantIdAndPartyIdOrderByCreatedAtDesc(tenantId, partyId).stream()
                .filter(d -> named.getEventId().equals(d.getEventId())).toList();
        copies.forEach(NotificationDispatch::markRead);
        dispatchRepository.saveAll(copies);
        return toInboxView(copies);
    }

    /** One message from the copies one event sent: the first with text names it, and it is read once any copy is. */
    private static tz.co.nlolo.lifeplatform.communication.api.InboxMessageView toInboxView(List<NotificationDispatch> copies) {
        NotificationDispatch lead = copies.stream().filter(c -> c.getBody() != null).findFirst().orElse(copies.get(0));
        return new tz.co.nlolo.lifeplatform.communication.api.InboxMessageView(lead.getDispatchId(), lead.getTemplateKey(),
            lead.getPolicyNumber(), lead.getBody(), lead.getCreatedAt(),
            copies.stream().anyMatch(c -> c.getReadAt() != null));
    }

    private static NotificationTemplateView toView(NotificationTemplate template) {
        return new NotificationTemplateView(template.getTemplateId(), template.getTemplateKey(),
            template.getChannel(), template.getLanguage(), template.getBodyTemplate(),
            List.copyOf(TemplateRenderer.placeholdersIn(template.getBodyTemplate())));
    }

    private static NotificationDispatchView toView(NotificationDispatch dispatch) {
        return new NotificationDispatchView(dispatch.getDispatchId(), dispatch.getPartyId(),
            dispatch.getPolicyNumber(), dispatch.getTemplateKey(), dispatch.getChannel(),
            dispatch.getStatus(), dispatch.getFailureReason(), dispatch.getDispatchedAt(),
            dispatch.getCreatedAt());
    }

    /** A channel and the address to use on it. */
    private record Reachable(NotificationChannel channel, String destination) {}
}
