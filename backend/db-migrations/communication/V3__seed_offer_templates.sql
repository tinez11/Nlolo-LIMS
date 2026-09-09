-- db-migrations/communication/V3__seed_offer_templates.sql
-- The offer lifecycle, in the customer's own words.
--
-- Four messages x two channels x two languages. Swahili first per key: `sw` is the
-- customer-facing default (V1's own comment on the language CHECK says so), `en` is the
-- back-office fallback.
--
-- SMS bodies are deliberately short. A Tanzanian aggregator bills per 160-character segment, so
-- a two-segment reminder costs twice as much to say the same thing -- and every one of these is
-- sent to every offer, several times over its life. Email bodies can afford a sentence more,
-- and use it to say what to do next rather than to say the same thing at greater length.
--
-- The four keys and their placeholders, which TemplateRenderer will refuse to leave unfilled:
--   OFFER_MADE     policyNumber, premium, expiryDate
--   OFFER_CLOSING  policyNumber, expiryDate
--   COVER_STARTED  policyNumber
--   OFFER_EXPIRED  policyNumber
--
-- Seeded for the dev tenant only. Templates are tenant-scoped by V2's unique index, and a second
-- tenant seeding its own wording is a real requirement rather than something to guess at here.

INSERT INTO communication.notification_template (tenant_id, template_key, channel, language, body_template) VALUES

-- OFFER_MADE -- the policy exists, nobody is covered yet, and there is a deadline.
-- Says the deadline and the amount, because "your policy is ready" without either reads as
-- confirmation that everything is done.
('11111111-1111-1111-1111-111111111111', 'OFFER_MADE', 'SMS', 'sw',
 'Ofa ya bima {{policyNumber}} iko tayari. Lipa {{premium}} kabla ya {{expiryDate}} ili ulinzi uanze. Hujaanza kulindwa bado.'),
('11111111-1111-1111-1111-111111111111', 'OFFER_MADE', 'SMS', 'en',
 'Your cover offer {{policyNumber}} is ready. Pay {{premium}} by {{expiryDate}} to start it. You are not covered yet.'),
('11111111-1111-1111-1111-111111111111', 'OFFER_MADE', 'EMAIL', 'sw',
 'Ofa ya bima {{policyNumber}} iko tayari. Lipa {{premium}} kabla ya {{expiryDate}} ili ulinzi uanze. Hadi malipo ya kwanza yakamilike, hujaanza kulindwa na hakuna dai linaloweza kulipwa. Ukishalipa tutakutumia ujumbe wa uthibitisho.'),
('11111111-1111-1111-1111-111111111111', 'OFFER_MADE', 'EMAIL', 'en',
 'Your cover offer {{policyNumber}} is ready. Pay {{premium}} by {{expiryDate}} to start it. Until the first premium clears you are not covered and no claim can be settled. We will confirm as soon as your payment reaches us.'),

-- OFFER_CLOSING -- the reminder. The one message that exists to prevent an outcome.
('11111111-1111-1111-1111-111111111111', 'OFFER_CLOSING', 'SMS', 'sw',
 'Kumbusho: ofa ya bima {{policyNumber}} inafungwa {{expiryDate}}. Lipa kabla ya tarehe hiyo ili ulinzi uanze.'),
('11111111-1111-1111-1111-111111111111', 'OFFER_CLOSING', 'SMS', 'en',
 'Reminder: your cover offer {{policyNumber}} closes on {{expiryDate}}. Pay before then to start your cover.'),
('11111111-1111-1111-1111-111111111111', 'OFFER_CLOSING', 'EMAIL', 'sw',
 'Kumbusho: ofa ya bima {{policyNumber}} inafungwa tarehe {{expiryDate}}. Ikifungwa bila malipo, itabidi uombe upya na majibu ya afya yatahitajika tena. Lipa kabla ya tarehe hiyo ili ulinzi uanze.'),
('11111111-1111-1111-1111-111111111111', 'OFFER_CLOSING', 'EMAIL', 'en',
 'Reminder: your cover offer {{policyNumber}} closes on {{expiryDate}}. If it closes unpaid you will need to apply again, and your health evidence will have to be assessed afresh. Pay before then to start your cover.'),

-- COVER_STARTED -- the money arrived. The one message that says a person is now insured, which
-- is why it must never be sent early: it is driven by the real PROPOSED -> ACTIVE transition.
('11111111-1111-1111-1111-111111111111', 'COVER_STARTED', 'SMS', 'sw',
 'Malipo yamepokelewa. Bima {{policyNumber}} imeanza kutumika. Umeanza kulindwa.'),
('11111111-1111-1111-1111-111111111111', 'COVER_STARTED', 'SMS', 'en',
 'Payment received. Policy {{policyNumber}} is now in force. You are covered.'),
('11111111-1111-1111-1111-111111111111', 'COVER_STARTED', 'EMAIL', 'sw',
 'Malipo yamepokelewa na bima {{policyNumber}} imeanza kutumika. Umeanza kulindwa kuanzia leo. Hifadhi namba hii ya bima; utaihitaji ukiwasiliana nasi au ukileta dai.'),
('11111111-1111-1111-1111-111111111111', 'COVER_STARTED', 'EMAIL', 'en',
 'Your payment has been received and policy {{policyNumber}} is now in force. Your cover starts today. Please keep this policy number; you will need it whenever you contact us or make a claim.'),

-- OFFER_EXPIRED -- terminal, and the message most likely to be the first a customer reads
-- carefully. Says plainly that they are not covered, and that reapplying is the way back.
('11111111-1111-1111-1111-111111111111', 'OFFER_EXPIRED', 'SMS', 'sw',
 'Ofa ya bima {{policyNumber}} imefungwa bila malipo. Hujalindwa. Wasiliana nasi kuomba upya.'),
('11111111-1111-1111-1111-111111111111', 'OFFER_EXPIRED', 'SMS', 'en',
 'Your cover offer {{policyNumber}} has closed unpaid. You are not covered. Contact us to apply again.'),
('11111111-1111-1111-1111-111111111111', 'OFFER_EXPIRED', 'EMAIL', 'sw',
 'Ofa ya bima {{policyNumber}} imefungwa kwa sababu malipo ya kwanza hayakupokelewa kwa muda uliopangwa. Hujalindwa, na hakuna dai linaloweza kulipwa chini ya namba hii. Ukitaka bima, wasiliana nasi ili uombe upya.'),
('11111111-1111-1111-1111-111111111111', 'OFFER_EXPIRED', 'EMAIL', 'en',
 'Your cover offer {{policyNumber}} has closed because the first premium did not reach us in time. You are not covered, and no claim can be settled under this number. If you would still like cover, contact us and we will start a new application.');
