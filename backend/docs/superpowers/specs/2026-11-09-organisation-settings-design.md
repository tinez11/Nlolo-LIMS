# Organisation settings: profile, branding, signatures, branches, document and report formatting

2026-11-09. The user: "a full build on organization settings ... branches, signature uploads, the logo and document
layouts ... the goal is to give the organization more freedom to market themselves in documents, receipts and the
exports", and "what about report formatting".

## Decisions (the user, 2026-11-09)

| # | Decision |
|---|---|
| O1 | Signatures are an image stamped on documents. The user will send the real signature; build with a mock-up. Certificate signing is not in this build. |
| O2 | Administrators only: one new permission, `organisation.manage`, held by the built-in ADMIN role. No second approver. Every change is audited. |
| O3 | Documents carry the head office's details only. Branches get a per-company register; no branch-based access. |
| O4 | Three preset layouts the company customises (recommended), not a free-form template editor. |
| O5 | Report exports are reformatted (the "report formatting" question): letterhead, headline figures, formatted and aligned numbers, wrapped text, headings repeated per page, page numbers; Excel cover sheet, frozen headings, filters, print setup; CSV pure data. A printed tree shows the lines as shown (option a). |

## The settings (Settings → Organisation, `organisation.manage`)

- **Profile:** legal name, trading name, TIRA registration number, TIN, head-office address, phone, email, website,
  tagline. Replaces the hard-coded "Nlolo Life".
- **Branding:** logo (PNG or JPEG, up to 1 MB), primary and secondary colour, typeface (Sans or Serif -- the PDF
  standard faces, so no font files to license or ship), layout (Classic, Banded, Minimal), paper (A4 or Letter), footer
  text, report confidentiality line. A live preview renders a sample receipt with the draft settings.
- **Signatories:** name, title, signature image, in force from; which signatory signs which document kind (policy
  schedule, receipt, payout advice, quotation, statement). A document carries the signatory in force on its issue day.
- **Branches:** code, name, address, phone, email, manager, active; one is the head office. Seeded from the shared
  BRANCH list on a company's first read. Replaces the shared list wherever a branch is chosen or checked.

## Architecture

- New module `organisation` (Spring Modulith), per-tenant tables `organisation.profile`, `organisation.branding`,
  `organisation.signatory`, `organisation.signatory_document`, `organisation.branch`; images in the document store
  (`document::api`). It depends on `document::api`, `refdata::api` (the seed), `iam::api`.
- `organisation::api` exposes `OrganisationApi`: `letterhead()` (a `Letterhead` record: names, registration, address,
  contacts, tagline, colours, typeface, layout, paper, footer, logo bytes), `signatory(kind, onDay)`, `branches()`,
  `isBranch(code)`, `headOffice()`.
- A shared renderer in the root package, `BrandedPdf`, draws the letterhead, the signature block and the footer for
  every PDF (customer documents, statements, reports), so the three layouts are one implementation.
- Readers: omnichannel (customer documents), accumulation and unitlinked (statements), reporting (exports), policy,
  underwriting and distribution (branch checks), the console (branch pickers).

## Not in this build

Certificate-based signing; a verification QR code and check page; branch-based access; per-branch addresses on
documents; email and SMS branding.
