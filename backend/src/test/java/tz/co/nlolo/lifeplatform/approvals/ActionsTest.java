package tz.co.nlolo.lifeplatform.approvals;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.approvals.api.ActionSource;
import tz.co.nlolo.lifeplatform.approvals.api.ActionsView;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalSource;
import tz.co.nlolo.lifeplatform.approvals.application.Actions;
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

/** The bell's gathering, with no database (2026-11-03): whose jobs are whose, the grouping, and a module that fails. */
class ActionsTest {

    private static final Instant EARLY = Instant.parse("2026-11-01T07:00:00Z");
    private static final Instant LATE = Instant.parse("2026-11-03T07:00:00Z");
    private static final ActionSource.Viewer STAFF = new ActionSource.Viewer("carol", null);

    private static ActionSource.Item item(String ref, Instant since) {
        return new ActionSource.Item(ref, null, "Client " + ref, "detail " + ref, since);
    }

    private static CurrentAccess holding(Permission... held) {
        Set<Permission> set = Set.of(held);
        return set::contains;
    }

    private static Approvals approvals(CurrentAccess access, ApprovalSource... sources) {
        return new Approvals(List.of(sources), access, new AccessApi() {
            @Override public AccessView accessOf(StaffIdentity identity) { throw new UnsupportedOperationException(); }
            @Override public Map<String, String> displayNames(Collection<String> subjects) { return Map.of(); }
        });
    }

    @Test
    void staffGetOneGroupPerKindTheyActOnTheNewestKindFirstAndNeverAnAgentsJobs() {
        CurrentAccess access = holding(Permission.UNDERWRITING_ASSESS, Permission.CLAIMS_ASSESS);
        Actions actions = new Actions(List.of(
            ActionSource.forStaff("proposal-review", "underwriting.assess", "Review it",
                (v, limit) -> ActionSource.Pending.of(List.of(item("case-1", EARLY), item("case-2", EARLY)), limit)),
            ActionSource.forStaff("claim-assess", "claims.assess", "Assess it",
                (v, limit) -> ActionSource.Pending.of(List.of(item("claim-1", LATE)), limit)),
            ActionSource.forStaff("claim-settle", "claims.settle", "Decide it",
                (v, limit) -> ActionSource.Pending.of(List.of(item("claim-2", LATE)), limit)),
            ActionSource.forAgents("proposal-returned", "Correct it",
                (v, limit) -> ActionSource.Pending.of(List.of(item("case-3", LATE)), limit))),
            access, approvals(access));

        ActionsView view = actions.forViewer(STAFF, false);

        assertThat(view.total()).isEqualTo(3);
        assertThat(view.incomplete()).isFalse();
        assertThat(view.groups()).extracting(ActionsView.Group::kind).containsExactly("claim-assess", "proposal-review");
        assertThat(view.groups().get(1).total()).isEqualTo(2);
        assertThat(view.groups().get(0).items().get(0).title()).isEqualTo("Client claim-1");
    }

    @Test
    void aKindWithNothingWaitingIsLeftOut() {
        CurrentAccess access = holding(Permission.CLAIMS_ASSESS);
        Actions actions = new Actions(List.of(
            ActionSource.forStaff("claim-assess", "claims.assess", "Assess it",
                (v, limit) -> ActionSource.Pending.of(List.of(), limit))),
            access, approvals(access));

        assertThat(actions.forViewer(STAFF, false).groups()).isEmpty();
    }

    @Test
    void anAgentGetsOnlyTheirOwnWorkWhateverTheirTokenHolds() {
        CurrentAccess access = holding(Permission.UNDERWRITING_ASSESS);
        Actions actions = new Actions(List.of(
            ActionSource.forStaff("proposal-review", "underwriting.assess", "Review it",
                (v, limit) -> ActionSource.Pending.of(List.of(item("case-1", EARLY)), limit)),
            ActionSource.forAgents("proposal-returned", "Correct it",
                (v, limit) -> ActionSource.Pending.of(List.of(item("case-for-" + v.subject(), LATE)), limit))),
            access, approvals(access));

        ActionsView view = actions.forViewer(new ActionSource.Viewer("agent-7", null), true);

        assertThat(view.groups()).singleElement().satisfies(g ->
            assertThat(g.items()).extracting(ActionsView.Item::reference).containsExactly("case-for-agent-7"));
    }

    @Test
    void aModuleThatCannotBeReadMakesTheBellIncompleteNotEmpty() {
        CurrentAccess access = holding(Permission.CLAIMS_ASSESS, Permission.UNDERWRITING_ASSESS);
        Actions actions = new Actions(List.of(
            ActionSource.forStaff("claim-assess", "claims.assess", "Assess it", (v, limit) -> {
                throw new IllegalStateException("claims is down");
            }),
            ActionSource.forStaff("proposal-review", "underwriting.assess", "Review it",
                (v, limit) -> ActionSource.Pending.of(List.of(item("case-1", EARLY)), limit))),
            access, approvals(access));

        ActionsView view = actions.forViewer(STAFF, false);

        assertThat(view.incomplete()).isTrue();
        assertThat(view.total()).isEqualTo(1);
    }

    @Test
    void signaturesAreOneGroupWithARowPerKindAndTheViewersOwnAreNotCounted() {
        CurrentAccess access = holding(Permission.ACCOUNTS_APPROVE);
        ApprovalSource withdrawals = ApprovalSource.of("account-withdrawal", "Savings withdrawals", "Approve",
            "accounts.approve", limit -> ApprovalSource.Pending.of(List.of(
                new ApprovalSource.Item("w1", "POL-1", "w1", BigDecimal.TEN, "TZS", "alice", EARLY),
                new ApprovalSource.Item("w2", "POL-2", "w2", BigDecimal.TEN, "TZS", "bob", LATE),
                new ApprovalSource.Item("w3", "POL-3", "w3", BigDecimal.TEN, "TZS", "carol", LATE)), limit));
        Actions actions = new Actions(List.of(), access, approvals(access, withdrawals));

        ActionsView view = actions.forViewer(STAFF, false);

        assertThat(view.total()).isEqualTo(2);
        assertThat(view.groups()).singleElement().satisfies(g -> {
            assertThat(g.kind()).isEqualTo("signatures");
            assertThat(g.total()).isEqualTo(2);
            assertThat(g.items()).singleElement().satisfies(i -> {
                assertThat(i.title()).isEqualTo("Savings withdrawals");
                assertThat(i.detail()).isEqualTo("2 waiting");
                assertThat(i.since()).isEqualTo(LATE);
            });
        });
    }
}
