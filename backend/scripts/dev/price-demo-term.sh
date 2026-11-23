#!/usr/bin/env bash
# Gives an ALREADY-SEEDED dev database's Demo Term Life a priced version (2026-11-23). seed-dev-data.sh now publishes
# it with base rates, but it refuses to run twice, and a database seeded before then has a version that can be sold
# but never quoted, so the open-case form keeps "Open case" disabled. This publishes a new version, effective today,
# from the same demo_term_version body (scripts/seed-lib.sh). Policies already issued keep the version they were
# issued on. Through the real API, as staff.admin, like the seed.
#
# Usage, from backend/: scripts/dev/price-demo-term.sh
set -euo pipefail
cd "$(dirname "$0")/../.."

API="${API_URL:-http://localhost:8080}"
source scripts/seed-lib.sh

ADMIN=$(token_for staff.admin)
PRODUCT_ID=$(api "$ADMIN" GET "/products" | node -e '
  const all = JSON.parse(require("fs").readFileSync(0, "utf8"));
  const list = Array.isArray(all) ? all : (all.content ?? all.items ?? []);
  const p = list.find((x) => x.productCode === "DEMO-TERM-01");
  if (!p) { console.error("No DEMO-TERM-01 product: seed the database first (scripts/seed-dev-data.sh)"); process.exit(1); }
  console.log(p.productId);')

TODAY=$(date +%Y-%m-%d)
echo "Publishing a priced Demo Term Life version effective $TODAY on product $PRODUCT_ID"
curl -sfS -X POST "$API/products/$PRODUCT_ID/versions" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuid)" -d "$(demo_term_version "$TODAY")" -o /dev/null -w "HTTP %{http_code}\n"

# Proved by a quote, which is what the open-case form asks for (the active snapshot does not list base rates).
curl -sfS -X POST "$API/products/$PRODUCT_ID/premium-quote" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' \
  -d '{"sumAssuredAmount":5000000,"sumAssuredCurrency":"TZS","dateOfBirth":"1990-05-01","sex":"FEMALE","smokerStatus":"NON_SMOKER","occupationClass":"1","frequency":"QUARTERLY"}' \
  | node -e '
    const q = JSON.parse(require("fs").readFileSync(0, "utf8"));
    console.log("quotes on version " + q.productVersionId + ": " + q.ratePerMille + " per 1,000, " + q.annualBase + " a year");'
