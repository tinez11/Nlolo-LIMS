-- One accrual may now be reversed MORE THAN ONCE, in parts.
--
-- ux_commission_accrual_single_reversal (V2) allowed exactly one reversal per accrual. That was
-- right for every clawback that existed when it was written: a lapse reverses the WHOLE
-- first-year commission, so a second reversal of the same accrual could only ever be a
-- redelivered event.
--
-- Credit life breaks that assumption, and legitimately. One accepted enrolment file accrues one
-- commission on the whole file's premium, and its borrowers then settle early ONE AT A TIME --
-- four hundred of them, each refunding their own share and each clawing back the commission on
-- that share. Every one of those is a genuine, distinct partial reversal of the same accrual.
--
-- The invariant is not dropped, it is made precise: one reversal per accrual PER SOURCE. The
-- lapse path passes the reversed accrual's own id as source_ref, so it still gets exactly one
-- reversal and its guarantee is unchanged. The credit-life path passes the departing member's
-- id, so each borrower reverses once and a redelivered exit still finds it already booked.
--
-- The alternative was to accrue per BORROWER rather than per file, which would have kept the
-- old index intact. Rejected on volume: four hundred accrual rows per monthly file, each
-- insert re-reading a growing statement line-item list to recompute its total -- quadratic work
-- per file, to avoid widening one index by a column.

DROP INDEX IF EXISTS distribution.ux_commission_accrual_single_reversal;

CREATE UNIQUE INDEX ux_commission_accrual_single_reversal_per_source
    ON distribution.commission_accrual (reverses_accrual_id, source_ref)
    WHERE reverses_accrual_id IS NOT NULL;
