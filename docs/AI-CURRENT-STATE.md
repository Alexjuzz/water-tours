# Water Tours — recovery checkpoint, 2026-09-13

Read this first when resuming. This is a sanitized code handoff; credentials and machine-specific operational notes remain in the owner's local hub, not Git.

## Current source and deployment
- Working branch: `work/tasks-6-7-9-11`, head `8891fca` (payment-support stage, deployed 2026-09-14). Canonical WordPress code: `integrations/wordpress/`. Do not use the separate legacy WordPress repository.
- Latest implementation before the new design: `2931bba`. Earlier: `6577ac9` staff test-ticket mail buttons; `72eb9d4` checkout UX; `485337f` truthful pending-payment screen.
- Worker reported those changes pushed to the same remote branch and deployed. No merge to main is claimed.
- Active live theme: `water-tours-river`, deployed and activated 2026-09-13 (see "River theme deployed" below). `water-tours-prototype` and `water-tours-editorial` remain installed and inactive as rollback. Owner rejected editorial design; retain it as history, do not activate it.
- Prototype CTA colors swapped: ticket sand #ffd27a; own-route lime #d6ef7c. Header/mobile CTA, immediate check-payment feedback, bounded timeout, single provider-return restoration and accessible saved-order resume implemented.

## Product invariants
- Regular tickets and private-boat checkout reuse `water-tours-buy`, never duplicate payment logic in a theme.
- Ticket valid 72 hours from confirmed payment, one admission, no reserved departure or seat. Private boat 1–6 guests, duration selected in purchase form.
- Prices come from the versioned backend/catalog through the purchase plugin. Preserve owner edits; unusually high prices are not evidence of a bug.
- Staff login/dashboard/prices/test-order belong to Spring. WordPress admin credentials are separate. Changing an env password alone does not rotate an existing database account.
- SMTP configuration works after port unblock; automatic ticket delivery implemented with historical queue held. Do not bulk-send held mail or enable local-checkout on production.

## Verification boundaries and unresolved work
- Checkout worker completed provider sandbox payments for boat and regular ticket. Boat backend PAID/ticket issued verified. Regular backend outcome and SMTP acceptance for both were not captured: worker access was blocked. Do not create duplicate payments to resolve this; inspect existing orders via authorized read-only access.
- Order IDs and evidence are in local `target/checkout-ux-20260913/RESULT.md`. Test success is not proof of inbox delivery.
- Design worker reported PHP lint + desktop/mobile checks and theme deployment in `target/design-alternative-20260913/RESULT.md`. This does not establish a full production acceptance test.
- Backup scripts were repaired earlier; complete checksummed backup captured. Restore drill remains unverified.
- Older untracked STATUS.md/PROBLEMS.md are historical local notes, not authoritative current deployment status. Preserve them outside commits.

## Current authorized task
1. Commit this recovery checkpoint BEFORE new design source changes.
2. Codex owns a new design using Sites guidance and owner photos; previous editorial option rejected. Preserve existing purchase flows and prior themes. Build preview and a deployable WordPress theme using the same rendering source.
3. Opus may assist when useful; owner requires Opus for deployment. Give it an exact source/commit, backup, activation, rollback and final verification scope. Never delegate Sites ownership or credentials.
4. User requested existing production deployment, not hosting migration. Keep WordPress/backend architecture; local Sites-guided preview does not require a new hosted Site.
5. Update this document with actual files, checks, commit and deployment status. Distinguish verified results from worker reports. No secrets or private hub contents in Git.

## Collaboration
- Owner communication Russian; worker prompts and reports concise English. Opus only for Claude work. No unnecessary intermediate/full-suite tests.
- Owner photos live outside repository; use optimized copies only, preserve originals. Already optimized photo assets exist in editorial theme. Inspect visually before reuse.
- No real financial transactions, password changes, unsolicited messages or infrastructure migration under this design task.

