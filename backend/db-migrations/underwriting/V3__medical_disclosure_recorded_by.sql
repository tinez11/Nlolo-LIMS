-- Record WHO took the disclosure, not just what was said.
--
-- medical_disclosure has existed since V1 with tenant, case, a JSONB question/response set,
-- document refs and a timestamp -- and zero call sites anywhere in the codebase. It is now
-- written and read for real, and the one column a contestability argument needs that it did
-- not have is the person who put the questions and wrote the answers down.
--
-- Without it the record says "someone was asked this and answered that", which is exactly the
-- part a contest turns on: an applicant disputing a non-disclosure finding is disputing what
-- they were asked and by whom. created_at alone cannot answer that.
--
-- Nullable, because the rows that exist before this column do not have one -- there are none
-- today, but a NOT NULL with no default would make this migration order-dependent for no gain.

ALTER TABLE underwriting.medical_disclosure
    ADD COLUMN recorded_by VARCHAR(100);

COMMENT ON COLUMN underwriting.medical_disclosure.recorded_by IS
    'JWT subject of whoever recorded the disclosures -- the agent or staff member who took the proposal.';
