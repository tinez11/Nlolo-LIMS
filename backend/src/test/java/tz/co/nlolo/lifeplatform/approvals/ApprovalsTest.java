package tz.co.nlolo.lifeplatform.approvals;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalSource;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalsView;
import tz.co.nlolo.lifeplatform.approvals.application.Approvals;
import tz.co.nlolo.lifeplatform.iam.api.AccessApi;
import tz.co.nlolo.lifeplatform.iam.api.CurrentAccess;
import tz.co.nlolo.lifeplatform.iam.api.Permission;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The gathering, with no database: who sees which kind, what counts, and what an unreadable module looks like. */
class ApprovalsTest {

    private static final Instant T = Instant.parse("2026-10-27T07:00:00Z");

    private static ApprovalSource source(String kind, String permission, boolean twoPeople, ApprovalSource.Item... items) {
        return ApprovalSource.of(kind, kind + " label", "Approve", permission, twoPeople,
            limit -> ApprovalSource.Pending.of(List.of(items), limit));
    }

    private static ApprovalSource.Item item(String ref, String by) {
        return new ApprovalSource.Item(ref, "POL-1", "summary " + ref, new BigDecimal("1500"), null, by, T);
    }

    private static CurrentAccess holding(Permission... held) {
        Set<Permission> set = Set.of(held);
        return set::contains;
    }

    private static AccessApi naming(Map<String, String> names) {
        return new AccessApi() {
            @Override public AccessView accessOf(StaffIdentity identity) { throw new UnsupportedOperationException(); }
            @Override public Map<String, String> displayNames(Collection<String> subjects) { return names; }
        };
    }

    @Test
    void aKindIsShownOnlyToWhoeverHoldsItsPermission() {
        Approvals approvals = new Approvals(List.of(
            source("account-withdrawal", "accounts.approve", true, item("w1", "alice")),
            source("manual-journal", "ledger.approve", true, item("j1", "bob"))),
            holding(Permission.ACCOUNTS_APPROVE), naming(Map.of()));

        ApprovalsView view = approvals.forViewer("carol");

        assertThat(view.groups()).extracting(ApprovalsView.Group::kind).containsExactly("account-withdrawal");
        assertThat(view.actionable()).isEqualTo(1);
    }

    @Test
    void theViewersOwnRequestIsListedAsTheirsAndNotCounted() {
        Approvals approvals = new Approvals(List.of(
            source("account-withdrawal", "accounts.approve", true, item("w1", "alice"), item("w2", "carol"))),
            holding(Permission.ACCOUNTS_APPROVE), naming(Map.of("alice", "Alice Officer")));

        ApprovalsView view = approvals.forViewer("carol");

        ApprovalsView.Group group = view.groups().get(0);
        assertThat(group.total()).isEqualTo(2);
        assertThat(group.actionable()).isEqualTo(1);
        assertThat(group.rows()).extracting(ApprovalsView.Row::yours).containsExactly(false, true);
        assertThat(group.rows().get(0).requestedByName()).isEqualTo("Alice Officer");
        assertThat(group.rows().get(0).amount()).isEqualTo(new ApprovalsView.Money("1500.00", "TZS"));
        assertThat(view.actionable()).isEqualTo(1);
    }

    @Test
    void aQueueNobodyAskedForCountsEveryRow() {
        Approvals approvals = new Approvals(List.of(
            source("bank-transfer", "payouts.manage", false, item("d1", null), item("d2", null))),
            holding(Permission.PAYOUTS_MANAGE), naming(Map.of()));

        assertThat(approvals.forViewer("carol").actionable()).isEqualTo(2);
    }

    @Test
    void aModuleThatCannotBeReadIsUnreadableNeverZero() {
        ApprovalSource broken = ApprovalSource.of("surrender", "Surrenders", "Approve", "policies.surrender-approve",
            limit -> { throw new IllegalStateException("module down"); });
        Approvals approvals = new Approvals(List.of(broken,
            source("account-withdrawal", "accounts.approve", true, item("w1", "alice"))),
            holding(Permission.ACCOUNTS_APPROVE, Permission.POLICIES_SURRENDER_APPROVE), naming(Map.of()));

        ApprovalsView view = approvals.forViewer("carol");

        assertThat(view.groups()).filteredOn(ApprovalsView.Group::unreadable)
            .extracting(ApprovalsView.Group::kind).containsExactly("surrender");
        assertThat(view.actionable()).isEqualTo(1);
    }

    @Test
    void namesThatCannotBeReadLeaveThePageStanding() {
        AccessApi failing = new AccessApi() {
            @Override public AccessView accessOf(StaffIdentity identity) { throw new UnsupportedOperationException(); }
            @Override public Map<String, String> displayNames(Collection<String> subjects) {
                throw new IllegalStateException("iam down");
            }
        };
        Approvals approvals = new Approvals(List.of(source("account-withdrawal", "accounts.approve", true,
            item("w1", "alice"))), holding(Permission.ACCOUNTS_APPROVE), failing);

        ApprovalsView.Row row = approvals.forViewer("carol").groups().get(0).rows().get(0);
        assertThat(row.requestedBy()).isEqualTo("alice");
        assertThat(row.requestedByName()).isNull();
    }
}
