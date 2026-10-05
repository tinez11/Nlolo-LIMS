package tz.co.nlolo.lifeplatform.unitlinked.api;

import tz.co.nlolo.lifeplatform.underwriting.api.UnitLinkedChoice;

import java.util.List;

/**
 * A switch as staff record it (U2, spec §2): for each fund to move out of, the share of its units (1-100, 100 = all);
 * and the split the proceeds buy into, in whole percents totalling 100.
 */
public record SwitchInput(List<Out> out, List<UnitLinkedChoice.Split> into) {

    public record Out(String fundCode, int percent) {}
}
