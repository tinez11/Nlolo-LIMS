import { useState } from 'react';
import { updateStaffContact, type StaffUser } from '@/api/access';
import { FormField } from '@/components/FormField';
import { InlineError } from '@/components/InlineError';
import { Panel } from '@/components/Panel';
import { Button } from '@/components/ui/button';
import { Input, Select } from '@/components/ui/input';
import type { ApiError } from '@/lib/apiError';
import { TEXT_LANGUAGES } from './textLanguages';

/**
 * Where a staff member's sign-in code goes (2026-11-14): the phone on their login and the language of their texts.
 * Another administrator changes it -- never you on your own login, since the code proves who you are. Without a phone
 * they cannot sign in once codes are switched on, so the panel says so.
 */
export function StaffContactPanel({ user, editable, onSaved }: {
  user: StaffUser;
  /** users.manage, someone else's login. */
  editable: boolean;
  onSaved: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [phone, setPhone] = useState(user.phone ?? '');
  const [language, setLanguage] = useState<'en' | 'sw'>(user.textLanguage);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function save() {
    setBusy(true);
    setError(null);
    try {
      await updateStaffContact(user.userId, phone.trim() === '' ? null : phone.trim(), language);
      setEditing(false);
      onSaved();
    } catch (cause) {
      setError(cause as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel title="Sign-in code" subtitle="Texted to this phone after the password, every time they sign in.">
      <div className="space-y-3 px-4 pb-4">
        {editing ? (
          <div className="flex flex-wrap items-end gap-2">
            <FormField label="Phone">
              <Input inputSize="sm" type="tel" value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="+255…" />
            </FormField>
            <FormField label="Texts in">
              <Select inputSize="sm" value={language} onChange={(e) => setLanguage(e.target.value as 'en' | 'sw')}>
                <option value="en">{TEXT_LANGUAGES.en}</option>
                <option value="sw">{TEXT_LANGUAGES.sw}</option>
              </Select>
            </FormField>
            <Button size="sm" variant="primary" pending={busy} disabled={busy} onClick={() => void save()}>
              Save
            </Button>
            <Button size="sm" variant="ghost" onClick={() => { setEditing(false); setPhone(user.phone ?? ''); setLanguage(user.textLanguage); }}>
              Cancel
            </Button>
          </div>
        ) : (
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-sm">
              {user.phone
                ? <><span className="tabular-nums">{user.phone}</span> · <span className="text-muted-foreground">{TEXT_LANGUAGES[user.textLanguage]}</span></>
                : <span className="text-status-warning-fg">No phone: they cannot get a sign-in code.</span>}
            </p>
            {editable && (
              <Button size="sm" onClick={() => setEditing(true)}>{user.phone ? 'Change phone' : 'Add phone'}</Button>
            )}
          </div>
        )}
        {error && <InlineError error={error} />}
      </div>
    </Panel>
  );
}
