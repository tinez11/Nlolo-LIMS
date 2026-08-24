# M0 — Platform Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Phase 0 design record (docs/01–08, api/, db-migrations/, infra/, observability/) into a real, buildable Spring Modulith repository skeleton — 18 empty module packages, a working Maven build, docker-compose stack that boots end-to-end, and a CI pipeline that actually runs — with zero business logic, per `docs/08-implementation-roadmap.md`'s M0 definition.

**Architecture:** Single Spring Boot 3 / Spring Modulith monolith, one Maven module, `tz.co.nlolo.lifeplatform` base package (confirmed in `README.md:5` and `docs/02-module-architecture.md:207`). Each of the 18 modules from `docs/02-module-architecture.md` §1/§4 becomes a direct subpackage under the base package, annotated with `@ApplicationModule(allowedDependencies = {...})` reproducing that doc's dependency table exactly. Database migrations stay per-module (already authored in `db-migrations/`) and are applied by an external script, not Spring Boot's embedded Flyway-on-boot — this sidesteps a real version-numbering collision (every module's first migration is named `V1__...`, which cannot share one Flyway schema-history table) and matches how `.github/workflows/ci-cd.yml`'s deploy jobs already invoke `./scripts/migrate.sh` as a separate pipeline step before the app deploys.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Modulith 1.2.5, Maven (wrapper-only, no local Maven required — built and verified through Docker), PostgreSQL 16, Docker Compose, GitHub Actions.

## Global Constraints

- Base package / groupId: `tz.co.nlolo.lifeplatform` — RESOLVED, do not use the old `com.nlolo.lifeplatform` placeholder anywhere (README.md:5, docs/02-module-architecture.md:207).
- No business logic in M0. Every module package is empty except a `package-info.java` per layer — per `docs/08-implementation-roadmap.md` M0 scope line and README.md:39.
- Module dependency table is authoritative and must be reproduced exactly from `docs/02-module-architecture.md` §4 — do not invent or drop an edge.
- `payment`'s allowed dependency is `refdata` only, and it must not appear as an `allowedDependencies` entry anywhere else — this is called out explicitly in Deliverable 2 §2/§4/§9 as load-bearing for the request/confirm pattern.
- Per-module Postgres schema convention (Deliverable 2 §2, "Per-module Postgres schemas (A1, approved)") — never wire cross-module joins or a shared schema-history table.
- `app_role` must never gain `SUPERUSER` or `BYPASSRLS` (infra/postgres/init/01-create-app-role.sql, Deliverable 6 §1/§5) — any fix to that file must preserve those two clauses verbatim.
- Camunda is Camunda 7 embedded, not a separate container/dependency in M0 (docs/07-infrastructure-architecture.md §1) — do not add a Camunda Maven dependency yet; it's out of scope until M3.
- No local `java`/`mvn` binaries are available in this environment (verified: both report "command not found" in Bash and PowerShell) — every build/test verification step in this plan runs **through Docker** (`maven:3.9.9-eclipse-temurin-21` for build/test, the project's own `infra/app/Dockerfile` for the packaged image, `docker compose` for the full stack). Do not write a verification step that assumes a bare `mvn`/`./mvnw` call on the host.
- Docker and Docker Compose ARE available (Docker 29.5.3 / Compose v5.1.4, verified).
- Never commit real secrets. All passwords in this plan are dev-only defaults already present in `infra/docker-compose.yml` (e.g. `devpassword`, `devapppassword`) — keep using those exact env var names so the compose file needs no changes.

---

## File Structure

```
pom.xml                                          # new — Maven project, Spring Boot/Modulith parent+deps
.mvn/wrapper/maven-wrapper.properties            # new — generated via dockerized `mvn wrapper:wrapper`
mvnw, mvnw.cmd                                   # new — generated the same way
.gitignore                                       # new
.github/workflows/ci-cd.yml                      # moved from ci-cd-workflows/ci-cd.yml, path bugs fixed
scripts/migrate.sh                               # new — per-module psql migration runner
scripts/deploy.sh                                # new — honest placeholder, fails loudly, no invented infra
src/main/java/tz/co/nlolo/lifeplatform/
  Application.java                               # new — @SpringBootApplication entry point
  party/package-info.java  (+ product/, underwriting/, policy/, policyloan/, billing/,
    claims/, distribution/, payment/, reinsurance/, finaccounting/, regreporting/,
    communication/, omnichannel/, audit/, iam/, document/, refdata/)
                                                  # new — 18× @ApplicationModule package-info
  <module>/{domain,application,infrastructure,api}/package-info.java
                                                  # new — 18×4 = 72 empty layer packages
src/main/resources/
  application.yml                                # new — base config
  application-local.yml                           # new — datasource/actuator config for docker-compose
src/test/java/tz/co/nlolo/lifeplatform/
  ModularityTests.java                           # new — ApplicationModules.of(Application.class).verify()
  NoCircularDependencyTest.java                  # new — explicit cycle guard (D4)
  NoCrossModuleJoinTest.java                     # new — ArchUnit guard against cross-schema JPA joins (D4)
keycloak/
  customers-realm.json, agents-realm.json, staff-realm.json, regulators-realm.json
                                                  # new — minimal importable realms (Deliverable 4 §"Four Keycloak realms")
mock-mobile-money/mappings/README.md             # new — placeholder so the compose bind mount resolves
infra/postgres/init/01-create-app-role.sql       # MODIFIED — fix psql variable substitution bug (see Task 8)
```

**Files deliberately NOT touched:** `db-migrations/**` (Phase 0 deliverable, already reviewed), `api/**`, `docs/**`, `observability/**`, `infra/docker-compose*.yml`, `infra/postgres/Dockerfile`, `infra/app/Dockerfile` — none of these need to change for M0 except the one bug documented in Task 8.

---

### Task 1: Maven project skeleton + wrapper

**Files:**
- Create: `pom.xml`
- Create: `.gitignore`
- Create (via Docker, not by hand): `.mvn/wrapper/maven-wrapper.properties`, `mvnw`, `mvnw.cmd`

**Interfaces:**
- Produces: a `./mvnw` that later tasks and CI invoke identically to how `infra/app/Dockerfile:6` (`RUN ./mvnw -B -q -DskipTests package`) and `.github/workflows/ci-cd.yml` already assume.

- [ ] **Step 1: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
    <relativePath/>
  </parent>

  <groupId>tz.co.nlolo.lifeplatform</groupId>
  <artifactId>lifeplatform</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <name>lifeplatform</name>
  <description>Digital Life Insurance Core Platform (Tanzania)</description>

  <properties>
    <java.version>21</java.version>
    <spring-modulith.version>1.2.5</spring-modulith.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.modulith</groupId>
        <artifactId>spring-modulith-bom</artifactId>
        <version>${spring-modulith.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.modulith</groupId>
      <artifactId>spring-modulith-starter-core</artifactId>
    </dependency>

    <dependency>
      <groupId>org.springframework.modulith</groupId>
      <artifactId>spring-modulith-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.tngtech.archunit</groupId>
      <artifactId>archunit-junit5</artifactId>
      <version>1.3.0</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <finalName>lifeplatform</finalName>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Write `.gitignore`**

```
target/
.mvn/wrapper/maven-wrapper.jar
*.iml
.idea/
.vscode/
*.log
.env
```

- [ ] **Step 3: Generate the Maven wrapper via Docker (no local Maven needed)**

Run from the repo root:

```bash
docker run --rm -v "$(pwd):/workspace" -w /workspace maven:3.9.9-eclipse-temurin-21 \
  mvn -N wrapper:wrapper -Dmaven=3.9.9
```

Expected: `mvnw`, `mvnw.cmd`, and `.mvn/wrapper/maven-wrapper.properties` are created in the repo root. On Linux/macOS follow-on runs, `chmod +x mvnw` (the container writes it non-executable on some Docker Desktop configurations).

- [ ] **Step 4: Verify the wrapper resolves dependencies**

```bash
docker run --rm -v "$(pwd):/workspace" -w /workspace maven:3.9.9-eclipse-temurin-21 \
  ./mvnw -B -q -DskipTests dependency:resolve
```

Expected: exits 0. If `spring-boot-starter-parent:3.3.5` or `spring-modulith-bom:1.2.5` fail to resolve (network or version-not-found), pin down to the nearest available GA version reported by Maven's error output and update `pom.xml` — do not silently downgrade Java or the base package.

- [ ] **Step 5: Commit**

```bash
git add pom.xml .gitignore mvnw mvnw.cmd .mvn
git commit -m "build: add Maven/Spring Boot project skeleton with wrapper"
```

---

### Task 2: Application entry point + base configuration

**Files:**
- Create: `src/main/java/tz/co/nlolo/lifeplatform/Application.java`
- Create: `src/main/resources/application.yml`
- Create: `src/main/resources/application-local.yml`

**Interfaces:**
- Consumes: nothing yet (no modules exist until Task 3, but the class must exist first since `ModularityTests` in Task 4 references `Application.class`).
- Produces: `tz.co.nlolo.lifeplatform.Application` — the class every module-verification test in Task 4 calls `ApplicationModules.of(Application.class)` against.

- [ ] **Step 1: Write `Application.java`**

```java
package tz.co.nlolo.lifeplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

- [ ] **Step 2: Write `application.yml`**

```yaml
spring:
  application:
    name: lifeplatform
  # Migrations are applied by scripts/migrate.sh as a separate pipeline/dev step,
  # per-module and per-module schema-history table -- not by Spring Boot on boot.
  # See this plan's Architecture note and Task 8/9.
  flyway:
    enabled: false
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: none

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      probes:
        enabled: true
```

- [ ] **Step 3: Write `application-local.yml`**

Mirrors the env vars `infra/docker-compose.yml`'s `app` service already sets (lines 116–131), so no compose changes are needed.

```yaml
spring:
  config:
    activate:
      on-profile: local
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/lifeplatform}
    username: ${SPRING_DATASOURCE_USERNAME:app_role}
    password: ${SPRING_DATASOURCE_PASSWORD:devapppassword}

server:
  port: 8080
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform/Application.java src/main/resources
git commit -m "feat: add Spring Boot application entry point and base config"
```

---

### Task 3: Generate all 18 module package skeletons

**Files:**
- Create: `src/main/java/tz/co/nlolo/lifeplatform/<module>/package-info.java` (18 files)
- Create: `src/main/java/tz/co/nlolo/lifeplatform/<module>/{domain,application,infrastructure,api}/package-info.java` (72 files)

**Interfaces:**
- Consumes: nothing.
- Produces: 18 Spring Modulith application modules, each with an `allowedDependencies` list matching `docs/02-module-architecture.md` §4 exactly. `ModularityTests` (Task 4) verifies this structure.

This is one mechanical, table-driven step across all 18 modules — reviewed as a single unit against Deliverable 2 §4, not module-by-module.

- [ ] **Step 1: Run the generator script**

The dependency table below is copied verbatim from `docs/02-module-architecture.md` §4 (the "Allowed to call (synchronous)" column). Save this as `/tmp/gen-modules.sh` (or the project's scratch dir) and run it from the repo root:

```bash
#!/usr/bin/env bash
set -euo pipefail

BASE="src/main/java/tz/co/nlolo/lifeplatform"

declare -A DEPS=(
  [party]="document,refdata"
  [product]="refdata"
  [underwriting]="party,product,document,refdata"
  [policy]="underwriting,product,party,document,refdata"
  [policyloan]="policy,refdata"
  [billing]="policy,product,refdata"
  [claims]="policy,underwriting,party,document,refdata"
  [distribution]="party,product,refdata"
  [payment]="refdata"
  [reinsurance]="refdata"
  [finaccounting]="product,refdata"
  [regreporting]="refdata"
  [communication]="party,refdata"
  [omnichannel]="policy,billing,claims,party"
  [audit]=""
  [iam]=""
  [document]=""
  [refdata]=""
)

for mod in "${!DEPS[@]}"; do
  moddir="$BASE/$mod"
  mkdir -p "$moddir"/{domain,application,infrastructure,api}

  deps="${DEPS[$mod]}"
  if [ -z "$deps" ]; then
    annotation_body=""
  else
    IFS=',' read -ra parts <<< "$deps"
    quoted=$(printf '"%s", ' "${parts[@]}")
    quoted="${quoted%, }"
    annotation_body="(allowedDependencies = { $quoted })"
  fi

  cat > "$moddir/package-info.java" <<EOF
@org.springframework.modulith.ApplicationModule${annotation_body}
package tz.co.nlolo.lifeplatform.$mod;
EOF

  for layer in domain application infrastructure api; do
    cat > "$moddir/$layer/package-info.java" <<EOF
package tz.co.nlolo.lifeplatform.$mod.$layer;
EOF
  done
done

echo "Generated $(find "$BASE" -mindepth 1 -maxdepth 1 -type d | wc -l) module packages."
```

```bash
chmod +x /tmp/gen-modules.sh && /tmp/gen-modules.sh
```

Expected: `Generated 18 module packages.` and `find src/main/java/tz/co/nlolo/lifeplatform -name package-info.java | wc -l` reports `90` (18 module-root + 72 layer files).

- [ ] **Step 2: Spot-check three modules against Deliverable 2 §4**

```bash
cat src/main/java/tz/co/nlolo/lifeplatform/payment/package-info.java
cat src/main/java/tz/co/nlolo/lifeplatform/policy/package-info.java
cat src/main/java/tz/co/nlolo/lifeplatform/audit/package-info.java
```

Expected:
- `payment` → `@org.springframework.modulith.ApplicationModule(allowedDependencies = { "refdata" })` — and confirm no other generated file lists `payment` in its own `allowedDependencies` (`grep -rl '"payment"' src/main/java` should return nothing).
- `policy` → `allowedDependencies = { "underwriting", "product", "party", "document", "refdata" }`.
- `audit` → bare `@org.springframework.modulith.ApplicationModule` with no `allowedDependencies` argument.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/tz/co/nlolo/lifeplatform
git commit -m "feat: stub all 18 Spring Modulith application module packages"
```

---

### Task 4: Modularity verification tests (CI gates from Deliverable 2 Rev 2 D4)

**Files:**
- Create: `src/test/java/tz/co/nlolo/lifeplatform/ModularityTests.java`
- Create: `src/test/java/tz/co/nlolo/lifeplatform/NoCircularDependencyTest.java`
- Create: `src/test/java/tz/co/nlolo/lifeplatform/NoCrossModuleJoinTest.java`

**Interfaces:**
- Consumes: `tz.co.nlolo.lifeplatform.Application` (Task 2), the 18 module packages (Task 3).
- Produces: the exact three test classes `.github/workflows/ci-cd.yml`'s `modulith-verify` job already names: `-Dtest=ModularityTests,NoCrossModuleJoinTest,NoCircularDependencyTest`.

- [ ] **Step 1: Write `ModularityTests.java`**

```java
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
```

- [ ] **Step 2: Write `NoCircularDependencyTest.java`**

`ApplicationModules.verify()` already fails on a circular *module* dependency, but Deliverable 2 Rev 2's D4 asks for this to be an explicit, separately-named guard so a reviewer sees a dedicated failure message rather than a generic verify() stack trace. This re-runs the same verification and asserts it completes without throwing, under a name CI can report independently.

```java
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
```

- [ ] **Step 3: Write `NoCrossModuleJoinTest.java`**

Deliverable 2 §2: "fails if a JPA repository query joins across module schema boundaries." At M0 there are zero `@Repository`/`@Query` classes, so this test's job right now is to *exist and pass trivially* — it becomes load-bearing starting M1 when the first JPA repositories appear. Implemented with ArchUnit so it actually inspects future repository query strings, not a placeholder that always returns true.

```java
package tz.co.nlolo.lifeplatform;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class NoCrossModuleJoinTest {

    private static final String BASE_PACKAGE = "tz.co.nlolo.lifeplatform";

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
                    method.tryGetAnnotationOfType("org.springframework.data.jpa.repository.Query");
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

    private static final java.util.List<String> MODULES = java.util.List.of(
        "party", "product", "underwriting", "policy", "policyloan", "billing", "claims",
        "distribution", "payment", "reinsurance", "finaccounting", "regreporting",
        "communication", "omnichannel", "audit", "iam", "document", "refdata"
    );

    private static String moduleOf(String packageName) {
        if (!packageName.startsWith(BASE_PACKAGE + ".")) {
            return null;
        }
        String remainder = packageName.substring((BASE_PACKAGE + ".").length());
        int dot = remainder.indexOf('.');
        return dot == -1 ? remainder : remainder.substring(0, dot);
    }
}
```

Add the one extra test-scope dependency this uses (`assertj-core` ships transitively with `spring-boot-starter-test`, already present from Task 1 — no `pom.xml` change needed).

- [ ] **Step 4: Run the tests through Docker**

```bash
docker run --rm -v "$(pwd):/workspace" -w /workspace maven:3.9.9-eclipse-temurin-21 \
  ./mvnw -B -q test -Dtest=ModularityTests,NoCrossModuleJoinTest,NoCircularDependencyTest
```

Expected: `BUILD SUCCESS`, 0 failures. If `ModularityTests` fails, read the reported violation — it will name the offending module/dependency edge; fix the `package-info.java` from Task 3, don't weaken the test.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/tz/co/nlolo/lifeplatform
git commit -m "test: add Spring Modulith structural verification gates"
```

---

### Task 5: Fix and relocate the CI/CD workflow

**Files:**
- Create: `.github/workflows/ci-cd.yml`
- Delete: `ci-cd-workflows/ci-cd.yml`

**Interfaces:**
- Consumes: `pom.xml`/`mvnw` (Task 1), `api/openapi/*.yaml` and `api/asyncapi-events.yaml` (existing), `db-migrations/**` (existing), `scripts/migrate.sh` (Task 6).

GitHub Actions only discovers workflows under `.github/workflows/` — `ci-cd-workflows/ci-cd.yml` (its current location) would never run. The file also has two path bugs from before the final repo layout was settled: it validates `openapi/openapi-*.yaml` (no such path — the real path is `api/openapi/openapi-*.yaml`) and `asyncapi-events.yaml` at the repo root (real path: `api/asyncapi-events.yaml`).

- [ ] **Step 1: Move the file**

```bash
mkdir -p .github/workflows
git mv ci-cd-workflows/ci-cd.yml .github/workflows/ci-cd.yml
rmdir ci-cd-workflows 2>/dev/null || true
```

- [ ] **Step 2: Fix the two path bugs in `contract-validation`**

Find this block (originally at what was `ci-cd-workflows/ci-cd.yml` lines 49–57):

```yaml
      - name: Validate OpenAPI 3.1 specs (Deliverable 4)
        run: |
          pip install -q openapi-spec-validator
          for f in openapi/openapi-*.yaml; do
            echo "Validating $f"
            python -m openapi_spec_validator "$f"
          done
      - name: Validate AsyncAPI 2.6 event catalog (Deliverable 5)
        run: npx --yes @asyncapi/cli validate asyncapi-events.yaml
```

Replace with:

```yaml
      - name: Validate OpenAPI 3.1 specs (Deliverable 4)
        run: |
          pip install -q openapi-spec-validator
          for f in api/openapi/openapi-*.yaml; do
            echo "Validating $f"
            python -m openapi_spec_validator "$f"
          done
      - name: Validate AsyncAPI 2.6 event catalog (Deliverable 5)
        run: npx --yes @asyncapi/cli validate api/asyncapi-events.yaml
```

- [ ] **Step 3: Verify no other stale paths remain**

```bash
grep -n "openapi/openapi-\|^\s*run:.*asyncapi-events.yaml" .github/workflows/ci-cd.yml
```

Expected: both remaining matches are prefixed `api/`. Also confirm the `db-migration-validation` job's module loop (`for mod in party product underwriting policy policyloan billing claims payment audit distribution reinsurance finaccounting regreporting communication document refdata`) — 16 names — each has a matching directory:

```bash
for mod in party product underwriting policy policyloan billing claims payment audit distribution reinsurance finaccounting regreporting communication document refdata; do
  test -f "db-migrations/$mod/V1__create_${mod}_schema.sql" || echo "MISSING: $mod"
done
```

Expected: no output.

- [ ] **Step 4: Validate workflow YAML syntax**

```bash
docker run --rm -v "$(pwd):/workspace" -w /workspace python:3.12-slim \
  python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci-cd.yml')); print('OK')"
```

Expected: `OK`.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/ci-cd.yml
git add -u ci-cd-workflows
git commit -m "fix: relocate CI workflow to .github/workflows and correct api/ paths"
```

---

### Task 6: Migration and deploy scripts

**Files:**
- Create: `scripts/migrate.sh`
- Create: `scripts/deploy.sh`

**Interfaces:**
- Consumes: `db-migrations/<module>/V1__create_<module>_schema.sql` (existing, 16 modules), `db-migrations/_post-migration/configure-pg-partman.sql` (existing).
- Produces: the two scripts `.github/workflows/ci-cd.yml`'s `deploy-staging`/`deploy-production` jobs already call (`./scripts/migrate.sh staging`, `./scripts/deploy.sh staging ${{ github.sha }}`).

- [ ] **Step 1: Write `scripts/migrate.sh`**

Mirrors the exact per-module loop already proven in `.github/workflows/ci-cd.yml`'s `db-migration-validation` job, but against a real target's `DATABASE_URL` instead of the CI-local Postgres service container. Each module's migration runs against its own schema; no shared Flyway schema-history table is used (see this plan's Architecture note) — that upgrade is deferred until a module needs a `V2` migration.

```bash
#!/usr/bin/env bash
# Applies every module's V1 schema migration in sequence against the target
# environment's database, mirroring .github/workflows/ci-cd.yml's
# db-migration-validation job. Usage: scripts/migrate.sh <staging|production>
set -euo pipefail

ENVIRONMENT="${1:?Usage: scripts/migrate.sh <staging|production>}"

case "$ENVIRONMENT" in
  staging)
    DB_URL="${STAGING_DB_URL:?STAGING_DB_URL is not set}"
    ;;
  production)
    DB_URL="${PRODUCTION_DB_URL:?PRODUCTION_DB_URL is not set}"
    ;;
  *)
    echo "Unknown environment: $ENVIRONMENT (expected 'staging' or 'production')" >&2
    exit 1
    ;;
esac

MODULES="party product underwriting policy policyloan billing claims payment audit distribution reinsurance finaccounting regreporting communication document refdata"

for mod in $MODULES; do
  echo "Applying $mod"
  psql "$DB_URL" -v ON_ERROR_STOP=1 -f "db-migrations/$mod/V1__create_${mod}_schema.sql"
done

echo "All 16 module migrations applied against $ENVIRONMENT."
```

- [ ] **Step 2: Write `scripts/deploy.sh`**

No deployment target (hosting platform, orchestrator, SSH target) has been specified anywhere in the eight Phase 0 deliverables — `docs/08-implementation-roadmap.md` §5's open item 4 explicitly says production deployment approval reviewers "need names," and no infra-as-code for a staging/production host exists yet. Inventing a specific target here would violate the engagement's "never invent business/infra facts not given" rule. This script is an honest, fail-loud placeholder — not a fake success — so the CD pipeline's shape is right and swapping in the real deploy mechanism later is a one-file change.

```bash
#!/usr/bin/env bash
# Rolling deploy of the lifeplatform image to the target environment.
#
# NOT YET IMPLEMENTED: no deployment target (orchestrator, host, or platform)
# has been specified in the Phase 0 deliverables (see
# docs/08-implementation-roadmap.md §5, open item 4). Wire the real deploy
# mechanism here once that's decided -- do not guess at one.
#
# Usage: scripts/deploy.sh <staging|production> <image-tag>
set -euo pipefail

ENVIRONMENT="${1:?Usage: scripts/deploy.sh <staging|production> <image-tag>}"
IMAGE_TAG="${2:?Usage: scripts/deploy.sh <staging|production> <image-tag>}"

echo "Would deploy ghcr.io/<repo>:${IMAGE_TAG} to ${ENVIRONMENT}, but no deployment" >&2
echo "target has been configured yet. Resolve docs/08-implementation-roadmap.md" >&2
echo "§5 open item 4, then implement this script." >&2
exit 1
```

- [ ] **Step 3: Make both executable and verify usage errors are clear**

```bash
chmod +x scripts/migrate.sh scripts/deploy.sh
./scripts/migrate.sh 2>&1 | grep -q "Usage: scripts/migrate.sh" && echo OK-migrate
./scripts/deploy.sh 2>&1 | grep -q "Usage: scripts/deploy.sh" && echo OK-deploy
```

Expected: `OK-migrate` and `OK-deploy`.

- [ ] **Step 4: Commit**

```bash
git add scripts/migrate.sh scripts/deploy.sh
git commit -m "feat: add migration runner and honest deploy placeholder script"
```

---

### Task 7: Keycloak realm imports

**Files:**
- Create: `keycloak/customers-realm.json`
- Create: `keycloak/agents-realm.json`
- Create: `keycloak/staff-realm.json`
- Create: `keycloak/regulators-realm.json`

**Interfaces:**
- Consumes: `docs/04-api-contracts.md`'s realm list and role names (lines 37, 44).
- Produces: the four realms `infra/docker-compose.yml`'s `keycloak` service imports via `--import-realm` (line 83) and that the `app` service's four `KEYCLOAK_ISSUER_*` env vars (lines 125–128) point at.

M0 needs these realms to exist and import cleanly so `docker compose up` succeeds — not full production IdP configuration (user federation, real client secrets, custom claim mappers for `party_id`/`agentOfRecord` are business-logic wiring that lands with the modules that need them, starting M1). Each realm gets one confidential client so a token can be issued locally for testing.

- [ ] **Step 1: Write `keycloak/customers-realm.json`**

```json
{
  "realm": "customers",
  "enabled": true,
  "sslRequired": "external",
  "registrationAllowed": false,
  "clients": [
    {
      "clientId": "lifeplatform-app",
      "enabled": true,
      "publicClient": false,
      "protocol": "openid-connect",
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": true,
      "serviceAccountsEnabled": false,
      "redirectUris": ["*"],
      "secret": "dev-secret-customers"
    }
  ]
}
```

- [ ] **Step 2: Write `keycloak/agents-realm.json`**

```json
{
  "realm": "agents",
  "enabled": true,
  "sslRequired": "external",
  "registrationAllowed": false,
  "clients": [
    {
      "clientId": "lifeplatform-app",
      "enabled": true,
      "publicClient": false,
      "protocol": "openid-connect",
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": true,
      "serviceAccountsEnabled": false,
      "redirectUris": ["*"],
      "secret": "dev-secret-agents"
    }
  ]
}
```

- [ ] **Step 3: Write `keycloak/staff-realm.json`**

Includes the six staff roles from `docs/04-api-contracts.md:44`.

```json
{
  "realm": "staff",
  "enabled": true,
  "sslRequired": "external",
  "registrationAllowed": false,
  "roles": {
    "realm": [
      { "name": "UNDERWRITER" },
      { "name": "CLAIMS_ASSESSOR" },
      { "name": "CLAIMS_MANAGER" },
      { "name": "FINANCE_OFFICER" },
      { "name": "CUSTOMER_SERVICE_REP" },
      { "name": "ADMIN" }
    ]
  },
  "clients": [
    {
      "clientId": "lifeplatform-app",
      "enabled": true,
      "publicClient": false,
      "protocol": "openid-connect",
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": true,
      "serviceAccountsEnabled": false,
      "redirectUris": ["*"],
      "secret": "dev-secret-staff"
    }
  ]
}
```

- [ ] **Step 4: Write `keycloak/regulators-realm.json`**

```json
{
  "realm": "regulators",
  "enabled": true,
  "sslRequired": "external",
  "registrationAllowed": false,
  "roles": {
    "realm": [
      { "name": "TIRA_READ_ONLY" }
    ]
  },
  "clients": [
    {
      "clientId": "lifeplatform-app",
      "enabled": true,
      "publicClient": false,
      "protocol": "openid-connect",
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": true,
      "serviceAccountsEnabled": false,
      "redirectUris": ["*"],
      "secret": "dev-secret-regulators"
    }
  ]
}
```

- [ ] **Step 5: Validate JSON syntax for all four**

```bash
for f in keycloak/*.json; do python3 -c "import json,sys; json.load(open('$f')); print('$f OK')"; done
```

(If `python3` isn't on the host: `docker run --rm -v "$(pwd):/w" -w /w python:3.12-slim sh -c 'for f in keycloak/*.json; do python -c "import json; json.load(open(\"$f\"))" && echo "$f OK"; done'`.)

Expected: `keycloak/agents-realm.json OK`, `keycloak/customers-realm.json OK`, `keycloak/regulators-realm.json OK`, `keycloak/staff-realm.json OK`.

- [ ] **Step 6: Commit**

```bash
git add keycloak
git commit -m "feat: add minimal importable Keycloak realm exports for the four realms"
```

---

### Task 8: Fix the `app_role` bootstrap script's env-var substitution bug

**Files:**
- Modify: `infra/postgres/init/01-create-app-role.sql`

**Interfaces:**
- Consumes: `${APP_DB_PASSWORD:-devapppassword}`, already defined in `infra/docker-compose.yml:119` for the `app` service.

`infra/postgres/init/01-create-app-role.sql:13` uses `PASSWORD :'app_role_password'` — a `psql` **variable** reference (`-v app_role_password=...`). But Postgres's official image runs every `*.sql` file under `docker-entrypoint-initdb.d/` by piping it straight to `psql` with no `-v` flags at all (only `*.sh` files in that directory get executed as shell, where such variables could be supplied). As committed, this file fails immediately on `docker compose up` with `psql: error: ... there is no parameter :'app_role_password'` — the postgres container's init would crash before the `keycloak` database or extensions get created. This is a defect in the Phase 0 infra deliverable that blocks M0's own acceptance criterion ("`docker compose up` succeeds"), not a stylistic change — the two load-bearing clauses (`NOSUPERUSER NOBYPASSRLS`) are left untouched.

- [ ] **Step 1: Rename the file to a shell wrapper Postgres's init mechanism will actually execute**

```bash
git mv infra/postgres/init/01-create-app-role.sql infra/postgres/init/01-create-app-role.sql.template
```

- [ ] **Step 2: Write the new `infra/postgres/init/01-create-app-role.sh`**

```bash
#!/bin/sh
# Postgres's official image executes *.sh files in docker-entrypoint-initdb.d
# directly (unlike *.sql files, which are piped to psql with no variable
# substitution available) -- this wrapper lets APP_DB_PASSWORD flow in from
# the environment the same way infra/docker-compose.yml already sets it for
# the `app` service (SPRING_DATASOURCE_PASSWORD / APP_DB_PASSWORD).
set -e

APP_ROLE_PASSWORD="${APP_DB_PASSWORD:-devapppassword}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v app_role_password="$APP_ROLE_PASSWORD" \
  -f /docker-entrypoint-initdb.d/01-create-app-role.sql.template
```

- [ ] **Step 3: Make it executable**

```bash
chmod +x infra/postgres/init/01-create-app-role.sh
```

- [ ] **Step 4: Confirm the load-bearing clauses survived the rename untouched**

```bash
grep -n "NOSUPERUSER\|NOBYPASSRLS" infra/postgres/init/01-create-app-role.sql.template
```

Expected: both flags still present, unchanged (Deliverable 6 §1/§5 — these must never be dropped).

- [ ] **Step 5: Commit**

```bash
git add infra/postgres/init/01-create-app-role.sh infra/postgres/init/01-create-app-role.sql.template
git rm --cached infra/postgres/init/01-create-app-role.sql 2>/dev/null || true
git commit -m "fix: app_role bootstrap script's psql variable substitution never worked under docker-entrypoint-initdb.d"
```

---

### Task 9: Mock mobile money mappings placeholder

**Files:**
- Create: `mock-mobile-money/mappings/README.md`

**Interfaces:**
- Produces: the directory `infra/docker-compose.yml:100` bind-mounts (`./mock-mobile-money:/home/wiremock:ro`) — without at least one file, the directory doesn't exist in git and the bind mount either fails or silently mounts nothing depending on the Docker Desktop version.

Real stub mappings (M-Pesa/Airtel/Tigo callback simulation) are `payment` module scope (M5) — out of place in M0.

- [ ] **Step 1: Write `mock-mobile-money/mappings/README.md`**

```markdown
# Mock Mobile Money — WireMock stub mappings

Empty at M0 intentionally. Real M-Pesa / Airtel Money / Tigo Pesa callback stub
mappings belong to the `payment` module and land with M5 (see
`docs/08-implementation-roadmap.md` §4, M5 — Money Out), where `payment`'s
mobile-money ACL integration tests are defined.
```

- [ ] **Step 2: Commit**

```bash
git add mock-mobile-money
git commit -m "chore: add placeholder for payment module's future WireMock mappings"
```

---

### Task 10: Full-stack verification — build image and bring up docker-compose

**Files:** none (verification-only task).

**Interfaces:**
- Consumes: everything from Tasks 1–9.

- [ ] **Step 1: Build the application image**

```bash
docker build -f infra/app/Dockerfile -t lifeplatform:m0 .
```

Expected: exits 0. This is the same multi-stage build (`eclipse-temurin:21-jdk-alpine` → `./mvnw -B -q -DskipTests package`, then `eclipse-temurin:21-jre-alpine`) that `.github/workflows/ci-cd.yml`'s `build-image` job runs — if this fails, `build-image` would fail in CI too.

- [ ] **Step 2: Bring up the full compose stack**

```bash
cd infra
docker compose up -d
```

Expected: all services start. `postgres`, `redis`, `minio` reach `healthy` per their healthchecks; `minio-init` runs to completion (exit 0); `keycloak` starts without the init-script crash from Task 8; `app` starts and stays up (not restarting).

- [ ] **Step 3: Confirm the app is actually healthy, not just running**

```bash
sleep 15
curl -sf http://localhost:8080/actuator/health
```

Expected: `{"status":"UP", ...}`. If `app` is in a restart loop, `docker compose logs app` — the two most likely causes given this plan's scope are a Postgres connection failure (check `postgres` health) or `ModularityTests`-class errors surfacing at boot (Spring Modulith validates structure at context startup too, not just in tests).

- [ ] **Step 4: Confirm Keycloak imported all four realms**

```bash
curl -sf http://localhost:8081/realms/customers/.well-known/openid-configuration | grep -q issuer && echo customers-OK
curl -sf http://localhost:8081/realms/agents/.well-known/openid-configuration | grep -q issuer && echo agents-OK
curl -sf http://localhost:8081/realms/staff/.well-known/openid-configuration | grep -q issuer && echo staff-OK
curl -sf http://localhost:8081/realms/regulators/.well-known/openid-configuration | grep -q issuer && echo regulators-OK
```

Expected: all four `-OK` lines print.

- [ ] **Step 5: Tear down**

```bash
docker compose down -v
cd ..
```

(`-v` removes the fresh dev volumes created for this smoke test only — confirm with the user before running this against any environment that isn't this throwaway local verification.)

- [ ] **Step 6: No commit** — this task only verifies Tasks 1–9; nothing new to add.

---

### Task 11 (optional — confirm with user before running): Initialize git and make the first commit

This repository currently has no `.git` (verified: `Is a git repository: false`). Every prior task's "Commit" steps assume one exists. This task is written last and marked optional because initializing version control is a reasonable default but is still a decision about the project's own repo, not purely mechanical — confirm before running if anything above gave you pause.

**Files:** none.

- [ ] **Step 1: Initialize the repository**

```bash
git init
git branch -m main
```

- [ ] **Step 2: Re-stage everything produced by Tasks 1–9 in one pass if per-task commits above weren't run incrementally**

```bash
git add -A
git status
```

Review the output before committing — confirm nothing unexpected (stray build artifacts, `.env` files) is staged.

- [ ] **Step 3: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat: M0 platform bootstrap — Spring Modulith skeleton, CI/CD wiring, infra fixes

Turns the Phase 0 design record into a buildable repository per
docs/08-implementation-roadmap.md's M0 definition: 18 stubbed application
modules with Deliverable 2 §4's dependency graph enforced by
ApplicationModules.verify(), a working Maven/Spring Boot project, the CI
workflow relocated to .github/workflows with its api/ path bugs fixed,
migrate/deploy scripts, importable Keycloak realms, and a fix for the
app_role bootstrap script's psql variable-substitution bug that was
blocking `docker compose up`.
EOF
)"
```

Do not push to any remote as part of this task — no remote has been configured or discussed.

---

## Self-Review Notes

- **Spec coverage:** M0's stated acceptance criteria (`docs/08-implementation-roadmap.md` §4, M0 section) are: (1) `ApplicationModules.verify()` passes against 18 empty modules → Task 4; (2) `docker compose up` succeeds → Tasks 8, 9, 10; (3) CI's nine jobs go green on a no-op commit → Task 5 (the workflow can only go green once it's in `.github/workflows/` with correct paths — actually triggering GitHub Actions requires a pushed remote, which is out of this plan's scope per the "no push" constraint; Task 5's Step 4 validates the YAML locally as the closest verification available without one); (4) `db-migration-validation`'s `app_role` privilege assertion passes → already true of the existing CI job and `infra/postgres/init/01-create-app-role.sql.template`'s untouched `NOSUPERUSER NOBYPASSRLS` clauses (Task 8 Step 4 confirms this explicitly).
- **Placeholder scan:** `scripts/deploy.sh` intentionally fails loudly rather than pretending to deploy — this is a documented, explained incompleteness (matching a real open item in the roadmap), not a "TODO" left unfilled. No other step contains unresolved placeholders.
- **Type/name consistency:** module name list is identical across Task 3's generator script, Task 4's `NoCrossModuleJoinTest`, Task 5's CI-loop check, and Task 6's `migrate.sh` (16 DB-backed modules there vs. 18 in Tasks 3/4 — intentional, since `iam` and `omnichannel` own no schema, per `docs/02-module-architecture.md` §1).
