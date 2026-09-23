package tz.co.nlolo.lifeplatform.policy.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.document.api.DocumentApi;
import tz.co.nlolo.lifeplatform.document.api.DocumentType;
import tz.co.nlolo.lifeplatform.policy.api.*;
import tz.co.nlolo.lifeplatform.policy.domain.ExitCsvParser;
import tz.co.nlolo.lifeplatform.policy.domain.ExitReportRenderer;
import tz.co.nlolo.lifeplatform.policy.domain.ExitSubmission;
import tz.co.nlolo.lifeplatform.policy.domain.ExitSubmissionRow;
import tz.co.nlolo.lifeplatform.policy.domain.GroupScheme;
import tz.co.nlolo.lifeplatform.policy.domain.PolicyMember;
import tz.co.nlolo.lifeplatform.policy.domain.XlsxToCsv;
import tz.co.nlolo.lifeplatform.policy.infrastructure.ExitSubmissionRepository;
import tz.co.nlolo.lifeplatform.policy.infrastructure.ExitSubmissionRowRepository;
import tz.co.nlolo.lifeplatform.policy.infrastructure.GroupSchemeRepository;
import tz.co.nlolo.lifeplatform.policy.infrastructure.PolicyMemberRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Bulk exits: read a lender's file, judge every row, and take nobody off cover until a second
 * person says so.
 *
 * <p>Mirrors {@link EnrolmentApiImpl} step for step, and the symmetry is the point — a reader
 * who understands one understands the other. Judging happens at SUBMIT and exiting at ACCEPT,
 * which means the checks here must reach the same verdict {@code PolicyApi.exitMember} would.
 *
 * <p>One asymmetry is worth stating. On an enrolment file, a rejection leaves somebody
 * uninsured and somebody eventually notices. Here, a rejection leaves a loan still on cover and
 * still being charged for, and nobody notices at all — so the report is not a courtesy on
 * either file, but for opposite reasons.
 */
@Service
public class ExitApiImpl implements ExitApi {

    private final ExitSubmissionRepository submissionRepository;
    private final ExitSubmissionRowRepository rowRepository;
    private final GroupSchemeRepository groupSchemeRepository;
    private final PolicyMemberRepository policyMemberRepository;
    private final PolicyApi policyApi;
    private final DocumentApi documentApi;

    public ExitApiImpl(ExitSubmissionRepository submissionRepository,
                        ExitSubmissionRowRepository rowRepository,
                        GroupSchemeRepository groupSchemeRepository,
                        PolicyMemberRepository policyMemberRepository,
                        PolicyApi policyApi, DocumentApi documentApi) {
        this.submissionRepository = submissionRepository;
        this.rowRepository = rowRepository;
        this.groupSchemeRepository = groupSchemeRepository;
        this.policyMemberRepository = policyMemberRepository;
        this.policyApi = policyApi;
        this.documentApi = documentApi;
    }

