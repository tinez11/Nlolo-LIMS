package tz.co.nlolo.lifeplatform.policy.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentCsvParser;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentReportRenderer;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentSubmission;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentSubmissionRow;
import tz.co.nlolo.lifeplatform.policy.domain.GroupScheme;
import tz.co.nlolo.lifeplatform.policy.domain.Policy;
import tz.co.nlolo.lifeplatform.policy.infrastructure.EnrolmentSubmissionRepository;
import tz.co.nlolo.lifeplatform.policy.infrastructure.EnrolmentSubmissionRowRepository;
import tz.co.nlolo.lifeplatform.policy.infrastructure.GroupSchemeRepository;
import tz.co.nlolo.lifeplatform.policy.infrastructure.PolicyMemberRepository;
import tz.co.nlolo.lifeplatform.policy.infrastructure.PolicyRepository;
import tz.co.nlolo.lifeplatform.product.api.EligibilityBounds;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bulk enrolment: read a lender's file, judge every row, and enrol nobody until a second
 * person says so.
 *
 * <p>Judging happens at SUBMIT and enrolling at ACCEPT, which means the checks here must
 * reach the same verdict {@code PolicyApi.addMember} would. Where they disagree that is
 * a bug, and acceptance fails loudly rather than quietly recording a rejection -- a
 * silent divergence between the report and the schedule is the worst thing this feature
 * could produce.
 */
@Service
public class EnrolmentApiImpl implements EnrolmentApi {

    private final EnrolmentSubmissionRepository submissionRepository;
    private final EnrolmentSubmissionRowRepository rowRepository;
    private final GroupSchemeRepository groupSchemeRepository;
    private final PolicyRepository policyRepository;
    private final PolicyMemberRepository policyMemberRepository;
    private final PolicyApi policyApi;
    private final ProductApi productApi;
    private final DocumentApi documentApi;

    public EnrolmentApiImpl(EnrolmentSubmissionRepository submissionRepository,
                             EnrolmentSubmissionRowRepository rowRepository,
                             GroupSchemeRepository groupSchemeRepository,
                             PolicyRepository policyRepository,
                             PolicyMemberRepository policyMemberRepository,
                             PolicyApi policyApi, ProductApi productApi, DocumentApi documentApi) {
        this.submissionRepository = submissionRepository;
        this.rowRepository = rowRepository;
        this.groupSchemeRepository = groupSchemeRepository;
        this.policyRepository = policyRepository;
        this.policyMemberRepository = policyMemberRepository;
        this.policyApi = policyApi;
        this.productApi = productApi;
        this.documentApi = documentApi;
    }

