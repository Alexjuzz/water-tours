# Water Tours River

Codex-authored WordPress design, September 2026. Independent from Prototype and Editorial.

Entry: `home-river.php`, styles: `assets/river.css`, responsive navigation: `assets/river.js`.
Requires the existing canonical `water-tours-buy` plugin for live forms. Both shortcodes remain unchanged; payment state, emails and prices are owned by that plugin/backend.

Photos are optimized copies of owner-supplied assets, reused from the previous theme. Keep their names and dimensions when replacing. No original files were modified.

Deployment: lint with the server PHP version; back up active theme and record its slug; upload only this directory, preserving all other themes/plugins; activate `water-tours-river` with WordPress; verify HTTP response, assets and both form dialogs on desktop/mobile without submitting orders. Rollback by reactivating the recorded previous slug. No backend restart or database migration.

Recovery context: `docs/AI-CURRENT-STATE.md` at repository root.
