#!/usr/bin/env node
// Nlolo Hazina -- a unit-linked product, its three funds and one real policy, configured in the dev platform for a demo.
//
// Authored 2026-10-23. Like malengo-demo.sh it speaks the REAL REST API with REAL Keycloak tokens, each step as the
// role that would click it: finance proposes fund prices, the admin (a second person) approves them, an agent opens
// the case, a senior underwriter assesses, an underwriter decides, staff request the premium on the mock rail.
//
//   node scripts/hazina-demo.mjs setup            funds, a year of weekly prices, the product and its version
//   node scripts/hazina-demo.mjs sell             Martin Fredy Lema's case, acceptance, first premium requested
//   node scripts/hazina-demo.mjs price <date> <equity> <balanced> <moneyMarket>
//                                                  propose AND approve one day's prices (after that day's cut-off)
//
// Forward pricing is the rule (spec U1 §5): money that arrives before a fund's 12:00 cut-off is bound to that day's
// price and nothing earlier, so a policy shows a gain only once a LATER price is approved. The year of history makes
// the funds' own performance visible from the start; the policy's gain follows with the next prices.
//
// Not idempotent on purpose (see seed-dev-data.sh): re-running `setup` fails on the existing fund codes.
import { readFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const KEYCLOAK = process.env.KEYCLOAK_URL ?? "http://localhost:8081";
const API = process.env.API_URL ?? "http://localhost:8080";
const MARTIN = process.env.HAZINA_CLIENT_PARTY_ID ?? "498eed59-7b3a-4446-a4de-298d9961cf44"; // Martin Fredy Lema
const PRODUCT_CODE = "UL-HAZINA";
const CUT_OFF = "12:00:00";

export const FUNDS = [
  { code: "HZ-EQUITY", name: "Hazina Equity Fund", assetClass: "EQUITY", charge: "1.5" },
  { code: "HZ-BALANCED", name: "Hazina Balanced Fund", assetClass: "BALANCED", charge: "1.0" },
  { code: "HZ-MMF", name: "Hazina Money Market Fund", assetClass: "MONEY_MARKET", charge: "0.5" },
];

function secret(realm) {
  const json = JSON.parse(readFileSync(resolve(ROOT, `keycloak/${realm}-realm.json`), "utf8"));
  return json.clients.find((c) => c.clientId === "lifeplatform-app").secret;
}
async function token(realm, username) {
  const res = await fetch(`${KEYCLOAK}/realms/${realm}/protocol/openid-connect/token`, {
    method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "password", client_id: "lifeplatform-app", client_secret: secret(realm), username, password: "devpassword" }),
  });
  if (!res.ok) throw new Error(`token ${realm}/${username}: ${res.status} ${await res.text()}`);
  return (await res.json()).access_token;
}
async function api(tok, method, path, body) {
  const res = await fetch(`${API}${path}`, {
    method, headers: { Authorization: `Bearer ${tok}`, "Content-Type": "application/json", "Idempotency-Key": randomUUID() },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path}: ${res.status} ${text}`);
  return text ? JSON.parse(text) : null;
}

// One year of weekly Friday prices to yesterday. Deterministic, so the PDF's figures match what was posted.
export function priceHistory() {
  const dates = [];
  for (let d = new Date(Date.UTC(2025, 9, 24)); d <= new Date(Date.UTC(2026, 9, 16)); d.setUTCDate(d.getUTCDate() + 7)) dates.push(d.toISOString().slice(0, 10));
  dates.push("2026-10-22");
  const n = dates.length;
  let seed = 7;
  let first = true;
  const noise = () => { const r = (seed = (seed * 9301 + 49297) % 233280) / 233280 - 0.5; return first ? 0 : r; };
  const series = { "HZ-EQUITY": [], "HZ-BALANCED": [], "HZ-MMF": [] };
  for (let i = 0; i < n; i++) {
    const t = i / (n - 1);
    // Equity: +14% over the year with a dip of about 7% around March.
    const dip = -0.075 * Math.exp(-Math.pow((t - 0.42) / 0.09, 2));
    series["HZ-EQUITY"].push(1 * (1 + 0.14 * t + dip + noise() * 0.012));
    series["HZ-BALANCED"].push(1 * (1 + 0.09 * t + dip * 0.4 + noise() * 0.005));
    series["HZ-MMF"].push(1 * Math.pow(1.06, t));
    first = false;
  }
  return dates.map((date, i) => ({ date, prices: Object.fromEntries(Object.entries(series).map(([k, v]) => [k, v[i].toFixed(6)])) }));
}

async function proposeAndApprove(finance, admin, code, date, price, reason) {
  const p = await api(finance, "POST", "/fund-prices", { fundCode: code, valuationDate: date, price, moveReason: reason });
  return api(admin, "POST", `/fund-prices/${p.priceId}/approval`);
}

async function setup() {
  const finance = await token("staff", "staff.finance");
  const admin = await token("staff", "staff.admin");
  for (const f of FUNDS) {
    await api(finance, "POST", "/funds", { code: f.code, name: f.name, currency: "TZS", assetClass: f.assetClass, annualManagementChargePercent: f.charge, cutOffTime: CUT_OFF });
    console.log(`fund ${f.code} added`);
  }
  for (const { date, prices } of priceHistory()) {
    for (const f of FUNDS) await proposeAndApprove(finance, admin, f.code, date, prices[f.code], "Weekly valuation from the fund manager");
  }
  console.log(`prices: ${priceHistory().length} weekly valuations per fund, approved`);

  const product = await api(admin, "POST", "/products", { productCode: PRODUCT_CODE, productName: "Nlolo Hazina", category: "UNIT_LINKED", portfolioCode: "ULIP", defaultCurrency: "TZS" });
  await api(admin, "POST", `/products/${product.productId}/versions`, {
    ifrsMeasurementModel: "GMM", effectiveDate: "2026-10-01",
    payoutTerms: { freeLookDays: 15 },
    tiraFiling: { reference: "TIRA/DEMO/UL/HAZINA/0001", approvalDate: "2026-10-01" },
    eligibility: { minEntryAge: 18, maxEntryAge: 65 },
    ratingTable: [],
    benefitSchedule: [{ benefitType: "DEATH", calculationMethod: "SUM_ASSURED" }],
    unitLinked: {
      fundCodes: FUNDS.map((f) => f.code),
      allocationBands: [{ fromYear: 1, toYear: 2, percent: 90 }, { fromYear: 3, toYear: null, percent: 98 }],
      monthlyPolicyFee: 2000,
      mortalityBasis: "UNISEX",
      mortality: [{ ageFrom: 18, ageTo: 29, annualRatePerMille: 0.9 }, { ageFrom: 30, ageTo: 39, annualRatePerMille: 1.4 },
        { ageFrom: 40, ageTo: 49, annualRatePerMille: 3.0 }, { ageFrom: 50, ageTo: 59, annualRatePerMille: 7.5 }, { ageFrom: 60, ageTo: null, annualRatePerMille: 20 }],
      deathRule: "HIGHER_OF", lapseRule: "EXHAUSTION",
      minimumSurrenderYears: 2, lowFundWarningMonths: 3,
      premiumMinimums: [{ frequency: "MONTHLY", amount: 50000 }, { frequency: "ANNUALLY", amount: 500000 }],
      sumAssuredMultipleMin: 5, sumAssuredMultipleMax: 20,
      options: { freeSwitchesPerYear: 2, switchFee: 5000, minimumWithdrawal: 100000, minimumRemainingValue: 500000,
        withdrawalReducesSumAssured: false, topUpAllocationPercent: 98, minimumTopUp: 50000,
        surrenderCharges: [{ fromYear: 1, toYear: 1, percent: 10 }, { fromYear: 2, toYear: 5, percent: 5 }, { fromYear: 6, toYear: null, percent: 0 }] },
    },
  });
  console.log(`product ${PRODUCT_CODE} ${product.productId} published`);
}

async function sell() {
  const admin = await token("staff", "staff.admin");
  const agent = await token("agents", "agent.senior"); // Juma Senior, who registered Martin
  const senior = await token("staff", "staff.senior");
  const underwriter = await token("staff", "staff.underwriter");
  const product = (await api(admin, "GET", "/products")).find((p) => p.productCode === PRODUCT_CODE);
  const snap = await api(admin, "GET", `/products/${product.productId}/active-snapshot`);
  const sumAssured = "18000000.00";
  const kase = await api(agent, "POST", "/underwriting/cases", {
    applicantPartyId: MARTIN, productId: product.productId, productVersionId: snap.productVersionId,
    sumAssured: { amount: sumAssured, currencyCode: "TZS" }, premiumFrequency: "MONTHLY",
    unitLinked: { split: [{ fundCode: "HZ-EQUITY", percent: 60 }, { fundCode: "HZ-BALANCED", percent: 40 }], premium: 150000, frequency: "MONTHLY", sumAssured: 18000000 },
  });
  console.log(`case ${kase.caseId} opened by agent.senior`);
  const a = await api(senior, "POST", `/underwriting/cases/${kase.caseId}/assessments`, { assessmentType: "MEDICAL", findings: "Healthy 23-year-old, non-smoker declared, no adverse findings", riskScore: 10 });
  console.log(`assessed: recommendation ${a.recommendationOutcome}`);
  await api(underwriter, "POST", `/underwriting/cases/${kase.caseId}/decision`, { outcome: "ACCEPT", reason: "Accepted at the chosen premium and cover" });
  let policy;
  for (let i = 0; i < 30 && !policy; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    const list = await api(admin, "GET", `/policies?policyholderPartyId=${MARTIN}`);
    policy = (list.items ?? list.content ?? list).find((p) => p.productId === product.productId);
  }
  if (!policy) throw new Error("no policy issued after 30s");
  console.log(`policy ${policy.policyNumber} issued`);
  let invoice;
  for (let i = 0; i < 30 && !invoice; i++) {
    const inv = await api(admin, "GET", `/policies/${policy.policyNumber}/invoices`);
    invoice = (inv.items ?? inv).filter((x) => x.status === "DUE" || x.status === "ISSUED" || x.status === "OPEN").sort((x, y) => (x.dueDate < y.dueDate ? -1 : 1))[0];
    if (!invoice) await new Promise((r) => setTimeout(r, 1000));
  }
  if (!invoice) throw new Error("no invoice raised");
  await api(admin, "POST", `/invoices/${invoice.invoiceId}/payment-request`, { payerRef: "+255754144369" });
  console.log(`first premium requested on invoice ${invoice.invoiceId} (${invoice.dueDate}) from +255754144369`);
}

async function price(date, ...values) {
  const finance = await token("staff", "staff.finance");
  const admin = await token("staff", "staff.admin");
  for (let i = 0; i < FUNDS.length; i++) {
    const v = await proposeAndApprove(finance, admin, FUNDS[i].code, date, values[i], "Daily valuation from the fund manager");
    console.log(`${FUNDS[i].code} ${date} ${v.price} ${v.status}`);
  }
}

const [cmd, ...rest] = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url) ? process.argv.slice(2) : [null];
if (cmd === null) { /* imported for priceHistory() */ }
else if (cmd === "setup") await setup();
else if (cmd === "sell") await sell();
else if (cmd === "price") await price(...rest);
else if (cmd !== "history") { console.error("usage: setup | sell | price <date> <equity> <balanced> <mm>"); process.exit(1); }
if (cmd === "history") console.log(JSON.stringify(priceHistory()));