## Codex River design — completed locally
- Pre-design recovery commit: `89af78c`. All previous tracked changes were already committed at `2931bba`; historical local notes deliberately excluded.
- New independent theme: `integrations/wordpress/themes/water-tours-river`. Codex authored templates, CSS and menu JavaScript; original and editorial themes preserved.
- Sites building workflow used in portable mode for the existing WordPress project. No Sites project registered and no hosting migration: owner assigned production upload to Opus on the existing host.
- Full-width real photo, navy overlay, sand ticket CTA, lime private-boat CTA, two booking cards, explanatory sections and FAQ. Two visually inspected optimized owner-photo copies reused; originals unchanged.
- Same canonical shortcodes render live forms; theme adds no checkout API, price constants or payment state handling.
- Local preview: `target/river-preview/router.php` with PHP stubs, served on localhost:8767. It renders the actual theme, but booking buttons deliberately show a preview notice and cannot charge/send mail.
- Local checks PASS: PHP lint (4 files), JS syntax, browser widths 390/768/1024/1440, image loading, no horizontal overflow, mobile navigation, FAQ; no browser JS errors. Screenshots in `target/river-preview/`. Live checkout styling is reserved for deployment acceptance; local stubs do not prove that integration.
- Deployment of this theme is DONE, 2026-09-13. See the section below.

