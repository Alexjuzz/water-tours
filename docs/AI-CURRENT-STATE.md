# Water Tours — recovery checkpoint, 2026-09-13

Read this first when resuming. This is a sanitized code handoff; credentials and machine-specific operational notes remain in the owner's local hub, not Git.

## Current source and deployment
- Working branch: `work/tasks-6-7-9-11`. Canonical WordPress code: `integrations/wordpress/`. Do not use the separate legacy WordPress repository.
- Latest implementation before the new design: `2931bba`. Earlier: `6577ac9` staff test-ticket mail buttons; `72eb9d4` checkout UX; `485337f` truthful pending-payment screen.
- Worker reported those changes pushed to the same remote branch and deployed. No merge to main is claimed.
- Active live theme: `water-tours-prototype` (last worker verification). `water-tours-editorial` installed inactive. Owner rejected editorial design; retain it as history, do not activate it.
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
- NEXT: Opus deploy only this theme from the design commit, activate `water-tours-river`, verify homepage/assets and open both actual purchase forms without submitting, then report deployment evidence. Preserve old themes as rollback. No new paid tests or email sends in this design task.
