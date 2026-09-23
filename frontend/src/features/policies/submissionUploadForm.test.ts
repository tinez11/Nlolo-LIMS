import { describe, expect, it } from 'vitest';
import {
  MAX_UPLOAD_BYTES,
  acceptAttribute,
  submissionUploadSchema,
} from './submissionUploadForm';

function fileOf(name: string, bytes: number, type = 'text/csv'): File {
  return new File([new Uint8Array(bytes)], name, { type });
}

describe('submissionUploadForm', () => {
  it('accepts a CSV for either kind', () => {
    for (const kind of ['enrolment', 'exits'] as const) {
      const result = submissionUploadSchema(kind).safeParse({
        file: fileOf('august.csv', 2048),
      });
      expect(result.success, kind).toBe(true);
    }
  });

  it('accepts an XLSX enrolment schedule but refuses an XLSX exits file', () => {
    // The asymmetry is the backend's, mirrored here so a doomed upload fails at the file picker
    // rather than after the round trip. A lender exports a schedule from a spreadsheet; an exits
    // file comes out of a loan system, and admitting XLSX there would buy a second set of
    // numeric-coercion traps for nobody.
    const xlsx = fileOf('august.xlsx', 4096);

    expect(submissionUploadSchema('enrolment').safeParse({ file: xlsx }).success).toBe(true);

    const exits = submissionUploadSchema('exits').safeParse({ file: xlsx });
    expect(exits.success).toBe(false);
    if (!exits.success) {
      expect(exits.error.issues[0]?.message).toBe('An exits file must be a CSV');
    }
  });

  it('refuses a file that is not a schedule at all, and says which formats are allowed', () => {
    const result = submissionUploadSchema('enrolment').safeParse({
      file: fileOf('scan-of-the-agreement.pdf', 9000, 'application/pdf'),
    });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0]?.message).toBe(
        'An enrolment schedule must be a CSV or an XLSX',
      );
    }
  });

  it('refuses an empty file', () => {
    // A zero-byte file is a failed export, and it would otherwise come back as a header-only
    // refusal from the server after an upload nobody needed to make.
    const result = submissionUploadSchema('enrolment').safeParse({ file: fileOf('empty.csv', 0) });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0]?.message).toBe('That file is empty');
    }
  });

  it('refuses a file over 10 MB', () => {
    const result = submissionUploadSchema('enrolment').safeParse({
      file: fileOf('everything.csv', MAX_UPLOAD_BYTES + 1),
    });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0]?.message).toContain('larger than 10 MB');
    }
  });

  it('refuses a missing file rather than throwing', () => {
    const result = submissionUploadSchema('exits').safeParse({ file: undefined });
    expect(result.success).toBe(false);
  });

  it('is case-insensitive about the extension', () => {
    // A Windows export is as likely to be .CSV as .csv, and refusing it would be the console
    // being stricter than the platform it fronts.
    expect(
      submissionUploadSchema('exits').safeParse({ file: fileOf('NOVEMBER.CSV', 512) }).success,
    ).toBe(true);
  });

  it('advertises the right formats to the file picker for each kind', () => {
    expect(acceptAttribute('enrolment')).toBe('.csv,.xlsx');
    expect(acceptAttribute('exits')).toBe('.csv');
  });
});
