# mock-sms-gateway stubs

WireMock mappings standing in for a Tanzanian SMS aggregator, so `communication`'s outbound ACL
(`SmsGatewayAdapter`) can be exercised end to end without live aggregator credentials. Exactly the
same arrangement, and the same reasoning, as `mock-mobile-money/mappings`.

`send-success.json` is the happy path: `POST /send` with a `to` field returns `ACCEPTED` and a
generated `messageId`.

There is deliberately no stubbed failure mapping here. A failure is more usefully produced in the
test that needs one — `SmsGatewayAdapterTest` starts its own WireMock and stubs a 500 directly, so
the failure path is asserted next to the assertion about it rather than depending on a file three
directories away. This directory exists for the *running dev stack*, where a permanently failing
stub would be a nuisance rather than a feature.
