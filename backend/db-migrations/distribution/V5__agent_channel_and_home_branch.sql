-- db-migrations/distribution/V5__agent_channel_and_home_branch.sql
-- IFRS 17 I2 (classification at sale, spec §6): every agent sells through a channel and from a home branch. A case
-- an agent introduces takes both as its defaults; the policy issued from it carries them for good, and they become
-- posting dimensions (posting guide 2.2).
--
-- An agent's channel is one of the three an intermediary can be: a tied agent, a broker, or a bank (bancassurance).
-- DIRECT and DIGITAL are channels a sale can have, but no agent's.
--
-- The branch is a refdata BRANCH code, validated by the application (refdata is global and carries no foreign key).
ALTER TABLE distribution.agent_profile
    ADD COLUMN sales_channel VARCHAR(20) NOT NULL DEFAULT 'AGENT'
        CHECK (sales_channel IN ('AGENT','BROKER','BANCASSURANCE')),
    ADD COLUMN home_branch VARCHAR(10);

-- The agents that exist today sell from head office: the platform's two seeded logins are Dar es Salaam agents. A
-- new agent is onboarded with a branch named.
UPDATE distribution.agent_profile SET home_branch = 'DSM' WHERE home_branch IS NULL;
