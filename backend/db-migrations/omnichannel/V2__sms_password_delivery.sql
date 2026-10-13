-- Portal invites by SMS (2026-10-13, the deployment pilot design §6): a third way a client's first password reaches
-- them. Until now a client with no email address could only be given one by a staff member reading it out, which is
-- the right fallback but a poor default in a market where almost every policyholder has a phone and few have email.
ALTER TABLE omnichannel.portal_access DROP CONSTRAINT portal_access_delivery_check;
ALTER TABLE omnichannel.portal_access ADD CONSTRAINT portal_access_delivery_check
    CHECK (delivery IN ('EMAIL_LINK', 'TEMPORARY_PASSWORD', 'SMS_PASSWORD'));
