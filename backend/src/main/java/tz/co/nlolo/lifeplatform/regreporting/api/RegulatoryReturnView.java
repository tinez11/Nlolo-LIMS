package tz.co.nlolo.lifeplatform.regreporting.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A generated return and its lines.
 *
 * <p>{@code documentRef} is always null: rendering a submission artifact requires TIRA's file
 * format, which is C2-blocked. It is exposed so an API consumer sees the field exists and is
 * unpopulated, rather than discovering later that it was silently omitted.
 */
public record RegulatoryReturnView(UUID returnId, String returnType, String period, String status,
                                    String documentRef, Instant generatedAt, String generatedBy,
                                    List<ReturnLineView> lines) {}
