-- 2026-11-14, staff sign-in code (spec 2026-11-14-staff-sign-in-code-design.md): the language a staff member's texts are
-- in -- their sign-in code. English unless an administrator says otherwise. Re-runnable.
ALTER TABLE iam.staff_user ADD COLUMN IF NOT EXISTS text_language VARCHAR(2) NOT NULL DEFAULT 'en';
ALTER TABLE iam.staff_user DROP CONSTRAINT IF EXISTS ck_staff_user_text_language;
ALTER TABLE iam.staff_user ADD CONSTRAINT ck_staff_user_text_language CHECK (text_language IN ('en', 'sw'));
