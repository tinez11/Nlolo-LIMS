package tz.co.nlolo.lifeplatform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * How an event is written to the event publication registry, and read back to be delivered again (2026-11-14, durable
 * events).
 *
 * <p>The registry's default writes plain JSON, which loses types: a payload's UUID comes back as text, a BigDecimal as
 * a double, a date as a string -- and thirty-five listener sites cast a payload value straight to UUID or BigDecimal, so
 * every redelivered event would have failed on the cast. This writes each value with its type and reads it back as the
 * same type, so a redelivered event is indistinguishable from the original.
 *
 * <p>Deterministic: the registry finds a delivery's row by serializing the event again and matching the text, so the
 * same event always writes the same text (map order is kept as the map iterates; a read-back map keeps it).
 */
@Component
@org.springframework.context.annotation.Primary   // over the registry's own JacksonEventSerializer, which does not back off
public class DurableEventSerializer implements EventSerializer {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ObjectMapper json;

    public DurableEventSerializer(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public Object serialize(Object event) {
        ObjectNode root = NODES.objectNode();
        if (event instanceof DomainEventEnvelope<?> e) {
            root.put("$env", true);
            root.put("eventId", e.eventId() == null ? null : e.eventId().toString());
            root.put("eventType", e.eventType());
            root.put("schemaVersion", e.schemaVersion());
            root.put("tenantId", e.tenantId() == null ? null : e.tenantId().toString());
            root.put("occurredAt", e.occurredAt() == null ? null : e.occurredAt().toString());
            if (e.sequenceNumber() == null) root.putNull("sequenceNumber");
            else root.put("sequenceNumber", e.sequenceNumber());
            root.set("payload", encode(e.payload()));
        } else {
            root.put("$o", event.getClass().getName());
            root.set("v", json.valueToTree(event));
        }
        try {
            return json.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not record event " + event.getClass().getName(), ex);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T deserialize(Object serialized, Class<T> type) {
        try {
            JsonNode root = json.readTree(serialized.toString());
            if (root.path("$env").asBoolean(false)) {
                return (T) new DomainEventEnvelope<>(
                    uuid(root.get("eventId")), text(root.get("eventType")), root.path("schemaVersion").asInt(1),
                    uuid(root.get("tenantId")),
                    root.hasNonNull("occurredAt") ? Instant.parse(root.get("occurredAt").asText()) : null,
                    root.hasNonNull("sequenceNumber") ? root.get("sequenceNumber").asLong() : null,
                    decode(root.get("payload")));
            }
            return json.treeToValue(root.get("v"), type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not read a recorded " + type.getName(), ex);
        }
    }

    // ---- values, each with its type -------------------------------------------------------------------------------

    JsonNode encode(Object value) {
        if (value == null) return NODES.nullNode();
        if (value instanceof String s) return NODES.textNode(s);
        if (value instanceof Boolean b) return NODES.booleanNode(b);
        if (value instanceof Integer i) return tagged("$i", NODES.numberNode(i));
        if (value instanceof Long l) return tagged("$L", NODES.numberNode(l));
        if (value instanceof Double d) return tagged("$f", NODES.numberNode(d));
        if (value instanceof BigDecimal d) return tagged("$d", NODES.textNode(d.toString()));
        if (value instanceof UUID u) return tagged("$u", NODES.textNode(u.toString()));
        if (value instanceof Instant || value instanceof LocalDate || value instanceof LocalDateTime
                || value instanceof OffsetDateTime || value instanceof ZonedDateTime || value instanceof YearMonth
                || value instanceof LocalTime || value instanceof Duration) {
            ObjectNode node = tagged("$t", NODES.textNode(value.getClass().getSimpleName()));
            node.put("v", value.toString());
            return node;
        }
        if (value instanceof Enum<?> e) {
            ObjectNode node = tagged("$e", NODES.textNode(e.getDeclaringClass().getName()));
            node.put("v", e.name());
            return node;
        }
        if (value instanceof Map<?, ?> map) {
            ObjectNode entries = NODES.objectNode();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                entries.set(String.valueOf(entry.getKey()), encode(entry.getValue()));
            }
            return tagged("$m", entries);
        }
        if (value instanceof Collection<?> collection) {
            ArrayNode items = NODES.arrayNode();
            for (Object item : collection) items.add(encode(item));
            return tagged(value instanceof Set<?> ? "$s" : "$l", items);
        }
        // A record or any other object: its class, and Jackson's own rendering of it.
        ObjectNode node = tagged("$o", NODES.textNode(value.getClass().getName()));
        node.set("v", json.valueToTree(value));
        return node;
    }

    Object decode(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (!node.isObject()) return json.convertValue(node, Object.class);
        Iterator<String> names = node.fieldNames();
        String tag = names.hasNext() ? names.next() : "";
        JsonNode v = node.get(tag);
        return switch (tag) {
            case "$i" -> v.asInt();
            case "$L" -> v.asLong();
            case "$f" -> v.asDouble();
            case "$d" -> new BigDecimal(v.asText());
            case "$u" -> UUID.fromString(v.asText());
            case "$t" -> time(v.asText(), node.get("v").asText());
            case "$e" -> enumValue(v.asText(), node.get("v").asText());
            case "$m" -> {
                Map<String, Object> map = new LinkedHashMap<>();
                v.fields().forEachRemaining(entry -> map.put(entry.getKey(), decode(entry.getValue())));
                yield map;
            }
            case "$l" -> {
                List<Object> list = new ArrayList<>();
                v.forEach(item -> list.add(decode(item)));
                yield list;
            }
            case "$s" -> {
                Set<Object> set = new LinkedHashSet<>();
                v.forEach(item -> set.add(decode(item)));
                yield set;
            }
            case "$o" -> object(v.asText(), node.get("v"));
            default -> json.convertValue(node, Object.class);
        };
    }

    private static ObjectNode tagged(String tag, JsonNode value) {
        ObjectNode node = NODES.objectNode();
        node.set(tag, value);
        return node;
    }

    private static Object time(String kind, String value) {
        return switch (kind) {
            case "Instant" -> Instant.parse(value);
            case "LocalDate" -> LocalDate.parse(value);
            case "LocalDateTime" -> LocalDateTime.parse(value);
            case "OffsetDateTime" -> OffsetDateTime.parse(value);
            case "ZonedDateTime" -> ZonedDateTime.parse(value);
            case "YearMonth" -> YearMonth.parse(value);
            case "LocalTime" -> LocalTime.parse(value);
            case "Duration" -> Duration.parse(value);
            default -> value;
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(String className, String name) {
        try {
            return Enum.valueOf((Class<? extends Enum>) Class.forName(className), name);
        } catch (ClassNotFoundException e) {
            return name;
        }
    }

    private Object object(String className, JsonNode value) {
        try {
            return json.treeToValue(value, Class.forName(className));
        } catch (ClassNotFoundException | JsonProcessingException e) {
            return json.convertValue(value, Object.class);
        }
    }

    private static UUID uuid(JsonNode node) {
        return node == null || node.isNull() ? null : UUID.fromString(node.asText());
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }
}
