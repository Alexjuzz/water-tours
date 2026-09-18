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

## Customer support (question form + bot questions) — implemented 2026-09-14

A customer can ask a question from the site or in the bot; it is stored, and the owner is
notified in ONE private Telegram chat. No AI answers, no CRM, no website chat.

### Configuration — the one value that is missing
- The destination is `support.owner-telegram-chat-id` (`SUPPORT_OWNER_TELEGRAM_CHAT_ID`), the
  OWNER's **private** chat id. It must be positive; a negative id is a group or channel and is
  refused, so customers' contacts cannot be published to a group.
- It is deliberately NOT derived from `STAFF_TELEGRAM_CHAT_IDS`. That allowlist answers a
  different question (who may redeem a ticket) and is unchanged by this work: no signup, no
  become-admin, no grant-role command exists.
- **VERIFIED 2026-09-14: the production `/opt/water-tours/.env` has no `SUPPORT_*` key at all.**
  So support ships DISABLED: `GET /api/v1/support/status` returns `enabled:false`, the site never
  shows its button, `POST /api/v1/support/questions` answers 503 and stores nothing, and
  `/question` in the bot refuses. Nothing was guessed and no destination was substituted.
- To turn it on the owner adds one line to `/opt/water-tours/.env` and restarts `tickets_app`:
  `SUPPORT_OWNER_TELEGRAM_CHAT_ID=<owner's own private chat id>`. `SUPPORT_ENABLED=false` is a
  kill switch that keeps the id.

### Website
- Compact «Задать вопрос» button in the active `water-tours-river` theme, in the FAQ block. It is
  hidden until `/api/v1/support/status` says the channel is configured, so a visitor never meets a
  form whose answer could not be delivered.
- Accessible dialog: labelled fields, hints, `aria-invalid`, focus trap, Esc/backdrop close, focus
  restored on close, `role="status"` for results. It states plainly that the question and contact
  go to support via Telegram, and asks for no passwords or card data.
- `POST /api/v1/support/questions` validates length (10..2000) and the reply contact (e-mail or a
  full phone; an unparseable contact is refused rather than stored), checks a honeypot field, and
  rate limits per contact (3/h), per client address (10/h) and globally (40/h). The global cap is
  the one that actually bounds a flood, because nginx does not forward the client address today.
  **CORRECTED 2026-09-15:** that last clause was wrong. The live nginx has always sent
  `X-Forwarded-For` (it used `$proxy_add_x_forwarded_for`); what was missing was the *backend*
  half of the boundary. Since the 2026-09-15 deploy the valve is live and nginx now *replaces*
  the header with `$remote_addr`, so the per-address limiters are keyed on the real client.
  Limiter storage is bounded twice: expired windows pruned on write, and new keys refused past
  5 000 live keys.
- A repeat of the same text to the same contact within 30 minutes returns the SAME reference
  instead of creating a second inquiry, so a double click cannot flood the owner.
- The browser never sees the bot token: it talks to the backend, the backend talks to Telegram.
- «Принято» is shown only after the row is committed, and it says the question was received - never
  that anyone read it. No order is ever looked up or disclosed: `orderReference` is free text the
  customer typed and is never matched against the order table, so the form cannot be used to probe
  the customer table. No website e-mail or SMS reply was implemented; staff answer by hand.

### Telegram
- `/question`, the «❓ Задать вопрос» reply-keyboard button and `?start=question` all open the same
  flow in a private chat; `/cancel` ends it; the state expires after 15 minutes and the store is
  capped at 500 chats. Per-chat limit 3 questions/hour. Commands are published with
  `setMyCommands` so the entry point is visible.
- **Dispatch order changed and it is load-bearing.** An open question state wins over everything
  except `/cancel`: whatever is sent next is stored as text and is never dispatched. That is what
  keeps "как оформить /refund?" from becoming a refund and keeps a staff member typing a question
  from redeeming a pasted ticket code (a photo mid-question is refused, not decoded). Explicit
  commands now run BEFORE the historical "any staff message is a ticket code" catch-all, which
  still runs last and is otherwise unchanged.
- `/start` order-link payloads and `/tickets` behave exactly as before; support is never offered
  from a group.
- Owner-only `/reply <reference> <text>` relays to the chat stored on the inquiry. The recipient is
  never a parameter, so a forged or guessed argument cannot redirect an answer. The owner is told
  whether Telegram ACCEPTED the message (acceptance, not reading), and a refusal is reported as not
  sent and does not mark the inquiry answered. A website inquiry cannot be answered by the bot: it
  returns the customer's contact and says so, rather than promising a delivery that cannot happen.
- Authorisation everywhere is the chat id from the update envelope - never a username, display name
  or forwarded content.

### Storage, retries and recovery
- New table `support_inquiries` (`scripts/migrations/20260914_add_support_inquiries.sql`,
  additive: one table plus two indexes, nothing else touched). Public handle is a random
  `WT-XXXXXXXX` reference, unique by index; the uuid id is never exposed.
- Minimal personal data: the reply contact, the question, and for a Telegram inquiry the asking
  chat id. No name, no IP. Contacts and message bodies are never written to the log - only the
  reference and an error type.
- `SupportNotificationJob` delivers every 20 s and retries with a fixed backoff
  (1/3/10/30/120 min, 6 attempts total), at most 10 inquiries per pass. An inquiry leaves NEW the
  moment Telegram accepts it, so the owner is never notified twice; after the last attempt it
  becomes UNDELIVERED.
- Read-only `/staff/support-inquiries` (last 50) exists for exactly that case, so a question whose
  notification failed is not invisible. No reply box, no search, no status editing.

### Checks
- `./mvnw -B verify` 300/300 (was 239). New: support properties/validation/rate limiter/service/
  notification job, the public endpoint enabled and disabled, a PostgreSQL persistence test, and a
  Telegram support test covering public/staff/owner boundaries, private vs group, question/cancel/
  start-question state, malicious command text (including from staff), forged references and
  caller-supplied recipients, delivery failure, and that refunds/redemption/`/tickets` still work.
- Migration verified against a throwaway PostgreSQL 16: applied first, then the app booted with
  `ddl-auto=update` and issued NO DDL against `support_inquiries`; schema and 0 rows unchanged.
