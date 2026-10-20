package tz.co.nlolo.lifeplatform.communication;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.communication.api.NotificationChannel;
import tz.co.nlolo.lifeplatform.communication.application.UnderwritingAlerts;
import tz.co.nlolo.lifeplatform.communication.infrastructure.NotificationSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The underwriting team is emailed when an agent's proposal arrives -- and only when someone is set to receive it. */
class UnderwritingAlertsTest {

    /** Records what would have been emailed. */
    static final class Outbox implements NotificationSender {
        final List<String[]> sent = new ArrayList<>();
        @Override public boolean supports(NotificationChannel channel) { return channel == NotificationChannel.EMAIL; }
        @Override public SendResult send(String destination, String body) {
            sent.add(new String[] {destination, body});
            return new SendResult(true, null);
        }
    }

    private static DomainEventEnvelope<Map<String, Object>> arrived(int round) {
        return DomainEventEnvelope.of("underwriting.ProposalSubmitted", UUID.randomUUID(), Map.of(
            "caseId", "c-1", "proposalNumber", "PRO-0001", "round", round, "applicantName", "Halima Prospect",
            "introducedBy", "Juma Senior"));
    }

    @Test
    void aNewProposalIsAnnouncedToEveryAddressWithALinkToTheCase() {
        Outbox outbox = new Outbox();
        new UnderwritingAlerts("uw1@insurer.tz, uw2@insurer.tz", "https://console.insurer.tz/", List.of(outbox))
            .onDomainEvent(arrived(1));

        assertThat(outbox.sent).extracting(m -> m[0]).containsExactly("uw1@insurer.tz", "uw2@insurer.tz");
        String body = outbox.sent.get(0)[1];
        assertThat(body.lines().findFirst()).hasValue("New proposal PRO-0001 from Juma Senior");
        assertThat(body).contains("Applicant: Halima Prospect").contains("https://console.insurer.tz/staff/underwriting/c-1");
    }

    @Test
    void aResubmissionSaysSoAndWhichRound() {
        Outbox outbox = new Outbox();
        new UnderwritingAlerts("uw@insurer.tz", "http://localhost:5173", List.of(outbox)).onDomainEvent(arrived(2));

        assertThat(outbox.sent.get(0)[1].lines().findFirst())
            .hasValue("Proposal PRO-0001 resubmitted (round 2) by Juma Senior");
        assertThat(outbox.sent.get(0)[1]).contains("Its history shows what changed");
    }

    @Test
    void nobodyConfiguredNothingSent_andOtherEventsAreIgnored() {
        Outbox outbox = new Outbox();
        new UnderwritingAlerts("", "http://localhost:5173", List.of(outbox)).onDomainEvent(arrived(1));
        new UnderwritingAlerts("uw@insurer.tz", "http://localhost:5173", List.of(outbox)).onDomainEvent(
            DomainEventEnvelope.of("policy.PolicyIssued", UUID.randomUUID(), Map.of()));

        assertThat(outbox.sent).isEmpty();
    }
}