## River theme deployed to production — 2026-09-13 (verified by Opus)
- Recovery doc for this deployment: local `target/river-deploy-20260913/RESULT.md` (gitignored; screenshots and raw measurements beside it).
- Pushed to `origin/work/tasks-6-7-9-11`: `2931bba..4516a8d` (checkpoint + River theme), then `4516a8d..b7a4243` (dialog CSS fix). Remote head `b7a4243`. No merge to `main`.
- Deployed only `integrations/wordpress/themes/water-tours-river` (11 files, cut with `git archive` from the design commit). Installed `www-data:www-data`, dirs 755, files 644. Server file hashes equal the committed blobs and equal the HTTP-served bodies.
- Server PHP 7.4.3: `php -l` clean on all 4 theme PHP files before activation. `wp theme activate water-tours-river` succeeded; `stylesheet`/`template` both `water-tours-river`.
- Backup before deploy: `/root/backups/themes-before-river-20260913.tar.gz`, sha256 `f208ae83…1a5a80`, whole `wp-content/themes` tree. Recorded prior active theme and rollback target: `water-tours-prototype`.
- Live checks (headless Chrome, 1440 and 390, against https://water-tours.ru): homepage 200; theme slug confirmed; CSS/JS/both photos/favicon 200 with matching hashes; no horizontal overflow; no browser JS errors; no failed requests. Both real plugin dialogs (`#wt-modal`, `#wt-boat-modal`) rendered, opened, closed and reopened cleanly at both widths, with live prices — not preview stubs. No form submitted, no order, no payment, no email.
- White-on-white risk from the dark boat card checked explicitly and CLEARED: every dialog and resume-bar text measured 14.46–18.1 contrast. Lowest on the page is 4.48 (plugin's own `#777` close "×" and `.wt-muted`) — pre-existing plugin styling, not theme-introduced, left unchanged.
- One theme-specific CSS integration defect was found live and fixed minimally in `b7a4243`: the theme's global `h2` and `p{margin:0}` leaked into the plugin dialog, giving 52px/38px modal titles and a description paragraph colliding with the Email label. Fix is two rules scoped to `.river-theme .wt-modal-content`; the purchase plugin was not touched. Re-verified after redeploy.
- Payment code untouched: live `water-tours-buy` php/css/js hashes matched the repo before deploy and were not modified. Fixes `485337f` and `72eb9d4` intact. Nothing outside the theme directory was written on the server.
- NOT established by this stage: end-to-end checkout acceptance on the River theme. No paid test, test order, email send, backend restart or migration was performed. That remains a separate stage.

## Payment support deployed — 2026-09-14 (verified by Opus)

Recovery doc: local `target/order-support-20260914/RESULT.md` (gitignored). Commit, push and
deployment are separate facts; none of them is acceptance of the customer screens.

- Commit `8891fca` pushed to `origin/work/tasks-6-7-9-11` (`3fc372c..8891fca`, fast-forward).
  No merge to `main`. Local notes `PROBLEMS.md`/`STATUS.md` stayed untracked.
- **Checkout (shared by tickets and boat):** «Проверить оплату» now has one controller-level
  15 s cooldown with the remaining seconds in its label; it is disabled with immediate progress
  while a request is out or a poll is scheduled, and closing the modal does not reset the
  deadline. `prefers-reduced-motion` respected.
- **Customer e-mail states are explicit and honest:** letter accepted by SMTP / letter still
  going out (bounded automatic check, 8 × 15 s) / send unconfirmed. The PDF is offered first in
  all three, and no state suggests the payment failed. "Sent" always means SMTP acceptance, never
  inbox delivery. Server resend cooldown and cap untouched.
- **New staff page `/staff/order-support`** (nav + dashboard, separate from refunds): exact search
  by order UUID, e-mail or normalised phone, nothing on partial input, everything escaped, no
  ticket codes or access tokens rendered, and an explicit note that no customer name is stored.
- **Address correction** for a mistyped e-mail re-sends the ALREADY ISSUED PDF: PAID + issued +
  not refund-pending/refunded + no USED/REVOKED/EXPIRED ticket, CSRF, address entered twice,
  mandatory reason, browser confirmation with masked addresses only, blocked against concurrent
  sends by the existing delivery claim plus a per-order cooldown/cap
  (`staff.email-correction.cooldown` 60 s, `.max-per-order` 3). No ticket or QR is issued and no
  payment state is touched.
- **Audit trail:** new table `order_email_corrections` (order id, both addresses, staff principal,
  reason, timestamp, outcome, failure type), written before the send so a crash still leaves
  evidence; addresses never reach the log. A failed send keeps the corrected address with no
  delivery stamp, so the right destination stays retryable.
- **Repository:** exact normalised phone lookup. Stored numbers keep the shape the customer typed
  — normalisation happens on read, no customer record was rewritten. Orders with no phone are
  excluded from the match.
- **Migration** `scripts/migrations/20260914_add_order_email_corrections.sql` applied inside a
  transaction with `ON_ERROR_STOP` BEFORE the new app started: one additive table plus an index,
  10 columns, 0 rows. Hibernate then issued no DDL against it, so the migration matches the
  entity. Rollback: `DROP TABLE IF EXISTS order_email_corrections;` (only before any correction is
  recorded); application rollback tree `/opt/water-tours-prev-20260914-005839`.
- **Backups before the deploy:** `/root/backups/pre-deploy/appdb-20260914-005839.sql` (sha256
  `9f8206b0…8bdfe1`), the backend tree above, and `water-tours-buy-before-20260914-005839`.
- **Deploy:** backend cut with `git archive` from `8891fca` (LF), verified against the server tree
  first — no server-local backend edits existed — and all 195 files byte-match the commit after
  extraction. Only `tickets_app` was rebuilt/recreated; Postgres and Redis kept running; health
  200 UP with no startup errors. Canonical plugin `water-tours-buy` installed (3 files, PHP 7.4
  lint clean, `www-data` 644/755); installed hashes equal the committed blobs and the HTTP-served
  bodies.
- **Live checks:** homepage and prices 200; served checkout JS is the committed file; status API
  still token-protected (400 without, 403 with a wrong token) and still carries `ticketsEmailedAt`
  and `emailResendAvailable`; staff pages 302 to `/login` for anonymous and the correction POST is
  refused; logged in, the support page renders, refuses a partial phone and finds an existing real
  order by all four spellings of its number without leaking a token. Held backlog still 13 with
  zero attempts, `order_email_corrections` empty — no e-mail was sent and no order was created.
- `./mvnw -B verify` 239/239 (was 213). The suite caught a real defect mid-work: an empty digit
  string matched every order without a phone; fixed in the query itself.
- **NOT established:** end-to-end acceptance of the three customer screens (needs a real paid
  order, out of scope) and any proof of actual delivery for a correction (no test e-mail was
  authorised). Telegram exposure of this action was deliberately left for later.
- **Pre-existing, unrelated:** the public nginx returns 404 for `/staff` while `/staff/` and
  `/staff/<page>` redirect to `/login`. The dashboard links use `/staff`. Worth a separate fix.