- No real Telegram message was sent in validation: the sender is mocked throughout. No e-mail, no
  payment, no order.

## Support modal always visible — fixed and deployed 2026-09-14

Recovery doc: local `target/support-modal-fix-20260914/RESULT.md` (gitignored).

- **Cause:** `assets/river.css` set `.ask-line{display:flex}` and
  `.wt-ask-modal{display:flex}` unconditionally; author CSS overrides the
  browser's default `[hidden]` styling regardless of specificity, so the FAQ
  "Задать вопрос" entry and its dialog stayed visible on every load/refresh,
  and `river.js` toggling `hidden` on close had no visual effect.
- **Fix, commit `ad3a9ba`** (pushed `c5fd1a2..ad3a9ba` to
  `origin/work/tasks-6-7-9-11`, fast-forward, no merge to `main`): two scoped
  rules, `.ask-line[hidden]{display:none!important}` and
  `.wt-ask-modal[hidden]{display:none!important}`. Nothing else in the diff —
  no JS/HTML/backend/auth/payment/recipient changes.
- **Local verification:** Playwright + system Chrome against a harness
  mirroring the real markup/CSS/JS, desktop 1440 and mobile 390, mocked
  support-status enabled/disabled/failing (with an artificial resolve delay)
  and a JS-blocked run. Initial state and both-disabled/failing states are
  `display:none` with no scroll lock or captured focus; enabled reveals only
  the entry line; the dialog opens solely on click with focus trap and scroll
  lock; Escape/X/backdrop(desktop) close it, restoring focus and scroll;
  reopening works.
- **Deploy:** backup
  `/root/backups/pre-deploy/river-theme-before-support-modal-fix-20260914-121122.tar.gz`
  sha256 `fc189fe5d30acd283ea855fe2903ea73a6330bead87888aa21f26c58ce25670a`.
  Only `assets/river.css` copied to
  `/var/www/water-tours/wp-content/themes/water-tours-river/assets/river.css`,
  `www-data:www-data` 644. No app restart, no migration.
- **Live verification:** server file hash `ba21a4976a9eff0c5097404a42301c00`
  matches the committed fix and the HTTP-served body. Headless Chrome against
  `https://water-tours.ru` (support-status mocked enabled, questions endpoint
  blocked — no question submitted, no Telegram/email sent) confirms
  `display:none` on load, entry line appears only once "enabled", dialog opens
  only on click and closes back to `display:none` on Escape, desktop and
  mobile.
- **Unchanged:** `SUPPORT_OWNER_TELEGRAM_CHAT_ID` still unset in production
  (support stays disabled); the two purchase dialogs (`#wt-modal`,
  `#wt-boat-modal`) and all checkout CSS untouched.

## Security remediation of the 2026-09-14 audit — implemented, NOT deployed

Audit report and per-finding disposition: local `target/security-audit-20260914/REPORT.md` and
`REMEDIATION-RESULT.md` (gitignored). Code only — nothing was deployed, pushed to production,
restarted or migrated on the server. `./mvnw -B verify` 327/327 (was 300).

- **Order takeover via a replayed `Idempotency-Key` (H-1) closed.** The key resolved to nothing but
  an order id, so replaying somebody's key returned their access token, e-mail and phone. A record
  now carries a fingerprint of the request and of the caller's own `Idempotency-Secret` header. A
  replay with a changed payload, or by a caller that cannot present the secret, is refused (409)
  and discloses nothing; the caller that created the order still recovers it, so a lost first
  response does not become a second payment. Both fingerprints are stored on the order row, so a
  retry after the 10-minute cache entry expires resolves to the same order instead of failing on
  the unique constraint. Migration `scripts/migrations/20260914_add_order_idempotency_bindings.sql`
  (two nullable columns, additive, re-runnable — applied twice against a throwaway PostgreSQL 16).
  The storefront's `Math.random()` UUID fallback is gone; it uses `crypto.getRandomValues`.
- **Transport (M-1/M-3/L-1).** `server.forward-headers-strategy: native` runs Tomcat's
  RemoteIpValve with an explicit trusted-proxy list, so the app sees the real client address and
  knows the request is HTTPS. `Secure` cookies and HSTS follow from that rather than being forced,
  which keeps plain local development working. CSP, Referrer-Policy and `SameSite=Lax` added.
  HSTS `includeSubDomains` is deliberately OFF until every *.water-tours.ru host is known to serve
  HTTPS. The login throttle now keys per address with a far looser global backstop — one visitor
  can no longer lock the console out with ten requests.
- **Also in this change.** Access token accepted in an `X-Order-Token` header (the query parameter
  stays, so issued PDF and Telegram links keep working); `anyRequest().authenticated()` with an
  explicit public list; owner/staff role split for refunds and prices with the existing account
  holding both roles; optional per-person accounts via `STAFF_ADDITIONAL_ACCOUNTS`; a server-side
  ticket-quantity ceiling; a zero-total order refused at creation; the Telegram photo authorised
  before it is downloaded; CORS limited to the two production origins by default; `show-sql` off;
  the unverified WordPress nonce removed; DR stack password parameterised and its nginx bound to
  loopback.
- **Deliberately NOT done:** Flyway / `ddl-auto: validate` (needs a verified drill against the real
  schema — deferred with the procedure recorded), a signed one-time PDF link (would break links
  already issued), and every live check. `H-2` is defended in depth (startup assertion + nginx
  deny) but its production status was never observed.
- **Requires a production action to take effect:** the nginx `X-Forwarded-For`/log-redaction change
  and a restart. Exact steps are in `REMEDIATION-RESULT.md`. Nothing here is verified live.

## Staged launch readiness — four stages completed locally, 2026-09-15 (NOT deployed)

Assignment: `target/launch-readiness-opus.md`. Full report with per-check evidence:
local `target/LAUNCH-READINESS-RESULT.md` (gitignored). Commits `dcd62fe..28be2c1` on
`work/tasks-6-7-9-11`, **local only — nothing pushed, nothing deployed, no server contacted.**
`./mvnw -B verify` **346/346** (was 330); Stages 3 and 4 changed no Java, so it was not re-run.

