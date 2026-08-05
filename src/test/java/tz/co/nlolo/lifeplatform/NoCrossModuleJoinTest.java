package tz.co.nlolo.lifeplatform;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoCrossModuleJoinTest {

    private static final String BASE_PACKAGE = "tz.co.nlolo.lifeplatform";

    private static final java.util.List<String> MODULES = java.util.List.of(
        "party", "product", "underwriting", "policy", "policyloan", "billing", "claims",
        "distribution", "payment", "reinsurance", "finaccounting", "regreporting",
        "communication", "omnichannel", "audit", "iam", "document", "refdata"
    );

    @Test
    void noJpaQueryJoinsAcrossModuleSchemas() {
        JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);

        java.util.List<String> violations = new java.util.ArrayList<>();

        classes.forEach(javaClass -> {
            String ownModule = moduleOf(javaClass.getPackageName());
            if (ownModule == null) {
                return;
            }
            for (var method : javaClass.getMethods()) {
                for (var annotation : method.getAnnotations()) {
                    if (!annotation.getRawType().getFullName().equals("org.springframework.data.jpa.repository.Query")) {
                        continue;
                    }
                    // Extract the JPQL string via the raw annotation members ArchUnit exposes.
                    annotation.get("value").ifPresent(value -> {
                        String jpql = value.toString();
                        for (String otherModule : MODULES) {
                            if (!otherModule.equals(ownModule) && jpql.contains(otherModule + ".")) {
                                violations.add(javaClass.getFullName() + "#" + method.getName()
                                    + " references module '" + otherModule + "' from module '" + ownModule + "'");
                            }
                        }
                    });
                }
            }
        });

        assertThat(violations).as("Cross-module JPA joins found (Deliverable 2 §2 forbids them)").isEmpty();
    }

    private static String moduleOf(String packageName) {
        if (!packageName.startsWith(BASE_PACKAGE + ".")) {
            return null;
        }
        String remainder = packageName.substring((BASE_PACKAGE + ".").length());
        int dot = remainder.indexOf('.');
        return dot == -1 ? remainder : remainder.substring(0, dot);
    }
}
