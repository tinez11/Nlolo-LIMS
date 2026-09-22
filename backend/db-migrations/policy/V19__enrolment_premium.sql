-- What a file costs, and what each borrower on it contributed to that.
--
-- Premium is an agreed percent per annum of the original principal, charged once at
-- enrolment (spec 2.8). So the amount is knowable the moment a file is accepted, and this is
-- where it is recorded -- rather than re-derived later from the members, which would drift
-- the moment one of them exits and their loan stops being part of the roll.

-- Per borrower, so a refund can be computed against what THIS loan was actually charged
-- rather than against a share of the file total. The two differ once a rate changes between
-- files, and the refund must follow the money that was taken.
ALTER TABLE policy.enrolment_submission_row
    ADD COLUMN premium_amount NUMERIC(19,2);

ALTER TABLE policy.enrolment_submission_row
    ADD CONSTRAINT chk_enrolment_row_premium_positive
        CHECK (premium_amount IS NULL OR premium_amount > 0);

-- A priced row is an enrolled row and vice versa: a rejected borrower is not charged, and an
-- enrolled one who somehow escaped pricing would be insured for free.
--
-- Only bites on rows written after acceptance -- a PENDING submission's rows are judged but
-- not yet priced, and they are all still ENROLLED/ENROLLED_CAPPED at that point. So the check
-- is one-directional: a REJECTED row may not carry a premium. The positive half is the
-- service's job, and the enrolled-count arithmetic below is what makes it checkable.
ALTER TABLE policy.enrolment_submission_row
    ADD CONSTRAINT chk_enrolment_row_rejected_is_not_charged
        CHECK (outcome <> 'REJECTED' OR premium_amount IS NULL);

-- The file total. One invoice is raised for exactly this figure.
ALTER TABLE policy.enrolment_submission
    ADD COLUMN premium_total NUMERIC(19,2);

-- An accepted file states what it cost; a pending one has not been priced yet.
--
-- Zero is legal and meaningful here, unlike on the row: a file where every row was rejected
-- enrols nobody, earns nothing, and must not raise an invoice. Distinguishing "accepted and
-- worth nothing" from "not yet accepted" is why this is a CHECK on status rather than a
-- NOT NULL.
--
-- Folded into V15's existing accepted-complete check rather than added beside it. Two
-- separate CHECKs on the same row would fire in whatever order Postgres happens to evaluate
-- them, so "what is wrong with this acceptance" would get a different answer on different
-- days -- and the test that names the violated constraint would be quietly flaky. What an
-- accepted submission must carry is one rule, so it is one constraint.
ALTER TABLE policy.enrolment_submission
    DROP CONSTRAINT chk_enrolment_submission_accepted_complete;

ALTER TABLE policy.enrolment_submission
    ADD CONSTRAINT chk_enrolment_submission_accepted_complete CHECK (
        status <> 'ACCEPTED'
        OR (accepted_by IS NOT NULL AND accepted_at IS NOT NULL AND premium_total IS NOT NULL));

ALTER TABLE policy.enrolment_submission
    ADD CONSTRAINT chk_enrolment_submission_premium_not_negative
        CHECK (premium_total IS NULL OR premium_total >= 0);
