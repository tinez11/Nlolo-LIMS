package tz.co.nlolo.lifeplatform.communication.application;

import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import tz.co.nlolo.lifeplatform.communication.api.NotificationChannel;
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
            dispatchRepository.save(dispatch);
            processedEventRepository.save(new ProcessedEvent(eventId));
            return;
        }

        for (Reachable target : reachable) {
            // Per channel, and a failure on one must not suppress the other: an unreachable
            // phone is no reason to withhold an email that would have arrived.
            sendOne(tenantId, partyId, policyNumber, templateKey, values, target);
        }
        processedEventRepository.save(new ProcessedEvent(eventId));
    }

    private void sendOne(UUID tenantId, UUID partyId, String policyNumber, String templateKey,
                          Map<String, String> values, Reachable target) {
        NotificationDispatch dispatch =
            new NotificationDispatch(tenantId, partyId, templateKey, target.channel().name(), policyNumber);

        Optional<NotificationTemplate> template = templateRepository
            .findByTenantIdAndTemplateKeyAndChannelAndLanguage(
                tenantId, templateKey, target.channel().name(), DEFAULT_LANGUAGE);
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

    /** A channel and the address to use on it. */
    private record Reachable(NotificationChannel channel, String destination) {}
}
