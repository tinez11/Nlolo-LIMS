package tz.co.nlolo.lifeplatform.policy.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.policy.domain.CreditLifePremium;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentCsvParser;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentReportRenderer;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentSubmission;
import tz.co.nlolo.lifeplatform.policy.domain.EnrolmentSubmissionRow;
import tz.co.nlolo.lifeplatform.policy.domain.GroupScheme;
import tz.co.nlolo.lifeplatform.policy.domain.XlsxToCsv;
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
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import tz.co.nlolo.lifeplatform.policy.domain.LenderTemplateXlsx;
import tz.co.nlolo.lifeplatform.policy.domain.PolicyMember;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;

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
    private final PartyApi partyApi;
    private final PolicyApi policyApi;
    private final ProductApi productApi;
    private final DocumentApi documentApi;
    private final ApplicationEventPublisher eventPublisher;

    public EnrolmentApiImpl(EnrolmentSubmissionRepository submissionRepository,
                             EnrolmentSubmissionRowRepository rowRepository,
                             GroupSchemeRepository groupSchemeRepository,
                             PolicyRepository policyRepository,
                             PolicyMemberRepository policyMemberRepository,
                             PolicyApi policyApi, ProductApi productApi, DocumentApi documentApi,
                             PartyApi partyApi,
                             ApplicationEventPublisher eventPublisher) {
        this.submissionRepository = submissionRepository;
        this.rowRepository = rowRepository;
        this.groupSchemeRepository = groupSchemeRepository;
        this.policyRepository = policyRepository;
        this.policyMemberRepository = policyMemberRepository;
        this.partyApi = partyApi;
        this.policyApi = policyApi;
        this.productApi = productApi;
        this.documentApi = documentApi;
        this.eventPublisher = eventPublisher;
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

        // Detected from the file's own bytes rather than its declared content type: a
        // browser posting a .xlsx routinely sends application/octet-stream, and a
        // lender's mail client is worse.
        boolean workbook = looksLikeXlsx(bytes);

        EnrolmentCsvParser.ParsedSchedule parsed;
        try {
            // Converted ONCE, here, so validation, judging, the report and every test
            // downstream see exactly one shape.
            String csv = workbook
                ? XlsxToCsv.convert(new ByteArrayInputStream(bytes))
                : new String(bytes, StandardCharsets.UTF_8);
            parsed = EnrolmentCsvParser.parse(new java.io.StringReader(csv));
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
            new ByteArrayInputStream(bytes), bytes.length,
            // What they actually sent, not what we converted it to. The stored file is
            // the evidence in a dispute, and evidence labelled as something it is not is
            // worth less than no label.
            workbook ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                     : "text/csv",
            fileName));

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
        BigDecimal premiumTotal = BigDecimal.ZERO;
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

            // Priced from this borrower's OWN loan against the scheme's own rate, and summed
            // here rather than re-derived later: a total recomputed from the members would
            // drift the moment one of them exits.
            //
            // On the full principal even for a CAPPED borrower. Capping limits what the
            // insurer will PAY, not what the lender borrowed, and the rate they negotiated is
            // a rate on the loan. Charging the capped amount would quietly discount exactly
            // the borrowers whose excess risk sent them to underwriting.
            BigDecimal memberPremium = CreditLifePremium.forLoan(
                judged.loanPrincipalAmount(), judged.loanTermMonths(), scheme.getPremiumRatePercent());
            row.becameMember(member.policyMemberId(), member.memberReference(), memberPremium);
            premiumTotal = premiumTotal.add(memberPremium);
            enrolled++;
        }

        submission.accept(acceptedBy, enrolled, premiumTotal);

        // Acceptance published NOTHING before this. So no other module could learn that a file
        // had been accepted, and the premium it earned was charged to nobody -- the master
        // policy's own schedule was billing a hand-typed figure instead (see
        // billing.PolicyEventListener, and the SINGLE guard that stopped it).
        // The LENDER, for distribution's rule that only the lender earns commission on its own
        // credit-life scheme (spec 2.8) -- distribution may not read policy to find out.
        UUID lenderPartyId = policyRepository.findByPolicyNumberAndTenantId(submission.getPolicyNumber(), tenantId)
            .map(Policy::getPolicyholderPartyId).orElseThrow();
        eventPublisher.publishEvent(DomainEventEnvelope.of("policy.EnrolmentAccepted", tenantId,
            Map.of("submissionId", submission.getSubmissionId(),
                   "policyNumber", submission.getPolicyNumber(),
                   "policyholderPartyId", lenderPartyId,
                   "enrolledCount", enrolled,
                   // The invoice's due date is derived from this, NOT from the consumer's
                   // clock: ux_premium_invoice_per_submission has to include due_date (the
                   // partition key), so a redelivery must recompute the identical date or the
                   // lender is charged twice.
                   "acceptedAt", submission.getAcceptedAt().toString(),
                   "premium", Map.of("amount", premiumTotal.toPlainString(),
                                     "currencyCode", scheme.getCurrency()))));

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

    /**
     * The scheme's submission history. Uses the repository's
     * {@code findByTenantIdAndPolicyNumberOrderBySubmittedAtDesc}, which existed with no caller
     * until now — the ordering it already declared is the one this needs, so it is used rather
     * than a second finder added beside it.
     */
    @Override
    @Transactional(readOnly = true)
    public List<EnrolmentSubmissionView> listSubmissions(String policyNumber) {
        return submissionRepository
            .findByTenantIdAndPolicyNumberOrderBySubmittedAtDesc(TenantContext.get(), policyNumber)
            .stream().map(EnrolmentApiImpl::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EnrolmentRowView> listRows(UUID submissionId) {
        UUID tenantId = TenantContext.get();
        EnrolmentSubmission submission = findSubmission(submissionId, tenantId);
        String currency = groupSchemeRepository
            .findByPolicyNumberAndTenantId(submission.getPolicyNumber(), tenantId)
            .map(GroupScheme::getCurrency)
            .orElse(null);
        return rowRepository.findByTenantIdAndSubmissionIdOrderByLineNumberAsc(tenantId, submissionId)
            .stream()
            .map(row -> new EnrolmentRowView(row.getLineNumber(), row.getLoanAccountNumber(),
                row.getBorrowerFullName(), row.getOutcome(), row.getReasonCode(), row.getReason(),
                row.getPolicyMemberId(), row.getMemberReference(), row.getPremiumAmount(),
                currency))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public String renderReport(UUID submissionId) {
        return EnrolmentReportRenderer.toCsv(listRows(submissionId));
    }

    @Override
    @Transactional(readOnly = true)
    public String renderTemplate(String policyNumber) {
        UUID tenantId = TenantContext.get();
        // The EARLIEST member, which on a scheme set up through the console is the opening
        // borrower somebody typed into the form. Echoing their own entry back is the whole point:
        // the file they are about to send is the file they already filled in once.
        //
        // The sort must be TOTAL -- a bulk upload gives every row the same joinedOn, and this
        // asks for exactly one row out of a paged query.
        Pageable earliest = PageRequest.of(0, 1, Sort.by(Sort.Direction.ASC, "joinedOn")
            .and(Sort.by(Sort.Direction.ASC, "policyMemberId")));
        List<PolicyMember> members = policyMemberRepository
            .findByTenantIdAndPolicyNumberAndStatus(tenantId, policyNumber, "ACTIVE", earliest)
            .getContent();

        StringBuilder csv = new StringBuilder(EnrolmentCsvParser.templateCsv());
        for (PolicyMember member : members) {
            LoanTerms terms = member.getLoanTerms();
            if (terms == null) {
                // No loan, nothing to demonstrate. A half-filled row teaches the wrong shape.
                continue;
            }
            /*
             * A PARTY MEMBER'S NAME LIVES IN party, NOT ON THE MEMBER ROW, and skipping those was
             * a real defect rather than a tidy guard. The first scheme a person set up this way
             * had its opening borrower above the free cover limit, so the platform referred them
             * for evidence and PROMOTED them to a registered party -- correct behaviour, and it
             * left memberName null. The template then fell back to a bare header, on the one
             * scheme whose owner most needed to see a filled row, and the file they sent back had
             * every date in Excel's own format and was refused entire.
             */
            String name = member.getMemberName();
            LocalDate born = member.getMemberDateOfBirth();
            if ((name == null || born == null) && member.getMemberPartyId() != null) {
                PartyDetailView party = partyApi.getPartyDetail(member.getMemberPartyId());
                name = name != null ? name : party.displayName();
                born = born != null ? born : party.dateOfBirth();
            }
            if (name == null || born == null) {
                continue;
            }
            csv.append(String.join(",",
                    csvValue(member.getMemberReference()),
                    csvValue(name),
                    born.toString(),
                    "", "", "",
                    terms.principalAmount().toPlainString(),
                    String.valueOf(terms.termMonths()),
                    terms.disbursementDate().toString(),
                    csvValue(member.getLoanAccountNumber())))
                .append('\n');
        }
        return csv.toString();
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] renderTemplateXlsx(String policyNumber) {
        // Built from the same example the CSV template uses, so the two cannot describe different
        // borrowers -- and parsed back by the same reader either way.
        return LenderTemplateXlsx.enrolment(exampleRowFor(policyNumber));
    }

    /**
     * The scheme's earliest active borrower, as an example, or null if it has none to show.
     *
     * <p>Shared by both templates. Split out when the spreadsheet arrived rather than duplicated,
     * because a CSV and an XLSX that demonstrated DIFFERENT borrowers would be the same class of
     * defect the generated header was introduced to prevent.
     */
    private LenderTemplateXlsx.EnrolmentExample exampleRowFor(String policyNumber) {
        UUID tenantId = TenantContext.get();
        Pageable earliest = PageRequest.of(0, 1, Sort.by(Sort.Direction.ASC, "joinedOn")
            .and(Sort.by(Sort.Direction.ASC, "policyMemberId")));
        for (PolicyMember member : policyMemberRepository
                .findByTenantIdAndPolicyNumberAndStatus(tenantId, policyNumber, "ACTIVE", earliest)
                .getContent()) {
            LoanTerms terms = member.getLoanTerms();
            if (terms == null) {
                continue;
            }
            String name = member.getMemberName();
            LocalDate born = member.getMemberDateOfBirth();
            if ((name == null || born == null) && member.getMemberPartyId() != null) {
                PartyDetailView party = partyApi.getPartyDetail(member.getMemberPartyId());
                name = name != null ? name : party.displayName();
                born = born != null ? born : party.dateOfBirth();
            }
            if (name == null || born == null) {
                continue;
            }
            return new LenderTemplateXlsx.EnrolmentExample(member.getMemberReference(), name, born,
                terms.principalAmount(), terms.termMonths(), terms.disbursementDate(),
                member.getLoanAccountNumber());
        }
        return null;
    }

    /**
     * A value safe to sit in a CSV cell.
     *
     * <p>A borrower's name is the one field here a lender wrote, and real exports carry commas in
     * them -- "Mwinyi, Amina H." is an ordinary way for a bank to hold a name. Emitting it raw
     * would shift every column after it and hand back a template the parser that generated it
     * cannot read.
     */
    private static String csvValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        if (raw.contains(",") || raw.contains("\"") || raw.contains("\n")) {
            return '"' + raw.replace("\"", "\"\"") + '"';
        }
        return raw;
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
            // The lender supplies no identifier, so the LOAN is the identity: who, born
            // when, borrowed how much, on what day. Judged HERE rather than discovered as
            // a failure part-way through acceptance, by which point the lender has
            // already been told the row was acceptable.
            if (policyMemberRepository.existsMatchingLoan(tenantId, policyNumber,
                    row.borrowerFullName(), row.borrowerDateOfBirth(),
                    row.disbursementDate(), row.loanPrincipalAmount())) {
                return refused(EnrolmentRejection.ALREADY_ENROLLED,
                    row.borrowerFullName() + " already has an active loan of "
                        + row.loanPrincipalAmount().toPlainString() + " disbursed on "
                        + row.disbursementDate() + " on this scheme, from an earlier file."
                        + " If this is a genuinely separate loan, quote the existing"
                        + " member's reference in the member_reference column.");
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

    /**
     * An XLSX is a ZIP: it begins {@code PK\003\004}.
     *
     * <p>Sniffed from the bytes rather than trusted from the declared content type,
     * because a browser posting a .xlsx routinely sends application/octet-stream and a
     * lender's mail client is worse. Getting this wrong in the safe direction just means
     * the parser reports an unreadable header.
     */
    private static boolean looksLikeXlsx(byte[] bytes) {
        return bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K'
            && bytes[2] == 3 && bytes[3] == 4;
    }

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
