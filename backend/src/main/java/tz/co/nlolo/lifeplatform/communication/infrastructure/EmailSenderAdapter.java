package tz.co.nlolo.lifeplatform.communication.infrastructure;

import tz.co.nlolo.lifeplatform.communication.api.NotificationChannel;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Email, for the customers who have an address on file.
 *
 * <p>Second to SMS rather than instead of it. Most customers here are reachable by phone and a
 * minority by email, so an address is a second chance at telling somebody their cover has not
 * started — not a reason to skip the channel they actually read.
 *
 * <p>The subject line is derived from the first line of the rendered body rather than carried as
 * a separate template field. {@code notification_template} has one {@code body_template} column
 * and no subject, and adding one would mean every SMS template carrying a field SMS has no
 * concept of. A message short enough to be an SMS is short enough that its opening clause is a
 * fair subject.
 *
 * <p>Plain text, not HTML. These are four short operational messages about whether somebody is
 * insured; HTML would add a rendering surface and a spam-classifier problem for no gain.
 */
@Component
public class EmailSenderAdapter implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSenderAdapter.class);
    private static final String COUNTER = "lifeplatform_communication_email_requests_total";
    /** Long enough to be a useful subject, short enough not to be the whole message twice. */
    private static final int MAX_SUBJECT_LENGTH = 78;

    private final JavaMailSender mailSender;
    private final MeterRegistry meterRegistry;
    private final String fromAddress;

    public EmailSenderAdapter(JavaMailSender mailSender, MeterRegistry meterRegistry,
                               @Value("${communication.from-address}") String fromAddress) {
        this.mailSender = mailSender;
        this.meterRegistry = meterRegistry;
        this.fromAddress = fromAddress;
    }

    @Override
    public boolean supports(NotificationChannel channel) {
        return channel == NotificationChannel.EMAIL;
    }

    @Override
    public SendResult send(String destination, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(destination);
            message.setSubject(subjectFrom(body));
            message.setText(body);
            mailSender.send(message);
            meterRegistry.counter(COUNTER, "status", "SUCCESS").increment();
            return SendResult.ok();
        } catch (MailException e) {
            // Same contract as the SMS adapter: recorded, never thrown. See NotificationSender.
            meterRegistry.counter(COUNTER, "status", "FAILED").increment();
            log.warn("SMTP did not accept a message for {}", destination, e);
            return SendResult.failed("Email not accepted: " + e.getMessage());
        }
    }

    /** The first sentence, or a clean truncation of it — never a bare ellipsis mid-word. */
    private static String subjectFrom(String body) {
        int fullStop = body.indexOf('.');
        String candidate = fullStop > 0 ? body.substring(0, fullStop) : body;
        if (candidate.length() <= MAX_SUBJECT_LENGTH) {
            return candidate;
        }
        int lastSpace = candidate.lastIndexOf(' ', MAX_SUBJECT_LENGTH);
        return candidate.substring(0, lastSpace > 0 ? lastSpace : MAX_SUBJECT_LENGTH) + "…";
    }
}
