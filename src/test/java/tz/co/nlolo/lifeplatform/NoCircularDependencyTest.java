package tz.co.nlolo.lifeplatform;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class NoCircularDependencyTest {

    @Test
    void moduleDependencyGraphStaysACyclicGraph() {
        ApplicationModules modules = ApplicationModules.of(Application.class);
        assertDoesNotThrow(modules::verify,
            "Synchronous module dependency graph must remain a DAG (docs/02-module-architecture.md §4)");
    }
}
