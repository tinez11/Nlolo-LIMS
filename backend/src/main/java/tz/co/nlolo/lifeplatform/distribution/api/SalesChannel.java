package tz.co.nlolo.lifeplatform.distribution.api;

/**
 * The channel a sale comes through (IFRS 17 spec §6; refdata SALES_CHANNEL). An agent is one of the first three --
 * {@link #isAgentChannel} -- while DIRECT and DIGITAL are sales no intermediary made.
 */
public enum SalesChannel {
    AGENT, BROKER, BANCASSURANCE, DIRECT, DIGITAL;

    /** Whether an intermediary (an agent profile) can sell through this channel. */
    public boolean isAgentChannel() {
        return this == AGENT || this == BROKER || this == BANCASSURANCE;
    }
}
