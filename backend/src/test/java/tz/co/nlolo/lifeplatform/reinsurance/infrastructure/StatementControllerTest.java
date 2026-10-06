package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StatementControllerTest {

    /** document_record.owner_context is VARCHAR(50); I4's "manual-journal:{uuid}" (51) failed every upload. */
    @Test
    void aStatementsDocumentOwnerFitsTheDocumentStore() {
        assertThat(StatementController.ownerContextOf(UUID.randomUUID()))
            .startsWith("statement:").hasSizeLessThanOrEqualTo(50);
    }
}
