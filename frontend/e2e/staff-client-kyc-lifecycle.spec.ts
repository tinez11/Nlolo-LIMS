import { test, expect, request as apiRequest } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * The three builds, against the running stack, as a real agent and a real staff member.
 *
 * Real Keycloak tokens from the real realms -- a fabricated JWT would prove the code path and
 * not the credential path, and this platform has been bitten by exactly that twice.
 */

const API = 'http://localhost:8080';
const KEYCLOAK = 'http://localhost:8081';
const PASSWORD = 'devpassword';

function clientSecret(realmFile: string): string {
  const realm = JSON.parse(readFileSync(resolve(`../backend/keycloak/${realmFile}`), 'utf8')) as {
    clients?: { clientId?: string; secret?: string }[];
  };
  const secret = realm.clients?.find((c) => c.clientId === 'lifeplatform-app')?.secret;
  if (!secret) throw new Error(`no secret in ${realmFile}`);
  return secret;
}

async function token(http: any, realm: string, realmFile: string, username: string) {
  const res = await http.post(`${KEYCLOAK}/realms/${realm}/protocol/openid-connect/token`, {
    form: {
      grant_type: 'password',
      client_id: 'lifeplatform-app',
      client_secret: clientSecret(realmFile),
      username,
      password: PASSWORD,
    },
  });
  expect(res.ok(), `token for ${username} -> ${res.status()} ${await res.text()}`).toBeTruthy();
  return (await res.json()).access_token as string;
}

const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
  'base64',
);

