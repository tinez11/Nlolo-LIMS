package tz.co.nlolo.lifeplatform.accumulation.api;

import java.time.Instant;
import java.util.UUID;

public record MaturityInstructionView(UUID instructionId, UUID periodId, MaturityAction action, Integer termMonths,
                                      String payeeRef, String recordedBy, Instant recordedAt) {}
