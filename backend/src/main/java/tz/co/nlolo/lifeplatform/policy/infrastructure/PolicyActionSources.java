package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.approvals.api.ActionSource;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.SubmissionStatus;
import tz.co.nlolo.lifeplatform.policy.domain.Policy;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Policy's jobs for the bell (2026-11-03): an agent's client whose offer closes within three days unpaid, and a
 * lender's credit-life file waiting for a second person to accept it.
 */
@Configuration
class PolicyActionSources {

    private static final Logger log = LoggerFactory.getLogger(PolicyActionSources.class);
    private static final ZoneId CIVIL_ZONE = ZoneId.of("Africa/Dar_es_Salaam");
    /** How close to its last day an unpaid offer becomes the agent's job. */
    static final int OFFER_WARNING_DAYS = 3;

    /** The last day to pay is the issue date plus the offer's validity -- the day the offer message names. */
    @Bean
    ActionSource offerClosing(PolicyRepository policies, DistributionApi distribution, PartyApi parties,
                              ReferenceDataApi refdata) {
        return ActionSource.forAgents("offer-closing", "Remind the client to pay", (viewer, limit) -> {
            if (viewer.partyId() == null) return new ActionSource.Pending(0, List.of());
            List<UUID> team = distribution.resolveAgentTeam(viewer.partyId());
            if (team.isEmpty()) return new ActionSource.Pending(0, List.of());
            long validity = validityDays(refdata);
            LocalDate today = LocalDate.now(CIVIL_ZONE);
            List<Policy> offers = policies.findOffersIssuedBetween(TenantContext.get(), today.minusDays(validity),
                today.plusDays(OFFER_WARNING_DAYS).minusDays(validity), team);
            Map<UUID, String> names = parties.displayNames(offers.stream()
                .map(Policy::getPolicyholderPartyId).filter(Objects::nonNull).toList());
            List<ActionSource.Item> items = new ArrayList<>();
            for (Policy p : offers) {
                LocalDate closes = p.getIssueDate().plusDays(validity);
                items.add(new ActionSource.Item(p.getPolicyNumber(), p.getPolicyNumber(),
                    names.getOrDefault(p.getPolicyholderPartyId(), "A client"),
                    "Offer " + p.getPolicyNumber() + (closes.equals(today) ? " closes today"
                        : " closes " + closes.format(java.time.format.DateTimeFormatter.ofPattern("d MMM",
                            java.util.Locale.ENGLISH))),
                    closes.minusDays(OFFER_WARNING_DAYS).atStartOfDay(CIVIL_ZONE).toInstant()));
            }
            return ActionSource.Pending.of(items, limit);
        });
    }

    /**
     * The person who sent a file in may not accept it, so it is not their job -- unless they hold
     * schemes.enrolments-own (2026-11-04), when it is.
     */
    @Bean
    ActionSource creditLifeFiles(EnrolmentSubmissionRepository enrolments, ExitSubmissionRepository exits,
                                 PolicyRepository policies, PartyApi parties,
                                 tz.co.nlolo.lifeplatform.approvals.api.OwnWork ownWork) {
        return ActionSource.forStaff("credit-life-file", "schemes.enrolments", "Accept or withdraw it",
            (viewer, limit) -> {
                record File(String id, String policyNumber, String what, java.time.Instant at) {}
                List<File> files = new ArrayList<>();
                UUID tenant = TenantContext.get();
                boolean alone = ownWork.mayActAlone("schemes.enrolments-own");
                enrolments.findByTenantIdAndStatusOrderBySubmittedAtDesc(tenant, SubmissionStatus.PENDING).stream()
                    .filter(s -> alone || !Objects.equals(s.getSubmittedBy(), viewer.subject()))
                    .forEach(s -> files.add(new File(s.getSubmissionId().toString(), s.getPolicyNumber(),
                        "New-loans file", s.getSubmittedAt())));
                exits.findByTenantIdAndStatusOrderBySubmittedAtDesc(tenant, SubmissionStatus.PENDING).stream()
                    .filter(s -> alone || !Objects.equals(s.getSubmittedBy(), viewer.subject()))
                    .forEach(s -> files.add(new File(s.getSubmissionId().toString(), s.getPolicyNumber(),
                        "Loans-ended file", s.getSubmittedAt())));
                Map<String, UUID> holders = new java.util.HashMap<>();
                for (File f : files) {
                    policies.findByPolicyNumberAndTenantId(f.policyNumber(), tenant)
                        .ifPresent(p -> holders.put(f.policyNumber(), p.getPolicyholderPartyId()));
                }
                Map<UUID, String> names = parties.displayNames(holders.values().stream().filter(Objects::nonNull).toList());
                return ActionSource.Pending.of(files.stream()
                    .map(f -> new ActionSource.Item(f.id(), f.policyNumber(),
                        names.getOrDefault(holders.get(f.policyNumber()), "A lender"),
                        f.what() + " · " + f.policyNumber(), f.at()))
                    .toList(), limit);
            });
    }

    private static long validityDays(ReferenceDataApi refdata) {
        try {
            return Long.parseLong(refdata.getValue("TZ_OFFER_VALIDITY_DAYS", "TZ"));
        } catch (RuntimeException e) {
            log.warn("TZ_OFFER_VALIDITY_DAYS unreadable; using 30 for the offers closing", e);
            return 30L;
        }
    }
}
