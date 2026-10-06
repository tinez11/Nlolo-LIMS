package tz.co.nlolo.lifeplatform.finaccounting.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An engine run's lines as the journals that post them (IFRS 17 I5a, guide 5.5 step 7): one journal per group, every
 * engine line counter-posted through 9160, the IFRS 17 engine upload clearing account. A group that balances leaves
 * 9160 at zero; one that does not leaves exactly its imbalance there, which is why a run is posted only when the run's
 * 9160 movement nets to zero. Pure.
 */
public final class EngineRunJournals {

    public static final String CLEARING = "9160";

    /** One posting leg: account, DR or CR, amount, the guide movement code if the engine gave one. */
    public record Leg(String account, String side, BigDecimal amount, String movement) {}

    private EngineRunJournals() {}

    /** By group, in the order the groups first appear: each engine leg followed by its 9160 counter-leg. */
    public static Map<String, List<Leg>> legs(List<EngineResults.Line> lines) {
        Map<String, List<Leg>> byGroup = new LinkedHashMap<>();
        for (EngineResults.Line l : lines) {
            List<Leg> legs = byGroup.computeIfAbsent(l.group(), g -> new ArrayList<>());
            legs.add(new Leg(l.account(), l.side(), l.amount(), l.movement()));
            legs.add(new Leg(CLEARING, "DR".equals(l.side()) ? "CR" : "DR", l.amount(), l.movement()));
        }
        return byGroup;
    }

    /** The run's net movement on 9160, Dr - Cr: zero when every group balances. */
    public static BigDecimal clearingNet(Map<String, List<Leg>> legs) {
        BigDecimal net = BigDecimal.ZERO;
        for (List<Leg> group : legs.values()) {
            for (Leg leg : group) {
                if (CLEARING.equals(leg.account())) {
                    net = "DR".equals(leg.side()) ? net.add(leg.amount()) : net.subtract(leg.amount());
                }
            }
        }
        return net;
    }

    public static boolean balanced(List<Leg> legs) {
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        for (Leg leg : legs) {
            if ("DR".equals(leg.side())) {
                dr = dr.add(leg.amount());
            } else {
                cr = cr.add(leg.amount());
            }
        }
        return dr.compareTo(cr) == 0;
    }
}
