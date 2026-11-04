package tz.co.nlolo.lifeplatform.approvals.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.approvals.api.OwnWork;
import tz.co.nlolo.lifeplatform.iam.api.AccessApi;
import tz.co.nlolo.lifeplatform.iam.api.CurrentAccess;
import tz.co.nlolo.lifeplatform.iam.api.Permission;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link OwnWork}: the signed-in person's own permissions decide, and the act is journalled with their name.
 *
 * <p>Outside a request -- a scheduled job, a listener -- nobody is signed in and the answer is no: two people stay
 * the rule wherever there is no person to have been given the permission.
 */
@Service
public class OwnWorkService implements OwnWork {

    private static final Logger log = LoggerFactory.getLogger(OwnWorkService.class);

    private final CurrentAccess access;
    private final AccessApi staff;
    private final ApplicationEventPublisher events;

    public OwnWorkService(CurrentAccess access, AccessApi staff, ApplicationEventPublisher events) {
        this.access = access;
        this.staff = staff;
        this.events = events;
    }

    @Override
    public boolean mayActAlone(String permission) {
        try {
            return Permission.fromCode(permission).map(access::can).orElse(false);
        } catch (RuntimeException e) {
            // No request, no signed-in person: nobody to have been given it.
            return false;
        }
    }

    @Override
    public void record(String what, String reference, String policyNumber, String by) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("what", what);
        payload.put("reference", reference);
        if (policyNumber != null) payload.put("policyNumber", policyNumber);
        payload.put("by", by);
        payload.put("byName", nameOf(by));
        payload.put("at", Instant.now().toString());
        events.publishEvent(DomainEventEnvelope.of(EVENT, TenantContext.get(), payload));
    }

    /** By name, as the user asked; the subject stands in if the name cannot be read. */
    private String nameOf(String subject) {
        try {
            return staff.displayNames(List.of(subject)).getOrDefault(subject, subject);
        } catch (RuntimeException e) {
            log.warn("Could not name {} for the acted-alone journal entry", subject, e);
            return subject;
        }
    }
}