### Security remediation finished
- **The idempotency transition was broken in the direction that costs money.** A record with no
  bindings — a bare order id left in Redis by the old build, or a pre-migration order row — hit
  the request-hash comparison first, and a null hash never matches, so every replay across an
  upgrade was *refused* rather than replayed redacted as documented. Legacy records are now
  recognised explicitly: same order back, no second order, no credential in the reply, and the
  caller's secret is never adopted onto the record. Confirmed end to end against a real
  pre-migration row, not just against stubs.
- The storefront says something useful when that happens: a `200` with no token shows the order
  number and asks the customer to contact support (retry key kept, so a second press resolves to
  the same order); a `409` gets a Russian message offering the two honest recoveries.
- PDF downloads moved to a header-authenticated blob fetch. The query parameter still works, so
  every link already in a customer's hands keeps opening.
- `ops/disaster-recovery/nginx.conf`: `$http_referer` was logged unredacted, the **real**
  test-pay endpoint (`/api/v1/orders/{uuid}/test-pay`) was never denied, and `/staff` was routed
  to WordPress. All three fixed and probed against a running nginx.
- Two runtime tests replace inference about the trust boundary: real Tomcat on a real port, a
  real HTTP client. A trusted peer gets `Secure` cookies, HSTS and per-address throttling; an
  untrusted one gets none of it and cannot buy fresh throttle buckets by rotating
  `X-Forwarded-For`. MockMvc never runs the valve, so it could not have shown any of this.
- The staff console, `/t/{code}` and the login page were rendered in Chrome under the real CSP:
  **0 violations**, inline handlers confirmed to still run, and the e-mail-correction form
  submitted with `confirm` forced false — `order_email_corrections` stayed empty.
- `ddl-auto=validate` drilled against a **synthetic old schema**: the previous build created it,
  the migration was applied, the current build booted clean and issued **0** DDL. The production
  drill is written up in the new `ops/security/DDL-VALIDATE-DRILL.md` — which `application.yml`
  already referenced and which did not exist.
- `php -l` clean on all 14 plugin/theme PHP files under PHP 7.4 (isolated container).

### Purchase and support acceptance — 54 checks, two real defects fixed
Driven through a harness that stubs the WordPress functions and loads the **real** theme, plugin
and assets, against the real backend with mocked SMTP (MailHog), mocked Telegram and the
`local-checkout` test payment.

- **A mail-server outage silently spent the customer's resend budget.** The cap counted attempts,
  not letters, so five refused sends during an SMTP outage permanently disabled the resend button
  on an order whose letter had never gone anywhere. The cap now counts what the mail server
  accepted; the cooldown still counts attempts.
- **The gate was told the wrong reason.** A ticket whose order was mid-refund was reported to
  staff as "истёк или ещё не начал действовать", and a refunded order's ticket as "уже был
  использован". Two dedicated exception types now carry the reason; both are subclasses of what
  was thrown before, so no existing caller changed.
- `telegram.api-base` became a property (production default unchanged). It was hardcoded, which
  made the bot path impossible to exercise without sending real messages.
- Verified: server-side pricing (a tampered client price changes nothing), a lost first response
  recovering the same order, all four payment outcomes, no modal reopening on reload, the manual
  re-check cooldown, one create request for two clicks, 72-hour validity, one-time redemption,
  refund-pending blocking entry, the STAFF/OWNER split with a real STAFF-only account, the
  e-mail correction with its audit row and cooldown, and the whole support flow including
  owner-only replies and survival of a Telegram outage.

### Backup recovery — drilled, and eight script defects fixed
- **`restore.sh` could report success on a failed restore** (`psql` without `ON_ERROR_STOP` exits
  0 after printing errors), and **the recovery stack did not contain the active theme** — it
  mounted `water-tours-prototype` only, while the restored database names `water-tours-river`.
- Also fixed: no integrity check anywhere (`SHA256SUMS` now written last and verified before
  anything starts), a password containing `=` truncated by `cut -d= -f2`, `docker cp` from Git
  Bash failing on Windows (the documented fallback machine), an unguarded retention `rm -rf`, a
  `pg_dump` too owner-specific to survive `ON_ERROR_STOP`, and a working recovery reporting
  itself unhealthy because SMTP is deliberately not in a backup.
- Drill on synthetic data, production never contacted: **5.5 min cold / 26 s warm**, 41 orders and
  34 tickets restored intact, 32 tickets still valid, catalogue served, site and `/login` 200.
  All three refusal guards tested by making them fire.
- `healthcheck.sh` now also asks whether a backup exists, how old it is and whether it passes its
  own checksums — a backup job that silently stopped looks exactly like a healthy system.
  Notifications stay **off**: the destination is an owner decision.
- ~~**There is still no automatic backup running on the server.**~~ **CORRECTED 2026-09-15 by
  direct observation on the server — this claim was wrong.** It was written without server access.
  A different, older script has been running all along: `crontab -l` shows
  `15 4 * * * /root/backup-water-tours.sh` (mode `700 root:root`), and `/root/backups/daily/` holds
  **13 dated sets**, newest `20260915-041501` from that morning, each with `appdb.sql`,
  `wordpress-db.sql` and `wp-content.tar.gz`. `backup.log` records unbroken daily runs 09-10 → 09-15.
  What *is* true is the narrower statement: **the repository's own `ops/disaster-recovery/backup.sh`
  has never been installed** and its three WordPress values are still placeholders. It is not the
  script in production, and it must not be installed alongside the running one.
