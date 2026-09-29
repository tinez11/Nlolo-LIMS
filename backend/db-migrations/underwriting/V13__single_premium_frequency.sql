-- The frequency a case is opened on must admit everything a policy can be written on.
--
-- SINGLE was already admitted by policy.policy's own CHECK, by PremiumFrequency, by this
-- module's OpenAPI schema and by the console -- and refused here, three layers down, as a
-- 500 from a constraint violation. A proposal could therefore be taken for a contract the
-- platform was perfectly able to issue.
--
-- This column mirrors policy.policy.premium_frequency: the case records what the applicant
-- intends to pay, and issuance carries it onto the policy verbatim. The two lists have to
-- agree, and this is the second of them. Any frequency added to one belongs in both, and in
-- OpenCaseRequest's @Pattern, which is the third.
ALTER TABLE underwriting.underwriting_case
    DROP CONSTRAINT underwriting_case_premium_frequency_check;

ALTER TABLE underwriting.underwriting_case
    ADD CONSTRAINT underwriting_case_premium_frequency_check
        CHECK (premium_frequency IN ('MONTHLY', 'QUARTERLY', 'ANNUALLY', 'SINGLE'));
