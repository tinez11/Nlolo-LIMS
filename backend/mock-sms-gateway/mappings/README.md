# mock-sms-gateway stubs

WireMock mappings standing in for **NextSMS** (`messaging-service.co.tz`), the Tanzanian
aggregator this platform sends through, so `communication`'s outbound ACL (`SmsGatewayAdapter`)
can be exercised end to end without spending credit or texting a real person. Same arrangement,
and the same reasoning, as `mock-mobile-money/mappings`.

**These mirror the real contract, and that is the whole point of them.** The first version of
this directory invented a `POST /send` taking `{to, message}` and answering `{"status":"ACCEPTED"}`,
because it was written before anybody had the aggregator's documentation. Every one of those
three details was wrong, and the adapter written against them would have failed on its first
real message while passing every test. The shapes here are transcribed from the published
Postman collection (`documenter.getpostman.com/view/4680389/SW7dX7JL`):

- `send-success.json` — `POST /api/sms/v1/text/single`, body `{from, to, text, reference}`,
  answering the real `{"messages":[{"to":…,"status":{"groupName":"PENDING","name":"PENDING_ENROUTE"},…}]}`.
  Note that **PENDING is success**: it means the aggregator accepted the message and queued it.
  An adapter treating anything but a literal `"ACCEPTED"` as a refusal — as the first draft did —
  rejects every message the gateway actually accepts.
- `balance.json` — `GET /api/sms/v1/balance`. Sends nothing and costs nothing, which makes it
  the endpoint to reach for when checking whether credentials work.

Recipient numbers here carry **no `+`**: NextSMS wants `255700000000`, while this platform stores
`+255700000000` (`TZ_PHONE_PATTERN` requires the plus). The adapter normalises, and a test asserts
it, because a `+` reaching the aggregator is a silently undelivered message.

There is deliberately no stubbed failure mapping. A failure is more usefully produced in the test
that needs one — `SmsGatewayAdapterTest` starts its own WireMock and stubs the error directly, so
the failure path sits next to the assertion about it. This directory serves the running dev stack,
where a permanently failing stub would be a nuisance rather than a feature.