    @Override
    @Transactional
    public ExitSubmissionView submit(String policyNumber, InputStream file, String fileName,
                                      String submittedBy) {
        UUID tenantId = TenantContext.get();
        GroupScheme scheme = findScheme(policyNumber, tenantId);

        if (scheme.getBenefitBasis() != BenefitBasis.AMORTISING_LOAN) {
            throw new InvalidPolicyStateException("Scheme " + policyNumber + " is on the "
                + scheme.getBenefitBasis() + " basis, not AMORTISING_LOAN; only a credit-life"
                + " scheme takes loans off cover from a lender's file");
        }

        // A readable error in front of ux_exit_submission_in_flight, which remains the
        // guarantee. Two files in flight can exit the same loan twice.
        submissionRepository.findByTenantIdAndPolicyNumberAndStatus(
                tenantId, policyNumber, SubmissionStatus.PENDING)
            .ifPresent(pending -> {
                throw new InvalidPolicyStateException("Scheme " + policyNumber
                    + " already has an exits file awaiting acceptance (" + pending.getSubmissionId()
                    + "), uploaded by " + pending.getSubmittedBy()
                    + ". Accept or withdraw it before sending another file.");
            });

        // Read ONCE: the bytes are both stored and parsed, and a stream cannot be consumed
        // twice.
        byte[] bytes = readFully(file);
        boolean workbook = looksLikeXlsx(bytes);

        ExitCsvParser.ParsedExits parsed;
        try {
            String csv = workbook
                ? XlsxToCsv.convert(new ByteArrayInputStream(bytes))
                : new String(bytes, StandardCharsets.UTF_8);
            parsed = ExitCsvParser.parse(new java.io.StringReader(csv));
        } catch (ExitCsvParser.MalformedExitsFileException e) {
            // A FILE problem. Nothing is written and nothing is stored: there would be nothing
            // to accept, and a pending row would block the corrected file the lender is about
            // to send.
            throw new InvalidPolicyStateException(e.getMessage());
        }

        ExitSubmission submission = new ExitSubmission(tenantId, policyNumber,
            "pending-upload", fileName, 0, 0, submittedBy);
        submissionRepository.saveAndFlush(submission);
        UUID submissionId = submission.getSubmissionId();

        submission.recordDocument(documentApi.upload("exits:" + submissionId,
            DocumentType.EXITS_FILE, submittedBy,
            new ByteArrayInputStream(bytes), bytes.length,
            // What they actually sent, not what we converted it to. The stored file is the
            // evidence in a dispute, and evidence labelled as something it is not is worth
            // less than no label.
            workbook ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                     : "text/csv",
            fileName));

        List<ExitSubmissionRow> rows = new ArrayList<>();

        for (ExitCsvParser.RowError error : parsed.errors()) {
            rows.add(ExitSubmissionRow.rejected(tenantId, submissionId, error.lineNumber(),
                error.memberReference(), error.reason(),
                error.detail() + " " + ExitReportRenderer.STILL_ON_COVER));
        }

        // References already claimed by an ACTIONABLE row of this file, accumulated AS the rows
        // are judged -- populating it afterwards would leave the duplicate check below reading
        // an empty set and quietly doing nothing.
        //
        // The parser already catches a reference repeated within the file, so in practice this
        // is a second line of defence. It is cheap, and what it prevents is acceptance calling
        // exitMember twice for one member: idempotent today, but a guarantee worth not leaning
        // on from two places at once.
        Set<String> claimed = new HashSet<>();

        for (ExitRow row : parsed.rows()) {
            ExitSubmissionRow judged = judge(row, submissionId, tenantId, policyNumber, claimed);
            if (!judged.isRejected()) {
                claimed.add(judged.getMemberReference());
            }
            rows.add(judged);
        }

        int rejected = (int) rows.stream().filter(ExitSubmissionRow::isRejected).count();
        rowRepository.saveAll(rows);
        submission.recordJudgement(rows.size(), rejected);
        return toView(submission);
    }

    /**
     * Does this row name a loan we can actually take off cover?
     *
     * <p>Every check here must reach the same verdict {@code exitMember} would, because a row
     * accepted here and refused there would fail half-way through a file, having already taken
     * earlier borrowers off risk.
     */
    private ExitSubmissionRow judge(ExitRow row, UUID submissionId, UUID tenantId,
                                     String policyNumber, Set<String> claimed) {
        Optional<PolicyMember> found = policyMemberRepository
            .findByTenantIdAndMemberReference(tenantId, row.memberReference());

        // Not merely "does this reference exist" but "is it on THIS scheme". Two lenders in one
        // tenant, and a reference from the other one names a real member we must not touch.
        if (found.isEmpty() || !found.get().getPolicyNumber().equals(policyNumber)) {
            return ExitSubmissionRow.rejected(tenantId, submissionId, row.lineNumber(),
                row.memberReference(), ExitRejection.UNKNOWN_MEMBER_REFERENCE,
                "No borrower on this scheme carries the reference " + row.memberReference()
                    + ". Check it against the enrolment report that issued it. "
                    + ExitReportRenderer.STILL_ON_COVER);
        }

        PolicyMember member = found.get();

        if (MemberStatus.EXITED.name().equals(member.getStatus())) {
            // Already off cover, by an earlier file or by a settled claim. Not an error the
            // lender caused, and exitMember would silently no-op -- but reporting it is how
            // they learn their file and our roll disagree.
            return ExitSubmissionRow.rejected(tenantId, submissionId, row.lineNumber(),
                row.memberReference(), ExitRejection.ALREADY_EXITED,
                row.memberReference() + " already left cover on " + member.getLeftOn()
                    + " (" + member.getExitReason() + "). No further action was taken.");
        }

        if (claimed.contains(row.memberReference())) {
            return ExitSubmissionRow.rejected(tenantId, submissionId, row.lineNumber(),
                row.memberReference(), ExitRejection.DUPLICATE_REFERENCE,
                row.memberReference() + " is already being exited by an earlier row of this file. "
                    + ExitReportRenderer.STILL_ON_COVER);
        }

        if (row.exitDate().isBefore(member.getJoinedOn())) {
            return ExitSubmissionRow.rejected(tenantId, submissionId, row.lineNumber(),
                row.memberReference(), ExitRejection.EXIT_BEFORE_COVER_STARTED,
                "exit_date " + row.exitDate() + " is before cover started on "
                    + member.getJoinedOn() + ". " + ExitReportRenderer.STILL_ON_COVER);
        }

        return ExitSubmissionRow.actionable(tenantId, submissionId, row.lineNumber(),
            row.memberReference(), row.exitDate(), row.exitReason(),
            row.outstandingBalanceAtExit());
    }