- **Two real weaknesses in the job that IS running** (observed, deliberately not changed —
  never install a duplicate backup job or overwrite this one blindly):
  1. `20260909-041501` is a **failed run**: `appdb.sql` is 0 bytes, no other file, no `backup.log`
     line. `set -e` aborted after `pg_dump` failed and nothing reported it, so a silent failure
     leaves a zero-byte dump that looks like a backup in a directory listing. 11 of 12 sets are
     complete; that one is not.
  2. It writes **no checksums at all**, so nothing detects a truncated set later. (The
     `SHA256SUMS`-written-last discipline exists only in the repository's uninstalled script.)
- Health of the running job, verified 2026-09-15 without restoring anything on production: today's
  `appdb.sql` and `wordpress-db.sql` both carry their dump terminators, `wp-content.tar.gz` is a
  valid archive of 858 entries, and it **does contain the active theme** `water-tours-river`
  (14 entries). Retention is `-mtime +7`; the tree is 269 MB.

### SEO and analytics
- `inc/seo.php` in the River theme: description and Open Graph on every **indexable singular**
  view, canonical only where core emits none, `noindex,follow` on search/404/date/author/tag/paged,
  and JSON-LD with Organization, WebSite and the two services carrying the **real** backend prices.
  No LocalBusiness (no confirmed address), no rating, no FAQPage. Everything stands down if an SEO
  plugin is ever installed.
  - The description started as a literal tag in the template, so archives and 404s claimed to be
    the ticket page; moving it here and restricting it to the front page fixed that, and was right
    while the front page was the only page. Since `/contacts/` exists that restriction was itself
    the bug — an indexable page in `wp-sitemap.xml` was going out with no description and no OG at
    all. As of 2026-09-18 the front page keeps its written description and title (front-page output
    verified byte-identical, on the stand and in a unit harness), and every other page/post
    describes itself from its own excerpt — manual if the owner wrote one, WordPress's derived one
    otherwise, and **no tag at all** when there is no usable text. `og:url` comes from
    `get_permalink()` so it cannot disagree with core's canonical. Verified on real WordPress 7.1
    and **deployed 2026-09-18**: live `/contacts/` carries one `description` plus 8 OG and 4 twitter
    tags, `og:url` equals the canonical, and the homepage response is byte-identical before and
    after (48 489 B). Page 12 was given a manual `post_excerpt` in the same pass (only that field
    and `post_modified` changed). Backup and exact rollback:
    `/root/backups/pre-deploy/seo-description-20260918-175627/`; detail in
    `target/SEO-BUSINESS-NEXT-STAGE-RESULT.md` §4.
- Responsive derivatives of both photos (originals untouched): a phone now takes 66 KB instead of
  207 KB for the LCP image. Nav and footer tap targets brought above 24 px.
- **Analytics: connected live 2026-09-16 (`92bd07f`), reviewed and corrected 2026-09-17
  (`target/METRICA-REVIEW-RESULT.md`).** The owner supplied the website counter (112721875, never
  the auto Maps/Business counter) and it is live: `WATER_TOURS_ANALYTICS_COUNTER_ID` is defined in
  `wp-config.php` on the server (not in git). Still nothing before consent, still a permanent
  refusal, still no e-mail/phone/access token in a payload.
  **Corrections made on review**, superseding the two claims below they replace: `product` no
  longer defaults to `'ticket'` on events that have none (`contact_cta_click`,
  `support_inquiry_sent` now correctly carry no product); the de-dup key now includes `product`,
  so an independent ticket `form_open` and boat `form_open` no longer silently suppress each other;
  UTM values are now validated against a character pattern, not merely capped by length; referrer
  sanitization now strips the query/fragment from every referrer, not only same-origin ones; the
  manual `hit()` call no longer falls back to `undefined` on an empty referrer (which would have
  let the vendor library substitute its own unsanitized default). **`payment_confirmed`'s old 24h
  "was this paid recently" heuristic is removed** - it was never a real provenance check, and the
  line below claiming it stopped stale reopens miscast "recent" as "real". It is replaced by two
  load-bearing gates: `OrderStatusResponse` now exposes the existing `test_paid` DB column
  (`testPaid`, no schema change - the column already existed for staff/local test-order tooling),
  and the frontend only announces `payment_confirmed` when `testPaid === false` explicitly
  (anything else, including a missing field from an old cached page, fails closed); a
  `localStorage` ledger keyed by order id replaces the time window for cross-tab/cross-day dedup on
  the same device. **Revenue/ecommerce is still off** - this is a JS goal via `reachGoal`, not
  ecommerce/dataLayer, and no server-side outbox/exactly-once delivery exists; refunds are not
  retroactively corrected and cross-device dedup is still not possible. One identifiable live QA
  visit (real consent grant + `form_open` only, fixed non-secret QA UTM labels) confirmed the
  actual outgoing `mc.yandex.ru`/`mc.yandex.com` wire requests carry only the sanitized payload -
  see the full report for the exact timestamps needed for the owner/Codex's exclusion segment.
  Full evidence: `target/METRICA-RESULT.md` (initial connection) and
  `target/METRICA-REVIEW-RESULT.md` (this review).
- `ops/seo/`: `SEARCH-CONSOLE-CHECKLIST.md`, `ANALYTICS.md`, `MEASUREMENT-PLAN.md`.

### Still not established
No real payment, e-mail, Telegram message or backup restore. `ddl-auto` is still `update`, and the
site is not verified in Search Console or Yandex Webmaster. Analytics now collects visits and
micro-conversions (see above) but not revenue.
(Two claims this paragraph used to make are stale and corrected here rather than left standing:
customer support is **ON** - see "Customer support is ON" further down - and the order rate
limiter is **ON** too, `MAX=20/10m`, verified against the real deployed image in an isolated drill
- see the "Three authorized stages" and "Full release backup" sections.)

## Production readiness deployed — 2026-09-15 (verified by Opus)

Everything reviewed in the four launch-readiness stages is now **live on water-tours.ru**, plus the
owner's support channel. Full per-check evidence: local `target/PRODUCTION-READINESS-RESULT.md`
(gitignored); screenshots and raw results in `target/production-readiness-20260915/`.

Deployed from **`c8211ca`** on `work/tasks-6-7-9-11`. Server tree was byte-identical to `c5fd1a2`
beforehand (220/220, no local edits, nothing extra), so nothing of anyone's was overwritten.

- **The configuration gap was real and is closed (`c8211ca`).** `compose.yml` enumerates the app
  container's environment, so `TRUSTED_PROXIES`, `FORWARD_HEADERS_STRATEGY`, `ORDER_RATE_LIMIT_*`,
  `SPRING_JPA_HIBERNATE_DDL_AUTO`, `HSTS_INCLUDE_SUBDOMAINS` and `SUPPORT_ENABLED` were settable in
  `application.yml` but **unreachable from `.env`** — editing `.env` alone would have done nothing.
  All are now wired with defaults that repeat the `application.yml` defaults, so the commit changes
  no behaviour by itself. Verified with `docker compose config` and then with `printenv` inside the
  running container. `CORS_ALLOWED_ORIGINS` also reached the server for the first time.
- **Migration `20260914_add_order_idempotency_bindings.sql` applied BEFORE the new app started**,
  `ON_ERROR_STOP` + `--single-transaction`: two nullable `varchar(64)` columns, 49 orders unchanged,
  **0 rows rewritten**. Hibernate then issued **0 DDL** at startup, so the migration matches the
  entity. Rollback `ALTER TABLE orders DROP COLUMN IF EXISTS …` is valid only before the new build runs.
- **Backend**: 247/247 files byte-match the commit; `.env` untouched (same sha before and after);
  **only `tickets_app`** rebuilt/recreated, postgres and redis up 5 days throughout; healthy in 20 s.
- **WordPress**: River theme 16/16 and `water-tours-buy` 4/4 byte-match, `www-data` 644/755, `php -l`
  clean on all 15 files under the **live PHP 7.4.3** before install and on the 6 installed after.
  HTTP-served bytes equal the committed blobs. Theme and plugins still active. No file deleted.
- **nginx** (native, hand-transcribed — the Docker recovery config was *not* copied): `/staff` now
  reaches the console (**404 → 302**, while `/staffroom` stays 404); the **real** test-pay endpoint
  `/api/v1/orders/{uuid}/test-pay` is denied (**404 → 403**); query string and `Referer` are redacted
  in the access log via a new `conf.d/water-tours-log-redaction.conf`; `X-Forwarded-For` now
  **replaces** rather than appends. `nginx -t` passed before reload. Redaction proven with a
  synthetic token that appears **0 times** in the log.
- **Trust boundary confirmed live**: `JSESSIONID` comes back `Secure; HttpOnly; SameSite=Lax` and
  HSTS `max-age=31536000` (no `includeSubDomains`) — both appear only when the RemoteIpValve
  trusted the peer. CSP, `Referrer-Policy`, `X-Frame-Options`, `X-Content-Type-Options` all present.
- **Customer support is ON.** `/opt/water-tours/.env` gained exactly one line,
  `SUPPORT_OWNER_TELEGRAM_CHAT_ID=439562529` (diff shows one added line; the other 17 byte-identical;
  no credential read or changed). `/api/v1/support/status` now returns **`{"enabled":true}`** and the
  «Задать вопрос» entry is visible on the live site at 1440 and 390. The id was added **only** as
  `support.owner-telegram-chat-id`; `STAFF_TELEGRAM_CHAT_IDS` is untouched — no staff/redemption
  authorisation was granted. `.env` mode tightened `644 → 600` (not a credential change).
  **The owner does not need to `/start` the bot**: a read-only `getChat` returns `ok:true`,
  `type:"private"`. No message was sent to anyone.
- **Acceptance in a real browser against the live site, 36/38 + 2 explained**, desktop 1440 and
  mobile 390, with every mutating request aborted at the network layer: both purchase dialogs start
  closed, open, show live prices, close on Escape and reopen; the support dialog opens and closes;
  no JS errors, no failed requests, no horizontal overflow. The two non-passes were my probe
  truncating its text sample before the boat prices — re-probed, the boat `select` carries all four
  durations and the total.
- **Nothing was created or sent**: orders 49 → 49, payments 35, tickets 31, `support_inquiries` 0,
  `order_email_corrections` 0, **held backlog still 13**, and `tickets_emailed_at` was stamped on
  09-12/09-13/09-14 only — **zero on 09-15**. No payment, refund, redemption, e-mail or Telegram
  message, and no customer record was opened.

### Open items the owner has to decide

1. **Telegram is DOWN from the container, and it is infrastructure. The code fix is deployed and
   does not help.** Measured 2026-09-15 ~22:05 UTC from the application's own Docker network: twelve
   consecutive lookups of `api.telegram.org` all return the single address `149.154.166.110`, no
   rotation; twelve connection attempts by name all fail; `149.154.167.220` is reachable from that
   same network and is never offered; the container has **no** IPv6 while the host does, which is
   why the host reaches Telegram (11/12) and the app cannot (0/12). The address-failover client is
   deployed (`TELEGRAM_CONNECTION_FAILOVER=true`) and measured at 9.3 polling failures/min - and the
   old client, switched back on through the kill switch, fails identically, so the change is neither
   the cause nor the cure. **This corrects the earlier claim that DNS rotates three addresses**; in
   this environment there is one, so failover has nothing to fail over to. The fix is a working IPv6
   route for Docker, or a resolver that returns the full A-record set - an owner decision, and
   nothing was pinned or invented. Inquiries are still stored before any notification, retried 6x
   (1/3/10/30/120 min) and listed at `/staff/support-inquiries`.

2. **Boat 30-minute price is still 35 010 ₽ live** (price version 6, published by `staff` on
   2026-09-12; v1 had 3 500 ₽). Preserved deliberately. One rollback at `/staff/prices` fixes it.
3. **Order rate limiter is ON in production.** `ORDER_RATE_LIMIT_ENABLED=true` in
   `/opt/water-tours/.env` (one added line; copy of the previous file kept on the server), app
   recreated without a rebuild, `MAX=20 WINDOW=10m`. The trust chain was confirmed live first - nginx
   replaces `X-Forwarded-For` with `$remote_addr`, the valve is demonstrably applying (`Secure`
   cookie and HSTS appear only when it trusts the peer), and the docker gateway `172.28.0.1` is in
   `TRUSTED_PROXIES`. Normal use verified from two different client addresses: eight invalid order
   attempts all answered 400, a read on the order surface answered 404 rather than 429, orders
   50 -> 50. The 429 boundary was deliberately **not** triggered in production: that needs 21
   requests from one address and, if the keying were wrong, would refuse real orders for ten
   minutes - the attempt to narrow the window first was refused by the permission classifier.

4. **`ddl-auto` stays `update`** - but the drill in `ops/security/DDL-VALIDATE-DRILL.md` has now
   been run: the application started with `ddl-auto=validate` against a restored copy of the real
   schema and came up healthy with 0 DDL statements and 0 validation errors. Evidence toward the
   switch, not the switch.
5. **Analytics ships disabled** — no counter id exists, so the script is not even enqueued.
6. **Disk**: 3.5 G free of 15 G, with 2.68 GB reclaimable Docker images and 2.38 GB build cache.
   Not pruned here (pruning images could drop the rollback image).
7. Monitoring recipient and off-site backup storage remain undecided; nothing was configured.

### Rollback, all on the server
`/root/backups/pre-deploy/readiness-20260915-090507` (8 artifacts + `SHA256SUMS`, verified 8/8),
backend tree `/opt/water-tours-prev-20260915-090507`, nginx original
`/root/deploy-20260915/water-tours.ru.ORIGINAL`, `.env` copy
`/root/deploy-20260915/env-before-support`.

---

## Three authorized stages — 2026-09-15, deployed in part

Two sessions: the first had no SSH, the second was granted it. Full evidence in local
`target/PRODUCTION-THREE-STAGES-RESULT.md` (gitignored).

### SEO — DEPLOYED and verified live (21:12 UTC)

The defect was real on the live site, not theoretical. The catalogue is price version 6 with
kopecks (`ADULT 1500.02`, boat 120 min `11000.02`); the site was rendering `1 500 ₽` / `11 000 ₽`
and publishing `"price":"1500"` / `"11000"` in JSON-LD. It now renders and publishes
**`1 500,02` and `11 000,02`**, matching the catalogue exactly. The 404 went from a 41 737-byte
copy of the landing page to its own 22 451-byte page carrying `noindex, follow` in core's single
robots tag. The other five prices are whole roubles and are byte-identical.

- Live tree was **byte-identical to `2d20207`** beforehand - no server-local edits, nothing extra.
- Eight files, `www-data` 644, **8/8 hash-verified against the commit** after install; `php -l`
  **6/6 clean on the live PHP 7.4.3** before anything was installed.
- A first attempt shipped via `git archive`, which applied `core.autocrlf` and produced CRLF; all
  eight hashes failed and **nothing was installed**. Rebuilt from raw blobs and re-verified.
- **54/54 live browser checks** at 1440x1000 and 390x844 with every mutating request aborted:
  both purchase dialogs and the support dialog open and close, none opens by itself, totals read
  `1 500,02` for one adult and `4 500,06` for three, boat 30 min `35 010` and 120 min `11 000,02`,
  no JS errors, no overflow, 404 uses the inner template.
- **The sitemap 404 found during verification is FIXED and DEPLOYED (2026-09-15 22:31 UTC,
  `42f18d8`).** `wp-sitemap.xml` and every child sitemap were rendering correct XML under an HTTP
  404 status line, so a crawler discarded them and the sitemap `robots.txt` advertises was, in
  effect, not there - dated precisely by the nginx log to 07/Sep 09:07 onward, when the last
  published post was deleted. Cause: `sitemap=index`/`sitemap=posts` are query vars `WP_Query`
  never recognises, so with zero published posts to find, core's own `handle_404()` marks the
  response 404 before `render_sitemaps()` writes valid XML on top of it. Fixed on `pre_handle_404`
  - the same filter core's own deprecated `redirect_sitemapxml()` used for this - so `handle_404()`
  stands down before ever setting the 404; the two genuine 404s that filter must still allow
  (sitemaps disabled, an unknown sitemap type) are checked with core's own public accessors, not
  reimplemented. Verified on a stand rebuilt to the live condition (zero published posts) before
  deploy: index and child sitemap both 200 with correct XML; stylesheets, front page and a real
  404 unaffected; unknown sitemap type, out-of-range page and discourage-indexing all still 404.
  Verified live after deploy the same way. No container restart - a plain PHP file WordPress reads
  every request. Rollback: `/root/backups/pre-deploy/sitemap-fix-20260915-223037/seo.php.before`.
  **`singular.php` is deployed but has nothing to render yet**: page 5 *is* the front page and the only other entry is
  a draft, so the duplicate-content problem it fixes applies to the next page the owner creates.
- Prices preserved: 35 010 untouched, `price_versions` still 6 rows.
- Rollback: `/root/backups/pre-deploy/three-stages-20260915-210907`, 7 artifacts, SHA256SUMS 7/7.

### Backup job — REPAIRED, proven, and a full restore rehearsed

`/root/backup-water-tours.sh` rewritten in place (same path, same mode, **same single cron entry**)
and committed as `ops/disaster-recovery/backup-water-tours.sh`. It now stages into
`.incomplete-<stamp>` and publishes atomically, validates every artifact's end marker, writes and
verifies `SHA256SUMS`, locks with `flock`, logs failures and exits non-zero, and runs a retention
that **only deletes sets it has verified**, never the newest, never below three.

Proven in a sandbox before installation: removed exactly 3 old verified sets and stopped at the
floor; **preserved both broken sets and named them in the log**; a truncated dump gave exit 1 with
nothing published; a second concurrent run stood down with exit 0.

One real backup: **6 s**, 12 → 13 sets, every existing set preserved, manifest verifies 3/3. The
silent 2026-09-09 failure (`appdb.sql` 0 bytes) is now **reported in the log every night** instead
of sitting there looking like a backup.

**Restore rehearsal, in containers on an `--internal` network with no published ports and no route
to the internet:** PostgreSQL **126 ms**, WordPress MariaDB **213 ms**, wp-content **549 ms**, the
application up in **32 s** - **≈33 s end to end**. All 10 row counts match production, all schema
object counts match (7 tables / 97 columns / 134 constraints / 15 indexes), WordPress 12 tables and
every table count matches, files 776 entries exactly as live, and the eight files deployed that
evening verify against the commit **from inside the backup**. The application served the restored
catalogue identical to production, with **0 ERROR and 0 WARN**. Everything was torn down; the
production containers were never stopped or reconfigured.

**The gap worth knowing:** the daily set restores the data, not the machine. `wp-config.php`,
WordPress core (7.1), `/opt/water-tours/.env`, `compose.yml`, both nginx files and
`/root/.wp_db_credentials` are **not in it**. Adding the secret-bearing ones would copy secrets to
the Windows sync target, so that is the owner's decision; `compose.yml` and the nginx files could
be added safely whenever wanted.

### Windows sync — one real defect fixed

All six manifested sets under `D:/project-backups/WATER_BACKUP/daily` verify, 18/18 files. Their
`SHA256SUMS.txt` was written with CRLF, so `sha256sum -c` reported `FAILED open or read` for every
file in every set; the archives were never affected. Fixed at the writer and verified under Windows
PowerShell 5.1. Existing manifests were left as they are and are read with
`tr -d '
' < SHA256SUMS.txt | sha256sum -c -`.

### Deployed tonight, and what it measured

Both remaining backend changes went out after the owner re-authorised them.

- **Rate limiter: ON.** Verified live as above. Rollback is one line out of `.env` plus a restart.
- **Telegram failover client: deployed.** Measured immediately after deploy: 9.3 polling failures
  per minute, no better than the old client switched back on via the kill switch - at that moment
  the deployed change did not fix the outage, and this doc said so. **The owner subsequently
  confirmed Telegram is delivering messages.** That confirmation was not independently re-measured
  in this session - Telegram/DNS/IPv6/network work was explicitly excluded and paused here, no
  diagnostic changes or backend restarts were made - so what is stated is the owner's report, not a
  fresh measurement. No further Telegram action was taken this session. Rollback without a rebuild:
  `TELEGRAM_CONNECTION_FAILOVER=false`; full revert from `/root/deploy-20260915b/backend-before/`.
- **Nothing was sent.** Zero `sendMessage` lines; the one support inquiry was already `NOTIFIED`
  (delivered 15:28 UTC today, one attempt), so no delivery was pending when the app restarted.
- Data unchanged across the whole session: orders 50, payments 36, tickets 32, inquiries 1, held
  mail 13, price version 6 with 35 010 intact.

---

## Sitemap 404 fixed and deployed — 2026-09-15 22:31 UTC (`42f18d8`)

Superseding what an earlier session in this same file called a drafted, unapplied five-line fix:
it has now been written properly, tested, and deployed. `/wp-sitemap.xml` and every child sitemap
were rendering correct XML under an HTTP 404 status line - a crawler discards the body on a 404
and never reads it, so the sitemap `robots.txt` advertises was, in effect, not there. Cause: the
site has zero published posts, `sitemap=index`/`sitemap=posts` are query vars `WP_Query` has never
heard of, and core's own `handle_404()` sets 404 before `render_sitemaps()` writes valid XML on top
of it. Fixed with one filter on `pre_handle_404` - the same extension point core's own deprecated
`redirect_sitemapxml()` used for this - so `handle_404()` stands down before ever setting the 404,
checked against core's own public accessors so the two genuine 404s (sitemaps disabled, an unknown
sitemap type) still work.

