#!/usr/bin/env bash
# Do the books match? (money movement step 7.) Prints every check of GET /finance/books-match and fails loudly on any
# difference: seed data the books do not agree with is a bug in the seed or in the platform, and either is worth
# stopping for. Run by seed-dev-data.sh at its end, and on its own after any money test against a local stack.
set -euo pipefail
cd "$(dirname "$0")/.."
KEYCLOAK="${KEYCLOAK_URL:-http://localhost:8081}"
API="${API_URL:-http://localhost:8080}"
source scripts/seed-lib.sh

FINANCE=$(token_for staff staff.finance "$(realm_secret staff)")
# The ledger posts after commit, so a check straight after a money move can run ahead of it.
sleep "${BOOKS_MATCH_SETTLE_SECONDS:-5}"
node -e '
  const v = JSON.parse(process.argv[1]);
  for (const c of v.checks) {
    console.log(`${c.matches ? "ok  " : "FAIL"}  ${c.name}: ledger ${c.ledger}, records ${c.records}, difference ${c.difference}`);
  }
  if (!v.allMatch) { console.error("FATAL: the books do not match -- see FAIL above."); process.exit(1); }
  console.log("The books match.");
' "$(api "$FINANCE" GET "/finance/books-match")"
