package tz.co.nlolo.lifeplatform.finaccounting.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code @IdClass} for {@link GlPosting}'s composite primary key
 * {@code (posting_id, created_at)} (finaccounting/V1 + V2 section 7). {@code gl_posting} is
 * {@code PARTITION BY RANGE (created_at)}, and Postgres requires the partition key in the primary
 * key, which is why this id is composite rather than a plain {@code posting_id}. Same shape as
 * {@code reinsurance.domain.PolicyProjectionId} -- a public no-arg constructor (the JPA spec's
 * requirement for an {@code @IdClass}), an all-args constructor, and {@code equals}/{@code
 * hashCode} over every field, copied rather than invented fresh.
 *
 * <p>Field order and names mirror {@link GlPosting}'s {@code @Id} fields exactly:
 * {@code postingId} then {@code createdAt}.
 */
public class GlPostingId implements Serializable {
    private UUID postingId;
    private Instant createdAt;

    public GlPostingId() {}

    public GlPostingId(UUID postingId, Instant createdAt) {
        this.postingId = postingId;
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GlPostingId that)) return false;
        return Objects.equals(postingId, that.postingId) && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() { return Objects.hash(postingId, createdAt); }
}
