-- The IFRS 17 measurement model belongs to the VERSION, not the product.
--
-- WHAT HAPPENED. publishVersion ended with an unconditional
-- product.activateWithMeasurementModel(...), and the column lived on product_definition. So
-- republishing a product with a different model silently rewrote the measurement basis of every
-- in-force contract ever issued under that product, retroactively. There is no test for it and
-- nothing outside the product module reads the field, which is why it stayed invisible.
--
-- WHY MOVE IT RATHER THAN GUARD IT. PAA eligibility turns on the coverage period, and
-- min_term_months / max_term_months already live on product_version. The fact that decides the
-- model sat on the version while the model itself sat on the product, so a product whose second
-- version sells a thirty-year term instead of a twelve-month one had no way to say its model had
-- changed except by rewriting the first version's. Moving the column puts the decision next to
-- its evidence, and makes in-force contracts immune by construction: a policy pins a
-- product_version_id, so it keeps its own basis forever. A legitimate change becomes a new
-- version, which is what it always should have been.
--
-- BACKFILL IS SAFE, verified against the dev database before writing this: all 128 existing
-- versions join to a product carrying a non-null model, and no DRAFT product has a version. A
-- DRAFT product has a null model and no versions, because a version only exists after a publish
-- and every publish supplies one.
--
-- Written as ONE migration rather than the two-phase expand/contract sequence, because the column
-- has no readers outside the product module and there is no rolling-deploy window to protect. If
-- this platform ever adopts rolling deploys, this is the migration to split.

ALTER TABLE product.product_version
    ADD COLUMN ifrs_measurement_model VARCHAR(10)
    CHECK (ifrs_measurement_model IN ('GMM','PAA'));

UPDATE product.product_version pv
   SET ifrs_measurement_model = pd.ifrs_measurement_model
  FROM product.product_definition pd
 WHERE pd.product_id = pv.product_id;

ALTER TABLE product.product_version
    ALTER COLUMN ifrs_measurement_model SET NOT NULL;

ALTER TABLE product.product_definition
    DROP COLUMN ifrs_measurement_model;

COMMENT ON COLUMN product.product_version.ifrs_measurement_model IS
    'GMM or PAA, decided per version. A policy pins a product_version_id, so its measurement '
    'basis cannot be changed by a later republish -- which is exactly what happened while this '
    'column lived on product_definition.';
