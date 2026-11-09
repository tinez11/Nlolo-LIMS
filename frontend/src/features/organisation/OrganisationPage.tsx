import { useCallback, useEffect, useState } from 'react';
import { getOrganisationSettings, type OrganisationSettings } from '@/api/organisation';
import { PageHeader } from '@/components/PageHeader';
import { RecordTabs } from '@/components/RecordTabs';
import { ErrorPanel, LoadingBlock } from '@/components/states';
import { toApiError, type ApiError } from '@/lib/apiError';
import { useOrganisationStore } from '@/store/organisationStore';
import { BranchesTab } from './BranchesTab';
import { BrandingTab } from './BrandingTab';
import { SignatoriesTab } from './SignatoriesTab';

/**
 * Settings → Organisation (2026-11-09, the user: "give the organization more freedom to market themselves in documents,
 * receipts and the exports"). Who the company is and how its documents look, with a live preview of a receipt; who
 * signs which documents; and its branches. Administrators only (organisation.manage).
 */
export function OrganisationPage() {
  const [settings, setSettings] = useState<OrganisationSettings | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const reloadBranches = useOrganisationStore((s) => s.loadBranches);

  const load = useCallback(async () => {
    setError(null);
    try {
      setSettings(await getOrganisationSettings());
    } catch (e) {
      setError(toApiError(e));
    }
  }, []);

  useEffect(() => {
    let live = true;
    getOrganisationSettings().then(
      (s) => { if (live) setSettings(s); },
      (e: unknown) => { if (live) setError(toApiError(e)); },
    );
    return () => { live = false; };
  }, []);

  /** A save changes what the other tabs and every branch picker read: read it all again. */
  const saved = useCallback(() => {
    void load();
    void reloadBranches(true);
  }, [load, reloadBranches]);

  return (
    <>
      <PageHeader
        title="Organisation"
        description="Who the company is and how it looks on every document, receipt and export."
      />
      <div className="px-6 pb-8">
        {error && !settings ? (
          <ErrorPanel error={error} onRetry={() => void load()} />
        ) : !settings ? (
          <LoadingBlock label="Loading the organisation" />
        ) : (
          <RecordTabs
            label="Organisation settings"
            tabs={[
              { value: 'branding', label: 'Company and branding', content: <BrandingTab settings={settings} onSaved={saved} /> },
              {
                value: 'signatories',
                label: 'Signatories',
                count: settings.signatories.length,
                content: <SignatoriesTab signatories={settings.signatories} onSaved={saved} />,
              },
              {
                value: 'branches',
                label: 'Branches',
                count: settings.branches.length,
                content: <BranchesTab branches={settings.branches} onSaved={saved} />,
              },
            ]}
          />
        )}
      </div>
    </>
  );
}