test('client reference, required fields, and who may file or verify KYC', async () => {
  const http = await apiRequest.newContext();
  const stamp = Date.now().toString().slice(-6);
  try {
    const agent = await token(http, 'agents', 'agents-realm.json', 'agent.senior');
    const staff = await token(http, 'staff', 'staff-realm.json', 'staff.admin');

    // ---- 1. an agent registers a full client, carrying their own reference ----
    const reference = `CLT-${stamp}`;
    const created = await http.post(`${API}/parties/individuals`, {
      headers: { Authorization: `Bearer ${agent}` },
      data: {
        fullName: `Live Check ${stamp}`,
        dateOfBirth: '1990-05-12',
        sex: 'FEMALE',
        idType: 'NATIONAL_ID',
        idNumber: `19900512-${stamp}-00001-11`,
        clientReference: reference,
        contactInfo: { phoneNumber: `+2557${stamp}01` },
      },
    });
    expect(created.ok(), `register -> ${created.status()} ${await created.text()}`).toBeTruthy();
    const partyId = (await created.json()).partyId as string;
    console.log(`\n1. registered ${partyId} with reference ${reference}`);

    const detail = await (
      await http.get(`${API}/parties/${partyId}`, { headers: { Authorization: `Bearer ${agent}` } })
    ).json();
    console.log(`   read back: clientReference=${detail.clientReference} sex=${detail.sex}`);
    expect(detail.clientReference).toBe(reference);

    // ---- 2. the same reference twice is refused ----
    const duplicate = await http.post(`${API}/parties/individuals`, {
      headers: { Authorization: `Bearer ${agent}` },
      data: {
        fullName: `Duplicate Ref ${stamp}`,
        dateOfBirth: '1991-06-13',
        sex: 'MALE',
        idType: 'NATIONAL_ID',
        idNumber: `19910613-${stamp}-00002-11`,
        clientReference: reference,
        contactInfo: { phoneNumber: `+2557${stamp}02` },
      },
    });
    console.log(`2. duplicate reference -> ${duplicate.status()} (expected not 2xx)`);
    expect(duplicate.ok()).toBeFalsy();

    // ---- 3. the required fields, on the server ----
    const noSex = await http.post(`${API}/parties/individuals`, {
      headers: { Authorization: `Bearer ${agent}` },
      data: {
        fullName: `No Sex ${stamp}`,
        dateOfBirth: '1990-05-12',
        idType: 'NATIONAL_ID',
        idNumber: `19900512-${stamp}-00003-11`,
        contactInfo: { phoneNumber: `+2557${stamp}03` },
      },
    });
    console.log(`3a. no sex -> ${noSex.status()} ${(await noSex.json()).errorCode ?? ''}`);
    expect(noSex.status()).toBe(422);

    const noId = await http.post(`${API}/parties/individuals`, {
      headers: { Authorization: `Bearer ${agent}` },
      data: {
        fullName: `No Id ${stamp}`,
        dateOfBirth: '1990-05-12',
        sex: 'MALE',
        contactInfo: { phoneNumber: `+2557${stamp}04` },
      },
    });
    console.log(`3b. no identity document -> ${noId.status()} ${(await noId.json()).errorCode ?? ''}`);
    expect(noId.status()).toBe(422);

    // ---- 4. the agent files KYC evidence for their own client ----
    const upload = await http.post(`${API}/parties/${partyId}/kyc-evidence`, {
      headers: { Authorization: `Bearer ${agent}` },
      multipart: { file: { name: 'id-scan.png', mimeType: 'image/png', buffer: PNG } },
    });
    console.log(`4. agent uploads own client's evidence -> ${upload.status()}`);
    expect(upload.status()).toBe(201);
    const documentRef = (await upload.json()).documentRef as string;

    // ---- 5. the agent may NOT verify ----
    const agentVerify = await http.post(`${API}/parties/${partyId}/kyc`, {
      headers: { Authorization: `Bearer ${agent}` },
      data: { status: 'VERIFIED', evidenceDocumentRef: documentRef },
    });
    console.log(`5. agent tries to verify -> ${agentVerify.status()} (expected 403)`);
    expect(agentVerify.status()).toBe(403);

    // ---- 6. staff verify the evidence the agent filed ----
    const staffVerify = await http.post(`${API}/parties/${partyId}/kyc`, {
      headers: { Authorization: `Bearer ${staff}` },
      data: { status: 'VERIFIED', evidenceDocumentRef: documentRef },
    });
    console.log(`6. staff verifies -> ${staffVerify.status()}`);
    expect(staffVerify.ok()).toBeTruthy();

    const verified = await (
      await http.get(`${API}/parties/${partyId}`, { headers: { Authorization: `Bearer ${staff}` } })
    ).json();
    console.log(`   final kycStatus=${verified.kycStatus} reference=${verified.clientReference}`);
    expect(verified.kycStatus).toBe('VERIFIED');

    // ---- 7. and the half that makes opening the endpoint safe ----
    // A client registered by STAFF is not on the agent's book, so the agent may neither read
    // it nor file documents against it. Without this the upload permission would let any agent
    // write to any party in the tenant.
    const staffsClient = await http.post(`${API}/parties/individuals`, {
      headers: { Authorization: `Bearer ${staff}` },
      data: {
        fullName: `Staff Registered ${stamp}`,
        dateOfBirth: '1988-02-09',
        contactInfo: { phoneNumber: `+2557${stamp}05` },
      },
    });
    expect(staffsClient.ok(), `staff register -> ${staffsClient.status()}`).toBeTruthy();
    const othersPartyId = (await staffsClient.json()).partyId as string;

    const trespass = await http.post(`${API}/parties/${othersPartyId}/kyc-evidence`, {
      headers: { Authorization: `Bearer ${agent}` },
      multipart: { file: { name: 'id-scan.png', mimeType: 'image/png', buffer: PNG } },
    });
    console.log(`7. agent files against somebody else's client -> ${trespass.status()} (expected 403)`);
    expect(trespass.status()).toBe(403);

    const peek = await http.get(`${API}/parties/${othersPartyId}`, {
      headers: { Authorization: `Bearer ${agent}` },
    });
    console.log(`   and reading it -> ${peek.status()} (expected 403)`);
    expect(peek.status()).toBe(403);
  } finally {
    await http.dispose();
  }
});
