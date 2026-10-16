package tz.co.nlolo.lifeplatform.dashboard.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * One figure's read, isolated. A dashboard reads from up to nine modules, and one module being down must cost one
 * card rather than the whole page -- the same rule as the sidebar badges, where a failed count is absent and never 0.
 */
final class Safely {

    private static final Logger log = LoggerFactory.getLogger(Safely.class);

    private Safely() {}

    static <T> T read(String figure, Supplier<T> read) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            log.warn("dashboard could not read {}", figure, e);
            return null;
        }
    }
}
