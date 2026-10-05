package tz.co.nlolo.lifeplatform.finaccounting.api;

/**
 * Who wrote a journal (IFRS 17 spec §5.2-5.3): an EVENT from the policy system; a SYSTEM run of the platform's own
 * (PAA earning, an auto-reversal, the year-end close); an ENGINE_RUN loaded from the IFRS 17 engine; or a MANUAL
 * journal prepared and approved by two people. An account's posting mode decides which of these may post to it.
 */
public enum JournalSource { EVENT, SYSTEM, ENGINE_RUN, MANUAL }
