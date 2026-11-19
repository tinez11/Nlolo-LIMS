# Test the silent staff phone set-up

Type: task
Status: resolved
Part of: [Replace Keycloak with Authentik](../map.md)

## Question

[Can Authentik text staff a sign-in code on every sign-in?](01-staff-sign-in-code.md) found that authentik can't be given a staff member's phone ahead of time, through the API or blueprints. The proposed workaround: at the first sign-in, a policy copies the login's `phone` attribute into the flow before the SMS set-up stage, so authentik texts a code with no phone screen. The source code supports this; the docs don't.

The work (AFK where possible):

1. Bring up stock authentik 2026.8.5 locally: server and worker, on a throwaway database. Keep it apart from the dev stack's Postgres data, and never run it alongside a Maven, vitest or Playwright run.
2. Build a minimal sign-in flow with a blueprint: identification, password, then a policy that copies the phone attribute, then the SMS set-up stage, using the generic provider pointed at a local request-catcher instead of the real gateway.
3. Create a user with a `phone` attribute and sign in.

Record:

- whether the code arrived at the catcher with no phone screen shown
- the exact request body
- whether the second sign-in reuses the stored phone
- what changes if the attribute is edited afterwards

If it fails, state what the person sees instead. The user then decides the fallback (a one-time phone enrolment screen, or something else) here, before the design tickets start.

Tear the test stack down afterwards and record where its blueprint is kept.

## Comments

**2026-11-19, first attempt: blocked by DNS, not by authentik.** The test stack is ready in [spike/](../spike/):

- `compose.yml`: project `ak-spike`, with its own Postgres, authentik on `127.0.0.1:9100`, and memory limits
- `blueprints/spike-sign-in.yaml`: the flow, with the copy-phone policy on the validation binding
- `catcher.py`: stands in for the backend's SMS endpoint
- `driver.py`: signs in three times through the flow-executor API — first sign-in, second sign-in, then after the phone attribute changes

The images never downloaded. The host's DNS server answers "server failure" for `pkg-containers.githubusercontent.com` and `production.cloudfront.docker.com` (the registries' download hosts), while `ghcr.io` resolves. That fails even for `busybox`. Nothing was left running.

To retry once DNS works, from `.scratch/authentik-migration/spike/`:

```
docker compose -p ak-spike -f compose.yml up -d
docker compose -p ak-spike -f compose.yml run --rm driver
docker compose -p ak-spike -f compose.yml down -v
```

Wait for the worker to apply the blueprint (about a minute) before running the driver. The blueprint and driver have not been run yet, so expect a fix or two to model fields.

**2026-11-19, second attempt: DNS worked, test run.** See the Answer below.

One design note found while writing the blueprint: if the copy-phone policy returns False (no phone on file), the validation binding is skipped and the person signs in **without** a code. The real flow needs a deny stage for that case, not a False policy.

## Answer

**The silent set-up works**, tested on stock authentik 2026.8.5, 2026-11-19. No fallback is needed.

1. **First sign-in.** The flow went identification, password, then straight to the SMS set-up stage with `phone_number_required = false`, so there was no phone screen. The code went to the number in the login's `phone` attribute. Typing it finished the sign-in, and authentik created the SMS device from that number. The catcher received:
   `{"From": "NLOLO", "To": "+255700000001", "Body": "994087", "Message": "Use this code to authenticate in authentik: 994087"}`
   with header `Authorization: Bearer spike-sms-bearer`.
2. **Second sign-in.** The flow went identification, password, then the validation stage with the one SMS device. Selecting it sends the code, which the browser UI does automatically. Typing it finished the sign-in.
3. **After changing the `phone` attribute to `+255700000002`.** The code still went to the **old** number. The device keeps the number it was created with.

Facts for the design tickets:

- **Phone changes:** to follow a changed number, either the backend sends to the phone on the **staff record** and ignores `To`, or the backend deletes the person's SMS device when their phone changes, so the next sign-in sets it up again. Decide this in [The webhook contract between Authentik and the backend](08-webhook-contract.md).
- **No phone on file:** if the copy-phone policy returns False, authentik skips the code entirely. The real flow must deny instead.
- **Wording:** the default body says "authentik" and ignores our wording. A webhook mapping (shown in the research) replaces it, and the backend writes the text.
- **The first-sign-in page** is authentik's SMS *set-up* page, with a code field only, and its visible heading wasn't checked. That belongs to [Login look in Authentik](09-login-look.md).
- **Memory under this test:** server about 700 MiB (limit 768), worker about 320 MiB, its own Postgres about 175 MiB. The server sits close to a 768 MiB limit, so the real compose should allow it about 1 GiB.
- **Where things are:** the blueprint, driver and compose are in [spike/](../spike/). The stack is torn down, with its volume removed.