Verified on a stand rebuilt to the live condition (WordPress 7.1, zero published posts) before
deploy, and live after: index and child sitemap both 200 with correct XML; stylesheets, home page
and a real 404 unaffected; unknown sitemap type, out-of-range page and discourage-indexing all
still 404. No container restart - a plain PHP file WordPress reads every request. Full evidence:
`target/SITEMAP-STATUS-FIX-RESULT.md`. Rollback:
`/root/backups/pre-deploy/sitemap-fix-20260915-223037/seo.php.before`.

## Full release backup, isolated restore and isolated limiter proof — 2026-09-15/16

No server-side application code changed in this pass beyond what is recorded above; this closes
the remaining backup/verification scope from `target/final-release-completion.md`, with its
Telegram/DNS/IPv6/network stage excluded per the owner's instruction (Telegram is reported working
and stays untouched).

### A reusable full-release backup tool, not a one-off

`ops/disaster-recovery/full-release-backup.sh`, installed at `/root/full-release-backup.sh`, run
manually at release time (not on any cron - the nightly three-file job is unchanged and untouched).
It captures everything the nightly job does not: the **whole** `/var/www/water-tours` (core +
wp-content + `wp-config.php`, not only `wp-content`), `.env`, `wp_db_credentials`, `compose.yml`,
both nginx configs, the certbot `letsencrypt` directory (cert, key, renewal config), the installed
nightly script itself, and recreation instructions for the PostgreSQL role and the MariaDB grants
(referencing the credential files, never embedding a password). Redis is deliberately **not**
snapshotted - `appendonly no`, `DBSIZE=0` at capture time, holds only short-TTL idempotency/limiter
state - and the backup carries a note saying so instead of a meaningless RDB file. Same
atomic-publish discipline as the nightly script: staged in a dot-prefixed directory, checksummed,
verified, only then renamed to its final name.

