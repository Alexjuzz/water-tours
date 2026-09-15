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
- **There is still no automatic backup running on the server.** `backup.sh` has never been
  installed and its three WordPress values are still placeholders.

### SEO and analytics
- `inc/seo.php` in the River theme: description (front page only — it used to be a literal tag in
  the template, so archives and 404s claimed to be the ticket page), canonical only where core
  emits none, `noindex,follow` on search/404/date/author/tag/paged, Open Graph, and JSON-LD with
  Organization, WebSite and the two services carrying the **real** backend prices. No
  LocalBusiness (no confirmed address), no rating, no FAQPage. Everything stands down if an SEO
  plugin is ever installed.
- Responsive derivatives of both photos (originals untouched): a phone now takes 66 KB instead of
  207 KB for the LCP image. Nav and footer tap targets brought above 24 px.
- **Analytics is implemented and ships disabled.** No counter id and no fallback for one anywhere
  in the JavaScript; with nothing configured the file is not even enqueued. Nothing is sent before
  consent and the vendor script is not loaded either; a refusal is permanent. A payload carries
  only the event, the product, an order id for de-duplication and an amount — the path is sent,
  never the URL, because the status and PDF URLs carry the access token. Verified 9/9 against a
  local mock sink with no counter in existence.
- `ops/seo/`: `SEARCH-CONSOLE-CHECKLIST.md`, `ANALYTICS.md`, `MEASUREMENT-PLAN.md`.

### Still not established
No live check of anything. No real payment, e-mail, Telegram message or backup restore. The order
rate limiter is off, `ddl-auto` is still `update`, analytics collects nothing, and the site is not
verified in Search Console or Yandex Webmaster. Customer support remains **disabled in
production** until the owner supplies their own private Telegram chat id.
