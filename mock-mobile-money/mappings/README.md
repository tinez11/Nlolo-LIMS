# Mock Mobile Money — WireMock stub mappings

Empty at M0 intentionally. Real M-Pesa / Airtel Money / Tigo Pesa callback stub
mappings belong to the `payment` module and land with M5 (see
`docs/08-implementation-roadmap.md` §4, M5 — Money Out), where `payment`'s
mobile-money ACL integration tests are defined.

## M5: stubs landed

- `disburse-success.json` — any `/disburse` POST with a `payeeRef` gets
  `{"status":"ACCEPTED","gatewayReference":"MM-<random>"}`.
- `disburse-insufficient-funds.json` — higher priority than the success stub;
  matches on the sentinel payee `MPESA-0000000000` so local dev and manual
  exploratory testing can deliberately exercise the rail-rejection branch
  (`{"status":"REJECTED","reason":"INSUFFICIENT_FLOAT"}`) without needing a
  real aggregator sandbox account with a depleted float.
- `collect-success.json` — same shape as `disburse-success.json` but for
  `/collect`, matching on `payerRef`.

These mappings are served by the `mock-mobile-money` compose service
(`infra/docker-compose.yml`, image `wiremock/wiremock:3.5.4`, bind-mounted
read-only into the container) for local dev. `payment`'s automated tests
(`MobileMoneyGatewayAdapterTest`) run an equivalent WireMock instance
in-process instead, on a random port, because CI's integration tests are
Testcontainers-based and cannot reach a compose-only service -- see the
`wiremock-standalone` dependency comment in `pom.xml`.
