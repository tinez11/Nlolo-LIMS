package tz.co.nlolo.lifeplatform.reporting.api;

public enum GroupBy {
    PRODUCT("Product"), PRODUCT_CATEGORY("Product category"), AGENT("Agent"), CHANNEL("Sales channel"), BRANCH("Branch");

    private final String label;

    GroupBy(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