    @Override
    @Transactional
    public EnrolmentSubmissionView submit(String policyNumber, InputStream file, String fileName,
                                           String submittedBy) {
        UUID tenantId = TenantContext.get();
        Policy policy = findPolicy(policyNumber, tenantId);
        GroupScheme scheme = findScheme(policyNumber, tenantId);

        if (scheme.getBenefitBasis() != BenefitBasis.AMORTISING_LOAN) {
            throw new InvalidPolicyStateException("Scheme " + policyNumber + " is on the "
                + scheme.getBenefitBasis() + " basis, not AMORTISING_LOAN; only a credit-life"
                + " scheme enrols borrowers from a lender's schedule");
        }

        // A readable error in front of ux_enrolment_submission_in_flight, which remains
        // the guarantee. Two files in flight can enrol the same loan twice.
        submissionRepository.findByTenantIdAndPolicyNumberAndStatus(
                tenantId, policyNumber, SubmissionStatus.PENDING)
            .ifPresent(pending -> {
                throw new InvalidPolicyStateException("Scheme " + policyNumber
                    + " already has a submission awaiting acceptance (" + pending.getSubmissionId()
                    + "), uploaded by " + pending.getSubmittedBy()
                    + ". Accept or withdraw it before sending another file.");
            });

        // Read ONCE: the bytes are both stored and parsed, and a stream cannot be
        // consumed twice.
        byte[] bytes = readFully(file);

        EnrolmentCsvParser.ParsedSchedule parsed;
        try {
            parsed = EnrolmentCsvParser.parse(
                new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
        } catch (EnrolmentCsvParser.MalformedScheduleException e) {
            // A FILE problem. Nothing is written and nothing is stored: there would be
            // nothing to accept, and a pending row would block the corrected file the
            // lender is about to send.
            throw new InvalidPolicyStateException(e.getMessage());
        }

        EnrolmentSubmission submission = new EnrolmentSubmission(tenantId, policyNumber,
            "pending-upload", fileName, 0, 0, submittedBy);
        submissionRepository.saveAndFlush(submission);
        UUID submissionId = submission.getSubmissionId();

        // Filed under the submission it belongs to. owner_context is VARCHAR(50) and
        // "submission:" + a UUID is 47 characters -- checked against the live column
        // rather than estimated, per document/V2's own note.
        submission.recordDocument(documentApi.upload("submission:" + submissionId,
            DocumentType.ENROLMENT_SCHEDULE, submittedBy,
            new ByteArrayInputStream(bytes), bytes.length, "text/csv", fileName));

        Judge judge = new Judge(policy, scheme, tenantId, policyNumber);
        List<EnrolmentSubmissionRow> rows = new ArrayList<>();
        int rejected = 0;

        for (EnrolmentCsvParser.RowError error : parsed.errors()) {
            rows.add(EnrolmentSubmissionRow.rejected(tenantId, submissionId, error.lineNumber(),
                error.loanAccountNumber(), error.borrowerFullName(), error.reason(),
                error.detail() + " " + EnrolmentReportRenderer.NOT_COVERED));
            rejected++;
        }
        for (EnrolmentRow row : parsed.rows()) {
            EnrolmentSubmissionRow judged = judge.judge(row, submissionId);
            if (judged.getOutcome() == RowOutcome.REJECTED) rejected++;
            rows.add(judged);
        }

        rowRepository.saveAll(rows);
        submission.recordJudgement(rows.size(), rejected);
        return toView(submission);
    }

    @Override
    @Transactional
    public EnrolmentSubmissionView accept(UUID submissionId, String acceptedBy) {
        UUID tenantId = TenantContext.get();
        EnrolmentSubmission submission = findSubmission(submissionId, tenantId);
        GroupScheme scheme = findScheme(submission.getPolicyNumber(), tenantId);

        // Refuse BEFORE enrolling anybody, so a rejected acceptance leaves no members.
        try {
            submission.requireAcceptableBy(acceptedBy);
        } catch (IllegalStateException e) {
            throw new InvalidPolicyStateException(e.getMessage());
        }

        int enrolled = 0;
        for (EnrolmentSubmissionRow row :
                rowRepository.findByTenantIdAndSubmissionIdOrderByLineNumberAsc(tenantId, submissionId)) {
            // ENROLLED_CAPPED is cover, not a refusal: a capped borrower is insured up
            // to the limit with a referral open for the excess. Skipping them here is
            // the most plausible way to get this wrong.
            if (row.getOutcome() == RowOutcome.REJECTED) continue;

            EnrolmentSubmissionRow.LoanTermsSource judged = row.getJudgedLoan();
            PolicyMemberView member = policyApi.addMember(submission.getPolicyNumber(),
                PolicyApi.MemberInput.borrower(row.getBorrowerFullName(),
                    judged.borrowerDateOfBirth(), row.getLoanAccountNumber(),
                    loanTermsFor(judged, scheme)),
                acceptedBy);
            row.becameMember(member.policyMemberId());
            enrolled++;
        }

        submission.accept(acceptedBy, enrolled);
        return toView(submission);
    }

    @Override
    @Transactional
    public EnrolmentSubmissionView withdraw(UUID submissionId, String withdrawnBy) {
        EnrolmentSubmission submission = findSubmission(submissionId, TenantContext.get());
        try {
            submission.withdraw();
        } catch (IllegalStateException e) {
            throw new InvalidPolicyStateException(e.getMessage());
        }
        return toView(submission);
    }

    @Override
    @Transactional(readOnly = true)
    public EnrolmentSubmissionView getSubmission(UUID submissionId) {
        return toView(findSubmission(submissionId, TenantContext.get()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<EnrolmentRowView> listRows(UUID submissionId) {
        UUID tenantId = TenantContext.get();
        findSubmission(submissionId, tenantId); // not-found rather than an empty list
        return rowRepository.findByTenantIdAndSubmissionIdOrderByLineNumberAsc(tenantId, submissionId)
            .stream()
            .map(row -> new EnrolmentRowView(row.getLineNumber(), row.getLoanAccountNumber(),
                row.getBorrowerFullName(), row.getOutcome(), row.getReasonCode(), row.getReason(),
                row.getPolicyMemberId()))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public String renderReport(UUID submissionId) {
        return EnrolmentReportRenderer.toCsv(listRows(submissionId));
    }

    /**
     * A row plus its scheme make a loan.
     *
     * <p>THE ONE PLACE these derivations live, and the one place to change when the
     * client answers how cover declines:
     *
     * <ul>
     *   <li><b>rate</b> is ZERO. Straight-line decline never reads it -- FLAT_RATE's
     *       principal decline is {@code principal x (n-k)/n}. If the answer comes back
     *       "reducing balance" the rate must return to the template, because a reducing
     *       schedule genuinely needs a per-loan rate.</li>
     *   <li><b>frequency</b> comes from the scheme: a lender's product repays on one
     *       cadence.</li>
     *   <li><b>first repayment</b> is disbursement plus one period. A moratorium cannot
     *       be expressed now the column is gone -- an open client question.</li>
     * </ul>
     */
    private static LoanTerms loanTermsFor(EnrolmentSubmissionRow.LoanTermsSource judged,
                                           GroupScheme scheme) {
        RepaymentFrequency frequency = scheme.getRepaymentFrequency();
        return new LoanTerms(judged.loanPrincipalAmount(), BigDecimal.ZERO,
            judged.loanTermMonths(), frequency, judged.disbursementDate(),
            judged.disbursementDate().plusMonths(frequency.monthsPerPeriod()));
    }

    // ---- judging ------------------------------------------------------------

    /** What a row was judged to be, and why. */
    private record Verdict(RowOutcome outcome, EnrolmentRejection reasonCode, String reason) {}

    /**
     * Decides each row's outcome at SUBMIT, writing nothing to the schedule.
     *
     * <p>Resolves the product's bounds once rather than per row: a 400-borrower file
     * would otherwise re-read the same snapshot 400 times.
     */
    private final class Judge {
        private final GroupScheme scheme;
        private final EligibilityBounds bounds;
        private final LocalDate commencement;
        private final LocalDate today = LocalDate.now();
        private final UUID tenantId;
        private final String policyNumber;

        Judge(Policy policy, GroupScheme scheme, UUID tenantId, String policyNumber) {
            this.scheme = scheme;
            this.tenantId = tenantId;
            this.policyNumber = policyNumber;
            this.commencement = policy.getCommencementDate();
            this.bounds = productApi.getActiveSnapshot(policy.getProductId(), LocalDate.now())
                .eligibility();
        }

        EnrolmentSubmissionRow judge(EnrolmentRow row, UUID submissionId) {
            Verdict verdict = verdictFor(row);
            return switch (verdict.outcome()) {
                case REJECTED -> EnrolmentSubmissionRow.rejected(tenantId, submissionId,
                    row.lineNumber(), row.loanAccountNumber(), row.borrowerFullName(),
                    verdict.reasonCode(), verdict.reason());
                case ENROLLED_CAPPED -> EnrolmentSubmissionRow.capped(tenantId, submissionId,
                    row, verdict.reason());
                case ENROLLED -> EnrolmentSubmissionRow.accepted(tenantId, submissionId, row);
            };
        }

        /** The FIRST reason this row cannot be enrolled, or its cover if it can. */
        private Verdict verdictFor(EnrolmentRow row) {
            if (row.disbursementDate().isAfter(today)) {
                return refused(EnrolmentRejection.DISBURSEMENT_DATE_IN_FUTURE,
                    "disbursement_date " + row.disbursementDate() + " is in the future."
                        + " Cover cannot commence before the loan exists.");
            }
            if (commencement != null && row.disbursementDate().isBefore(commencement)) {
                return refused(EnrolmentRejection.LOAN_BEFORE_SCHEME_COMMENCED,
                    "disbursement_date " + row.disbursementDate() + " is before this scheme"
                        + " commenced on " + commencement + ".");
            }
            String ageOrTerm = ageOrTermRefusal(row);
            if (ageOrTerm != null) {
                return refused(EnrolmentRejection.ENTRY_AGE_OR_TERM_OUT_OF_BOUNDS, ageOrTerm);
            }
            if (policyMemberRepository.existsByTenantIdAndPolicyNumberAndLoanAccountNumberAndStatus(
                    tenantId, policyNumber, row.loanAccountNumber(), MemberStatus.ACTIVE.name())) {
                return refused(EnrolmentRejection.ALREADY_ENROLLED,
                    "loan_account_number " + row.loanAccountNumber() + " is already an active"
                        + " member of this scheme from an earlier file.");
            }
            // LoanTerms carries its own invariants -- a term that does not divide into
            // whole periods throws from the record's constructor. Caught HERE rather
            // than at acceptance, where it would roll back an entire accepted file.
            try {
                loanTermsFor(new EnrolmentSubmissionRow.LoanTermsSource(row.borrowerDateOfBirth(),
                    row.loanPrincipalAmount(), row.loanTermMonths(), row.disbursementDate()), scheme);
            } catch (IllegalArgumentException e) {
                return refused(EnrolmentRejection.MALFORMED_VALUE, e.getMessage() + ".");
            }

            BigDecimal fcl = scheme.getFclAmount();
            if (fcl != null && row.loanPrincipalAmount().compareTo(fcl) > 0) {
                return new Verdict(RowOutcome.ENROLLED_CAPPED, null,
                    "Cover limited to the free cover limit of " + fcl.toPlainString() + " "
                        + scheme.getCurrency() + " against a loan of "
                        + row.loanPrincipalAmount().toPlainString() + " " + scheme.getCurrency()
                        + ". The excess is NOT covered. An underwriting referral opens when this"
                        + " submission is accepted, and cover may be extended if medical evidence"
                        + " is supplied.");
            }
            return new Verdict(RowOutcome.ENROLLED, null, null);
        }

        private Verdict refused(EnrolmentRejection code, String detail) {
            return new Verdict(RowOutcome.REJECTED, code,
                detail + " " + EnrolmentReportRenderer.NOT_COVERED);
        }

        private String ageOrTermRefusal(EnrolmentRow row) {
            if (bounds == null) return null;
            int entryAge = row.entryAge();
            int maturityAge = Period.between(row.borrowerDateOfBirth(),
                row.disbursementDate().plusMonths(row.loanTermMonths())).getYears();

            if (bounds.minEntryAge() != null && entryAge < bounds.minEntryAge()) {
                return "Age at entry is " + entryAge + "; this product accepts from "
                    + bounds.minEntryAge() + ".";
            }
            if (bounds.maxEntryAge() != null && entryAge > bounds.maxEntryAge()) {
                return "Age at entry is " + entryAge + " and age at maturity would be "
                    + maturityAge + "; this product accepts entry ages up to "
                    + bounds.maxEntryAge() + ".";
            }
            if (bounds.minTermMonths() != null && row.loanTermMonths() < bounds.minTermMonths()) {
                return "A term of " + row.loanTermMonths() + " months is shorter than this"
                    + " product's minimum of " + bounds.minTermMonths() + ".";
            }
            if (bounds.maxTermMonths() != null && row.loanTermMonths() > bounds.maxTermMonths()) {
                return "A term of " + row.loanTermMonths() + " months is longer than this"
                    + " product's maximum of " + bounds.maxTermMonths()
                    + ". Cover is not truncated to fit: a loan insured for part of its life is"
                    + " worse than one openly refused.";
            }
            return null;
        }
    }

    // ---- plumbing ------------------------------------------------------------

    private static byte[] readFully(InputStream file) {
        try {
            return file.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded schedule", e);
        }
    }

    private Policy findPolicy(String policyNumber, UUID tenantId) {
        return policyRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId)
            .orElseThrow(() -> new PolicyNotFoundException(policyNumber));
    }

    private GroupScheme findScheme(String policyNumber, UUID tenantId) {
        return groupSchemeRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId)
            .orElseThrow(() -> new InvalidPolicyStateException(
                "Policy " + policyNumber + " is not a scheme"));
    }

    private EnrolmentSubmission findSubmission(UUID submissionId, UUID tenantId) {
        return submissionRepository.findBySubmissionIdAndTenantId(submissionId, tenantId)
            .orElseThrow(() -> new InvalidPolicyStateException(
                "No enrolment submission " + submissionId + " in this tenant"));
    }

    private static EnrolmentSubmissionView toView(EnrolmentSubmission s) {
        return new EnrolmentSubmissionView(s.getSubmissionId(), s.getPolicyNumber(), s.getStatus(),
            s.getFileName(), s.getRowCount(), s.getEnrolledCount(), s.getRejectedCount(),
            s.getSubmittedBy(), s.getSubmittedAt(), s.getAcceptedBy(), s.getAcceptedAt());
    }
}
