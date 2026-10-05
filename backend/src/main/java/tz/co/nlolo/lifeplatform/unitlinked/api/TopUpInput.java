package tz.co.nlolo.lifeplatform.unitlinked.api;

import tz.co.nlolo.lifeplatform.underwriting.api.UnitLinkedChoice;

import java.math.BigDecimal;
import java.util.List;

/** A top-up as staff record it (U2): the amount, who pays it, and optionally its own split (else the one in force). */
public record TopUpInput(BigDecimal amount, String payerRef, List<UnitLinkedChoice.Split> split) {}
