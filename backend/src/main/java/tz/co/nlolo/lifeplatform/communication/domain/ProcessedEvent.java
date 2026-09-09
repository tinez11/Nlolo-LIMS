package tz.co.nlolo.lifeplatform.communication.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * An event this module has already acted on.
 *
 * <p>Explicit dedup, and communication is the one module on this platform that needs it. The V1
 * migration's own comment says so: everywhere else a redelivered event is absorbed silently
 * because the write is idempotent -- re-projecting the same policy row twice changes nothing.
 * Here it is not. A second SMS is a second SMS on somebody's phone, and a customer who is told
 * twice that their offer is closing learns that this platform's messages are noise.
 *
 * <p>The row is written in the same transaction as the dispatch it guards, so a crash between
 * the two cannot leave an event marked processed with nothing sent.
 */
@Entity
@Table(name = "processed_event", schema = "communication")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();

    protected ProcessedEvent() {}

    public ProcessedEvent(UUID eventId) {
        this.eventId = eventId;
    }

    public UUID getEventId() { return eventId; }
    public Instant getProcessedAt() { return processedAt; }
}