Run once for real: `/root/backups/full/full-20260915-225915/`, 48 MB, **15/15 files
SHA256-verified**, manifest records the exact release (theme/plugin `42f18d8`, backend `8d5bdfa`,
WordPress core 7.1, PHP 7.4.3, MariaDB 10.3.39, the `tickets_app` image id).

### Secure local copy

Transferred over SSH, extracted, **re-verified 15/15 against the same SHA256SUMS** at
`D:/project-backups/WATER_BACKUP/full/full-20260915-225915/`. NTFS ACL reset to exactly three
principals - SYSTEM, Administrators, the owner's own account - inheritance from the parent
directory broken first, confirmed by SID rather than by locale-dependent display name.

A separate **local source snapshot** (distinct from the server release set, which only has what is
*deployed*) sits beside it: `source-snapshot-20260915-230418/` - a `git bundle --all` of the full
local history (23 refs, verified) and a tar of the working tree excluding `.git`, `target/`,
`node_modules` and compiled `.class` files. `ops/seo/WT-BUSINESS-DISCUSSION-2027.md` - an
unapproved business-interview draft, deliberately left **untracked** and not committed - is
preserved inside this tar as a plain file copy, not as a git commit, so it survives even if this
worktree is later removed. Same restrictive ACL applied.

### Isolated restore, from the full set - not the old three-file drill