    @Override
    @Transactional
    public ExitSubmissionView accept(UUID submissionId, String acceptedBy) {
        UUID tenantId = TenantContext.get();
        ExitSubmission submission = findSubmission(submissionId, tenantId);

        // Refuse BEFORE taking anybody off cover, so a rejected acceptance leaves the roll
        // exactly as it was.
        try {
            submission.requireAcceptableBy(acceptedBy);
        } catch (IllegalStateException e) {
            throw new InvalidPolicyStateException(e.getMessage());
        }

        int exited = 0;
        for (ExitSubmissionRow row :
                rowRepository.findByTenantIdAndSubmissionIdOrderByLineNumberAsc(tenantId, submissionId)) {
            if (row.isRejected()) continue;

            PolicyMember member = policyMemberRepository
                .findByTenantIdAndMemberReference(tenantId, row.getMemberReference())
                .orElseThrow(() -> new InvalidPolicyStateException(
                    "Reference " + row.getMemberReference() + " was judged actionable at "
                        + "submission and cannot be found now; the roll changed underneath "
                        + "this file and it must be resubmitted"));

            PolicyMemberView exitedMember = policyApi.exitMember(submission.getPolicyNumber(),
                member.getPolicyMemberId(), row.getExitDate(), row.getExitReason(),
                row.getOutstandingBalanceAtExit(), acceptedBy);
            row.becameExit(exitedMember.policyMemberId());
            exited++;
        }

        submission.accept(acceptedBy, exited);
        return toView(submission);
    }

    @Override
    @Transactional
    public ExitSubmissionView withdraw(UUID submissionId, String withdrawnBy) {
        UUID tenantId = TenantContext.get();
        ExitSubmission submission = findSubmission(submissionId, tenantId);
        try {
            submission.withdraw();
        } catch (IllegalStateException e) {
            throw new InvalidPolicyStateException(e.getMessage());
        }
        return toView(submission);
    }

    @Override
    @Transactional(readOnly = true)
    public ExitSubmissionView getSubmission(UUID submissionId) {
        return toView(findSubmission(submissionId, TenantContext.get()));
    }

    /**
     * The scheme's exits history. Uses the repository's
     * {@code findByTenantIdAndPolicyNumberOrderBySubmittedAtDesc}, which existed with no caller
     * until now — the ordering it already declared is the one this needs.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ExitSubmissionView> listSubmissions(String policyNumber) {
        return submissionRepository
            .findByTenantIdAndPolicyNumberOrderBySubmittedAtDesc(TenantContext.get(), policyNumber)
            .stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExitRowView> listRows(UUID submissionId) {
        UUID tenantId = TenantContext.get();
        findSubmission(submissionId, tenantId); // not-found rather than an empty list
        return rowRepository.findByTenantIdAndSubmissionIdOrderByLineNumberAsc(tenantId, submissionId)
            .stream()
            .map(row -> new ExitRowView(row.getLineNumber(), row.getMemberReference(),
                row.getExitDate(), row.getExitReason(), row.getOutstandingBalanceAtExit(),
                row.getOutcome(), row.getReasonCode(), row.getReason(), row.getPolicyMemberId()))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public String renderReport(UUID submissionId) {
        return ExitReportRenderer.toCsv(listRows(submissionId));
    }

    private ExitSubmission findSubmission(UUID submissionId, UUID tenantId) {
        return submissionRepository.findBySubmissionIdAndTenantId(submissionId, tenantId)
            .orElseThrow(() -> new InvalidPolicyStateException(
                "No exits file " + submissionId + " in this tenant"));
    }

    private GroupScheme findScheme(String policyNumber, UUID tenantId) {
        return groupSchemeRepository.findByPolicyNumberAndTenantId(policyNumber, tenantId)
            .orElseThrow(() -> new InvalidPolicyStateException(
                "Policy " + policyNumber + " is not a scheme, so it has no loans to take off cover"));
    }

    private ExitSubmissionView toView(ExitSubmission s) {
        return new ExitSubmissionView(s.getSubmissionId(), s.getPolicyNumber(), s.getStatus(),
            s.getFileName(), s.getRowCount(), s.getExitedCount(), s.getRejectedCount(),
            s.getSubmittedBy(), s.getSubmittedAt(), s.getAcceptedBy(), s.getAcceptedAt());
    }

    /**
     * Detected from the file's own bytes rather than a declared content type: a browser posting
     * a .xlsx routinely sends application/octet-stream, and a lender's mail client is worse.
     * PK\003\004 is the ZIP local file header every OOXML workbook starts with.
     */
    private static boolean looksLikeXlsx(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 0x50 && bytes[1] == 0x4B
            && bytes[2] == 0x03 && bytes[3] == 0x04;
    }

    private static byte[] readFully(InputStream file) {
        try {
            return file.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded exits file", e);
        }
    }
}
