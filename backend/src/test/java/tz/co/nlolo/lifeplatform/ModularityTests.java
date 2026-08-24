package tz.co.nlolo.lifeplatform;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    static final ApplicationModules modules = ApplicationModules.of(Application.class);

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new org.springframework.modulith.docs.Documenter(modules)
            .writeModulesAsPlantUml()
            .writeIndividualModulesAsPlantUml();
    }
}
