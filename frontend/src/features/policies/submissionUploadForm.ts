import { z } from 'zod';

/**
 * Choosing the file. The only thing the console can check before the server sees it.
 *
 * **Deliberately thin, and that is the point.** Every judgement that matters — is this borrower
 * already enrolled, is the disbursement date in the future, is the entry age within the product's
 * bounds — is made row by row by the backend, and a client-side pre-check would either duplicate
 * those rules (and drift from them) or invent reassurance the platform cannot stand behind. What
 * is checked here is only what a browser genuinely knows: that a file was chosen, that it is not
 * empty, that it is not absurdly large, and that its name ends in a format the endpoint accepts.
 *
 * The format lists differ by kind and that is not an oversight. An enrolment schedule may be CSV
 * or XLSX, because a lender exports it from a spreadsheet. An exits file is CSV only — it is a
 * short list generated from a loan system, and admitting XLSX would mean a second set of numeric
 * coercion traps for nobody's benefit. The backend enforces exactly this asymmetry; this schema
 * mirrors it so a doomed upload fails at the file picker instead of after the round trip.
 */

/** 10 MB. A lender's file is hundreds of rows, not hundreds of thousands: anything larger is a
 * mistake worth catching before it occupies a multipart request. The backend's own multipart
 * limit is the real guard; this one exists to say so in the person's own words. */
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

export type SubmissionKind = 'enrolment' | 'exits';

/**
 * Both kinds take either format now.
 *
 * <b>Exits was CSV-only until a real lender's month proved that wrong.</b> The argument was that
 * an exits file is a short list generated from a loan system, so admitting a second format would
 * buy a second set of numeric-coercion traps for nobody. What it missed is that the file carries a
 * DATE, and a CSV cannot survive Excel — it rewrites dates on open and again on save. Two
 * enrolment files were refused entire that way before that side moved to a workbook, and exits sat
 * exposed to the identical failure, having simply not been reached yet.
 */
const EXTENSIONS: Record<SubmissionKind, readonly string[]> = {
  enrolment: ['.csv', '.xlsx'],
  exits: ['.csv', '.xlsx'],
};

/** What the file input should advertise, so the OS dialog filters before the person picks. */
export function acceptAttribute(kind: SubmissionKind): string {
  return EXTENSIONS[kind].join(',');
}

function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot < 0 ? '' : name.slice(dot).toLowerCase();
}

export function submissionUploadSchema(kind: SubmissionKind) {
  const allowed = EXTENSIONS[kind];
  return z.object({
    file: z
      .instanceof(File, { message: 'Choose a file to upload' })
      .refine((f) => f.size > 0, { message: 'That file is empty' })
      .refine((f) => f.size <= MAX_UPLOAD_BYTES, {
        message: 'That file is larger than 10 MB. A monthly file should be well under it.',
      })
      .refine((f) => allowed.includes(extensionOf(f.name)), {
        message:
          kind === 'exits'
            ? 'An exits file must be a CSV or an XLSX'
            : 'An enrolment schedule must be a CSV or an XLSX',
      }),
  });
}

export type SubmissionUploadValues = z.infer<ReturnType<typeof submissionUploadSchema>>;
