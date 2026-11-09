import { del, get, post, put } from '@/lib/http';
import type { components } from '@/types/api/organisation';

/**
 * The company's own settings (2026-11-09, organisation settings): profile, branding and document layout, signatories
 * and branches. Read and changed by administrators; the branch list and the logo by any staff member.
 */
export type OrganisationSettings = components['schemas']['OrganisationSettings'];
export type Profile = components['schemas']['Profile'];
export type Branding = components['schemas']['Branding'];
export type Signatory = components['schemas']['Signatory'];
export type SignatoryInput = components['schemas']['SignatoryInput'];
export type DocumentKind = components['schemas']['DocumentKind'];
export type Branch = components['schemas']['Branch'];

export function getOrganisationSettings(): Promise<OrganisationSettings> {
  return get<OrganisationSettings>('/organisation/settings');
}

export function saveProfile(profile: Profile): Promise<Profile> {
  return put<Profile>('/organisation/profile', profile);
}

export function saveBranding(branding: Branding): Promise<Branding> {
  return put<Branding>('/organisation/branding', branding);
}

function form(file: File): FormData {
  const data = new FormData();
  data.append('file', file);
  return data;
}

export function uploadLogo(file: File): Promise<void> {
  return post<void>('/organisation/logo', form(file));
}

export function removeLogo(): Promise<void> {
  return del<void>('/organisation/logo');
}

/** The logo as a blob, or null when the company has none. */
export async function getLogo(): Promise<Blob | null> {
  try {
    return await get<Blob>('/organisation/logo', { responseType: 'blob' });
  } catch {
    return null;
  }
}

export function addSignatory(input: SignatoryInput): Promise<Signatory> {
  return post<Signatory>('/organisation/signatories', input);
}

export function updateSignatory(signatoryId: string, input: SignatoryInput): Promise<Signatory> {
  return put<Signatory>(`/organisation/signatories/${encodeURIComponent(signatoryId)}`, input);
}

export function uploadSignature(signatoryId: string, file: File): Promise<Signatory> {
  return post<Signatory>(`/organisation/signatories/${encodeURIComponent(signatoryId)}/signature`, form(file));
}

export async function getSignature(signatoryId: string): Promise<Blob | null> {
  try {
    return await get<Blob>(`/organisation/signatories/${encodeURIComponent(signatoryId)}/signature`, { responseType: 'blob' });
  } catch {
    return null;
  }
}

export function listBranches(): Promise<Branch[]> {
  return get<Branch[]>('/organisation/branches');
}

export function saveBranch(branch: Branch): Promise<Branch> {
  return put<Branch>(`/organisation/branches/${encodeURIComponent(branch.code)}`, branch);
}

/** A sample receipt in the draft profile and branding, before either is saved. */
export function previewDocument(profile: Profile, branding: Branding): Promise<Blob> {
  return post<Blob>('/organisation/preview', { profile, branding }, { responseType: 'blob', headers: { Accept: 'application/pdf' } });
}
