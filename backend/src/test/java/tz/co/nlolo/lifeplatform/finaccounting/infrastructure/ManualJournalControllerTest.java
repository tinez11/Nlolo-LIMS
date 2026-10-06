package tz.co.nlolo.lifeplatform.finaccounting.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ManualJournalControllerTest {

    /**
     * document.document_record.owner_context is VARCHAR(50). "manual-journal:" plus a UUID is 51, and every upload
     * failed with a 500 the integration tests never saw -- they attach a reference without storing a file.
     */
    @Test
    void aJournalsDocumentOwnerFitsTheDocumentStore() {
        String owner = ManualJournalController.ownerContextOf(UUID.randomUUID());
        assertThat(owner).startsWith("journal:").hasSizeLessThanOrEqualTo(50);
    }
}