Everything on a Docker network created `--internal` (no route out at all) with no container
publishing a port, torn down completely afterward:

| Step | Measured |
|---|---|
| set integrity, checked first | 15/15 `sha256sum -c` OK |
| PostgreSQL restore | 159 ms |
| MariaDB restore | 189 ms |
| **whole WordPress tree extracted** (not just `wp-content`) | 1.9 s, 4 620 entries, `wp-config.php` and full core present |
| deployed sitemap fix verified **from inside the backup** | `inc/seo.php` hash matches the live file exactly |
| PostgreSQL: rows | **10/10 counts match production** (orders 50, held mail 13, …) |
| PostgreSQL: schema | **7 tables / 97 columns / 134 constraints / 15 indexes, all match** |
| MariaDB: tables | **6/6 spot-checked counts match production** |
| application start, `ddl-auto=validate` | **UP in 32 s, 0 DDL, 0 schema errors** |
| application reads the restored catalogue | byte-identical to production's live `/api/v1/prices` |
| ERROR / WARN in startup | 0 / 0 |

### Order limiter's 429 boundary - proven in isolation, not on production

Using the **real deployed image** against the restored databases on the same isolated network,
with `ORDER_RATE_LIMIT_MAX=3` (this instance only - production stayed at its real `20/10m`
throughout): three probes from one forwarded address answered `400` (ordinary validation, not
throttled), the fourth answered **`429`**, and a probe from a *different* forwarded address
immediately after answered `400` - not `429` - proving the bucket is keyed per customer, not per
proxy. Orders before and after: **50 = 50**. No live production experiment was run; production's
limiter was never touched and stayed at `MAX=20`.

