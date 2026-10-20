package tz.co.nlolo.lifeplatform.communication.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.communication.api.NotificationChannel;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationSender;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Tells the underwriting team when an agent's proposal arrives (2026-10-20): a first submission from a quotation, or a
 * resubmission after a return or a postponement. Email to the addresses in {@code communication.underwriting-alerts};
 * none configured, nothing sent.
 *
 * <p>Staff are not parties, so this is not a customer notification: no dispatch row, no inbox, no template -- a plain
 * message to a team address, sent after the proposal's own transaction has committed, and a failure only logged. The
 * queue is the record; this is the knock on the door. SMS is never used here.
 */
@Component
public class UnderwritingAlerts {

    private static final Logger log = LoggerFactory.getLogger(UnderwritingAlerts.class);
    static final String EVENT = "underwriting.ProposalSubmitted";

    private final List<String> recipients;
    private final String consoleUrl;
    private final NotificationSender email;

    public UnderwritingAlerts(@Value("${communication.underwriting-alerts:}") String recipients,
                              @Value("${communication.console-url:http://localhost:5173}") String consoleUrl,
                              List<NotificationSender> senders) {
        this.recipients = Arrays.stream(recipients.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.consoleUrl = consoleUrl.endsWith("/") ? consoleUrl.substring(0, consoleUrl.length() - 1) : consoleUrl;
        this.email = senders.stream().filter(s -> s.supports(NotificationChannel.EMAIL)).findFirst().orElse(null);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!EVENT.equals(envelope.eventType()) || recipients.isEmpty() || email == null) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) envelope.payload();
            String body = message(p, consoleUrl);
            for (String to : recipients) {
                NotificationSender.SendResult result = email.send(to, body);
                if (!result.sent()) log.warn("Underwriting alert not sent to {}: {}", to, result.detail());
            }
        } catch (RuntimeException e) {
            // After the commit: the proposal is in the queue whatever happens here.
            log.error("Underwriting alert failed for event {}", envelope.eventId(), e);
        }
    }

    /** The first line is the email's subject (EmailSenderAdapter), so it carries what the team scans for. */
    static String message(Map<String, Object> p, String consoleUrl) {
        int round = p.get("round") instanceof Number n ? n.intValue() : 1;
        String proposal = String.valueOf(p.getOrDefault("proposalNumber", "a proposal"));
        String agent = blankToNull(p.get("introducedBy"));
        String applicant = blankToNull(p.get("applicantName"));
        String headline = round > 1
            ? "Proposal " + proposal + " resubmitted (round " + round + ")" + (agent != null ? " by " + agent : "")
            : "New proposal " + proposal + (agent != null ? " from " + agent : "");
        StringBuilder body = new StringBuilder(headline).append("\n\n");
        if (applicant != null) body.append("Applicant: ").append(applicant).append('\n');
        if (agent != null) body.append("Agent: ").append(agent).append('\n');
        body.append(round > 1
            ? "The agent corrected it and sent it back. Its history shows what changed.\n"
            : "Submitted from the agent's quotation. It is waiting in the underwriting queue.\n");
        body.append('\n').append("Open it: ").append(consoleUrl).append("/staff/underwriting/").append(p.get("caseId"))
            .append('\n');
        return body.toString();
    }

    private static String blankToNull(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }
}
