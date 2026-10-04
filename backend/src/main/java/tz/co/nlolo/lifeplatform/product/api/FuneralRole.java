package tz.co.nlolo.lifeplatform.product.api;

/** Who a life on a funeral plan is, relative to the main member who owns and pays for the policy. */
public enum FuneralRole {
    MAIN_MEMBER("main member", "main members"),
    SPOUSE("spouse", "spouses"),
    CHILD("child", "children"),
    PARENT("parent", "parents"),
    EXTENDED("extended family member", "extended family members");

    private final String label;
    private final String plural;

    FuneralRole(String label, String plural) {
        this.label = label;
        this.plural = plural;
    }

    /** How a refusal names one of these lives: "a child must be 0 to 20 at entry". */
    public String label() {
        return label;
    }

    public String plural() {
        return plural;
    }
}
