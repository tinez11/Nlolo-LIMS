package tz.co.nlolo.lifeplatform;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * The module-structure gate (docs/02-module-architecture.md §2, §4).
 *
 * <p>{@code verify()} is what enforces the {@code allowedDependencies} graph, the DAG requirement
 * of §4, and module encapsulation — which is why eleven production classes cite this test by name
 * when explaining why they are shaped the way they are.
 *
 * <p>{@code NoCircularDependencyTest} used to sit beside this class calling the SAME
 * {@code ApplicationModules.of(Application.class).verify()} inside an {@code assertDoesNotThrow},
 * which is the same assertion with a different failure message: one check, two names, two module
 * graph builds per suite run. §4's DAG requirement is verified here, as
 * {@code GlobalExceptionHandler}'s javadoc already said it was.
 *
 * <p>{@code writesModuleDocumentation()} is gone too, and for a different reason: it asserted
 * nothing. It ran Modulith's {@code Documenter} to write PlantUML into {@code target/}, so it
 * could only ever fail on an IO error — a build step wearing a {@code @Test} annotation. Run the
 * {@code Documenter} from a build plugin if those diagrams are wanted.
 */
class ModularityTests {

    static final ApplicationModules modules = ApplicationModules.of(Application.class);

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }
}