### Nothing further deployed

No backend or WordPress code changed in this pass. The only new thing installed on the server is
the backup tool itself (`/root/full-release-backup.sh`), which is infrastructure, not the
application.

Full evidence: `target/FINAL-RELEASE-RESULT.md`.

---

## Contacts and boarding pier — added and deployed, 2026-09-16 (`ef6653e`)

The owner's first proposal concatenated three phrases into one address
("Кронверкская набережная", "Александровский парк, 8", "причал номер 1"). Checked against public
pier directories before publishing anything: they name at least two different piers, and
"Александровский парк, 8" matches no source found — the nearby park-address pier is registered
at "Александровский парк, д. 1" (number 1, not 8), a **different** pier from "Причал №1" (which
is registered at Кронверкская набережная, near the Ioannovsky bridge). Told this, **the owner
resolved it directly: use причал №1** — the pier that public sources actually register. Published:
"Санкт-Петербург. Посадка: Причал №1, Кронверкская набережная (у Иоанновского моста)." No
LocalBusiness/office address was added anywhere — a pier is a boarding point, not this company's
registered office, matching `inc/seo.php`'s own existing reasoning.

Telegram contact: the bot's real public `@water_tours_bot` username was read from server config
(never the token) and confirmed live on t.me without sending a message. The footer link uses
`?start=question` — a real, already-handled deep-link payload (`TelegramUpdateHandler`,
`QUESTION_START_PAYLOAD`) that opens the same support conversation the on-page "Задать вопрос"
form already leads to.

New `inc/contacts.php`, three small `apply_filters`-wrapped functions matching the theme's own
established pattern (`wt_river_seo_title()` and neighbours); the Telegram username is additionally
overridable by a `WATER_TOURS_TELEGRAM_BOT_USERNAME` constant. One line changed in the footer of
`home-river.php`; everything above it byte-identical. Verified 24/24 on a stand rebuilt to
production's exact tree, then 14/14 live (boarding text, Telegram href, ≥44px tap target, no
horizontal overflow at 1440/390, checkout dialog still opens, price still `1 500,02`). No backend
restart — pure theme files. Rollback: `/root/backups/pre-deploy/contacts-fix-20260916-170929/`.

**Superseding the line above**: a dedicated `/contacts/` page was added the same day, once the
owner clarified the actual need — a single URL to submit to Yandex's regional-affiliation form,
which an in-page anchor cannot satisfy. See the next section.

Still open: the registered legal-entity address for Яндекс.Вебмастер / an organisation card
(backlog item 7, explicitly distinct from the boarding pier); the night-boarding pier (backlog
item 9, day pier now answered).

## `/contacts/` page — added for Yandex regional-affiliation submission, 2026-09-16 (`9c88b00`)

**Live URL: `https://water-tours.ru/contacts/`** — the exact link to paste into Yandex. Nothing
was submitted to Yandex in this pass; only the page was created and verified.

A real WordPress page (`wp_insert_post`, `post_type=page`, ID 12, slug `contacts`), not a
hardcoded route — created the same way any WordPress page is, so `singular.php` (already in place
from the sitemap fix) renders it with its own title, and core supplies `rel=canonical` and the
`wp_robots` tag exactly as it does for every other page. Nothing new was needed for indexability.

Content is the same three verified facts already in the footer (`ef6653e`), pulled through a new
`[water_tours_contacts]` shortcode reading the same `wt_river_contact_*()` functions — one source
of truth, so a filter or the `WATER_TOURS_TELEGRAM_BOT_USERNAME` constant updates the footer and
this page together: **Санкт-Петербург**, **Причал №1, Кронверкская набережная (у Иоанновского
моста)** stated as a boarding point, and a **"Написать в Telegram"** link to
`https://t.me/water_tours_bot?start=question`. The footer gained one link to it, next to the
existing "Правила билета".

Verified before deploy on a stand rebuilt from this tree, then live: **200**, canonical exactly
`https://water-tours.ru/contacts/`, no `noindex`, listed in `wp-sitemap-posts-page-1.xml`, correct
content at 1440 and 390, and the front page's ticket checkout still opens with the price
unchanged (`1 500,02`). `orders` count unchanged (50) throughout — this was a WordPress content
change only, no backend touched, no container restarted.

Rollback: theme files at
`/root/backups/pre-deploy/contacts-page-code-20260916-172210/*.before`; the database (containing
the new page row) backed up whole beforehand at
`/root/backups/pre-deploy/contacts-page-20260916-171907/wordpress-db.sql.before`. Deleting the
page (`wp post delete 12`) is the simplest single-page rollback if ever needed; the DB backup
covers a full revert.

Full evidence: `target/CONTACTS-RESULT.md`.
