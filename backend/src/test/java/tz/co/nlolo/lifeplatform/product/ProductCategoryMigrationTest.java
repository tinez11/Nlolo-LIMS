package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The category list exists twice -- as {@link ProductCategory} and as
 * {@code product_definition_category_check} -- because a migration cannot call Java. This
 * asserts the Java half and fails loudly if it grows without the SQL half growing with it.
 *
 * <p>A pure test with no database and no Spring context: the integration tests prove the
 * constraint accepts the value, this proves nobody added a category and forgot the
 * migration.
 */
class ProductCategoryMigrationTest {

    @Test
    void creditLifeIsAProductCategory() {
        assertEquals("CREDIT_LIFE", ProductCategory.valueOf("CREDIT_LIFE").name());
    }

    /** Family funeral cover; product V24 widens the CHECK to admit it. */
    @Test
    void funeralIsAProductCategory() {
        assertEquals("FUNERAL", ProductCategory.valueOf("FUNERAL").name());
    }

    @Test
    void theCategoryListHasNotGrownUnexpectedly() {
        assertEquals(9, ProductCategory.values().length,
            "A new category must also be added to product_definition_category_check "
            + "(a migration) and to frontend/src/types/api/policy.ts");
    }
}
