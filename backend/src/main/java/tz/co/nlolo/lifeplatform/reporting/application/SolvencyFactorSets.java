package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.reporting.api.NewSolvencyFactorSet;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactorSetView;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactors;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactors.Charge;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactors.Shock;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactors.Threshold;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.SolvencyFactorStore;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Which solvency factors a month is worked out on, and the publishing of new ones (2026-11-09, decision D5).
 *
 * <p>A month uses the newest version in force at its end. A company that has published nothing -- or nothing in force
 * yet -- is on the built-in placeholder set, and every solvency report says so.
 */
@Service
public class SolvencyFactorSets {

    private static final Pattern ACCOUNT = Pattern.compile("\\d{4}");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final SolvencyFactorStore store;

    public SolvencyFactorSets(SolvencyFactorStore store) {
        this.store = store;
    }

    /** Every version, newest first, ending with the built-in placeholder set. */
    public List<SolvencyFactorSetView> all(UUID tenantId) {
        List<SolvencyFactorSetView> out = new ArrayList<>(store.all(tenantId));
        out.add(PlaceholderFactors.SET);
        return out;
    }

    public SolvencyFactorSetView forMonth(UUID tenantId, YearMonth month) {
        return store.all(tenantId).stream().filter(s -> !s.effectiveFrom().isAfter(month.atEndOfMonth()))
            .findFirst().orElse(PlaceholderFactors.SET);
    }

    public SolvencyFactorSetView publish(UUID tenantId, NewSolvencyFactorSet set, String createdBy) {
        validate(set);
        int version = store.insert(tenantId, set, createdBy);
        return store.all(tenantId).stream().filter(s -> s.version() == version).findFirst().orElseThrow();
    }

    /** Refused in words a person can act on; nothing half-valid is stored. */
    static void validate(NewSolvencyFactorSet set) {
        if (set.name() == null || set.name().isBlank()) throw new ReportParameterException("Give the factor set a name.");
        if (set.name().length() > 200) throw new ReportParameterException("The name is longer than 200 characters.");
        if (set.effectiveFrom() == null) throw new ReportParameterException("Say from when the factors apply.");
        SolvencyFactors f = set.factors();
        if (f == null) throw new ReportParameterException("The factors are missing.");
        charges("Insurance risk", f.insurance());
        charges("Market risk", f.market());
        charges("Credit risk", f.credit());
        rate("The operational risk rate", f.operationalPercent());
        if (f.inadmissible() == null) throw new ReportParameterException("The inadmissible accounts are missing.");
        f.inadmissible().forEach(code -> account("An inadmissible account", code));
        if (!SolvencyFactors.ROOT_SUM_OF_SQUARES.equals(f.combination())
            && !SolvencyFactors.SIMPLE_SUM.equals(f.combination())) {
            throw new ReportParameterException("Choose how the charges combine.");
        }
        if (f.thresholds() == null || f.thresholds().isEmpty()) {
            throw new ReportParameterException("Give at least one threshold, such as the minimum (MCR).");
        }
        Set<String> keys = new HashSet<>();
        for (Threshold t : f.thresholds()) {
            if (t.label() == null || t.label().isBlank()) throw new ReportParameterException("Every threshold needs a name.");
            if (t.ratioPercent() == null || t.ratioPercent().signum() <= 0) {
                throw new ReportParameterException(t.label() + ": the ratio must be above zero.");
            }
            if (!keys.add(t.key())) throw new ReportParameterException("Two thresholds share the key " + t.key() + ".");
        }
        if (f.shocks() == null) throw new ReportParameterException("The stress shocks are missing.");
        for (Shock s : f.shocks()) shock(s);
    }

    private static void charges(String risk, List<Charge> charges) {
        if (charges == null) throw new ReportParameterException(risk + ": the charges are missing.");
        Set<String> keys = new HashSet<>();
        for (Charge c : charges) {
            if (c.label() == null || c.label().isBlank()) throw new ReportParameterException(risk + ": every charge needs a name.");
            if (!keys.add(c.key())) throw new ReportParameterException(risk + ": two charges share the key " + c.key() + ".");
            rate(c.label(), c.percent());
            if (c.accounts() == null || c.accounts().isEmpty()) {
                throw new ReportParameterException(c.label() + ": name at least one account.");
            }
            c.accounts().forEach(code -> account(c.label(), code));
        }
    }

    private static void shock(Shock s) {
        if (s.label() == null || s.label().isBlank()) throw new ReportParameterException("Every shock needs a name.");
        switch (s.kind() == null ? "" : s.kind()) {
            case Shock.ASSETS -> {
                if (s.percent() == null || s.percent().compareTo(HUNDRED.negate()) < 0 || s.percent().compareTo(HUNDRED) > 0) {
                    throw new ReportParameterException(s.label() + ": the move must be between -100% and 100%.");
                }
                if (s.accounts() == null || s.accounts().isEmpty()) {
                    throw new ReportParameterException(s.label() + ": name the accounts it moves.");
                }
                s.accounts().forEach(code -> account(s.label(), code));
            }
            case Shock.INSURANCE -> {
                if (s.percent() == null || s.percent().compareTo(HUNDRED.negate()) < 0) {
                    throw new ReportParameterException(s.label() + ": the change must be -100% or more.");
                }
            }
            case Shock.LOSS -> {
                if (s.amount() == null || s.amount().signum() < 0) {
                    throw new ReportParameterException(s.label() + ": the loss must be zero or more.");
                }
            }
            default -> throw new ReportParameterException(s.label() + ": choose what the shock acts on.");
        }
    }

    private static void rate(String what, BigDecimal percent) {
        if (percent == null || percent.signum() < 0 || percent.compareTo(HUNDRED) > 0) {
            throw new ReportParameterException(what + ": the rate must be between 0% and 100%.");
        }
    }

    private static void account(String what, String code) {
        if (code == null || !ACCOUNT.matcher(code).matches()) {
            throw new ReportParameterException(what + ": " + code + " is not a four-digit account code.");
        }
    }
}
