# Changelog

All notable changes made in this fork are documented here.

This fork revives the abandoned upstream project (last upstream release:
`1.0-alpha09`, built against Pokémon data from ~2020) and brings it up to
date with the modern Pokémon Showdown server, Generation 9 data, and a number
of UI and reliability improvements.

## [Unreleased] – New Android Versions and Moves tracking

### Added — Battle info
- **Revealed move tracking**: the opponent's moves are now
  remembered as they are used and listed in the Pokémon tip popup (tap-and-hold
  the on-field Pokémon). The list persists when a Pokémon switches out and back
  in, so re-opening the popup for an already-seen Pokémon still shows everything
  it has revealed. Moves called indirectly (e.g. via another move) and Struggle
  are excluded, matching the official client. Shown for spectated/replay battles
  on both sides too.
- **Tappable team icons**: the small team icons in the player info bars are now
  tap-and-holdable for any Pokémon that has already appeared on the field,
  opening the same tip popup (species, types, possible abilities, speed and the
  revealed moves) — so you can review a benched opponent without waiting for it
  to come back out. Not-yet-revealed (preview-only) icons stay inert.
- **Larger team icons**: the team icons in the player info bars were enlarged
  (16sp → 24sp) to make better use of the previously wasted space in the bar and
  to give the new tap targets more room.

### Fixed — Stability
- **Crash on entering battles** (`ArrayIndexOutOfBoundsException: length=0;
  index=0` in `PlayerInfoView.appendPokemon`) when joining or spectating a
  battle whose team preview had already started, or when the `teamsize`
  message was missing/out of order. Placeholder team slots are now lazily
  initialised when the first preview Pokémon arrives, and all poké-ball
  `ImageSpan` look-ups are bounds-checked instead of blindly indexing `[0]`.
- **General crash safety net for battle messages**: `AbsMessageObserver` now
  wraps each message handler in a try/catch, so a single malformed or
  unexpected server message is logged (with its command/room) and skipped
  instead of bringing down the app.
- **Uncaught coroutine exceptions** in fragment scopes are now caught by a
  `CoroutineExceptionHandler` and logged via Timber, rather than crashing the
  process (matches the existing `SupervisorJob` behaviour for siblings).

### Changed — Dependencies
- Upgraded **Glide 4.8.0 → 4.16.0**. The removed `SimpleTarget` API was
  migrated to `CustomTarget` (with the now-required `onLoadCleared`), and
  `BitmapDrawable(Bitmap)` was migrated to `BitmapDrawable(Resources, Bitmap)`.

### Changed — Code maintenance (deprecation cleanup)
Resolved all outstanding Kotlin/Android deprecation warnings in the `psclient`
module. These are behaviour-preserving for the app's ASCII/English data:
- Locale-defaulting string APIs replaced with locale-independent ones:
  `toLowerCase()/toUpperCase(Locale.ROOT)` → `lowercase()/uppercase()`,
  `Char.toLowerCase()` → `lowercaseChar()`, and
  `String.capitalize()` → `replaceFirstChar { it.uppercaseChar() }`.
- `Html.fromHtml(String)` → `HtmlCompat.fromHtml(…, FROM_HTML_MODE_LEGACY)`.
- `Fragment.requireFragmentManager()` → `parentFragmentManager`.
- `BitmapRegionDecoder.newInstance(stream, false)` → `newInstance(stream)` on
  API 31+ (old overload kept and suppressed for older devices).
- `WindowInsets.stableInsetTop` → `getInsetsIgnoringVisibility(systemBars()).top`
  on API 30+ (old field kept and suppressed for older devices).
- `Bundle`/`Intent` typed accessors: `Bundle.get(key)` → `getString(key)`;
  remaining unavoidable platform deprecations (`getParcelableArrayList`,
  `getSerializable`/`getSerializableExtra`, `startActivityForResult` /
  `onActivityResult`) are explicitly `@Suppress`-ed pending a future migration
  to the type-safe accessors and the Activity Result API.
- Removed dead always-true/false null checks in `Team.pack()` while keeping the
  exact same packed-team output sent to the server.

## [1.1.0] – Gen 9 revival

### Added — Generation 9 support
- **Terastallization** (the Gen 9 battle gimmick), end-to-end:
  - Decision request parsing (`canTerastallize`) and the `move <n> terastallize`
    choice command.
  - A **Terastallize** toggle in the move-decision widget, shown as a
    type-coloured badge with the Tera type.
  - `-terastallize` battle message handling: log line, type-coloured toast, and
    the battling Pokémon's Tera type is stored (and persists when a
    terastallized Pokémon switches out and back in).
  - On-field **TERA `<TYPE>`** badge in the status row, plus a Tera-type line in
    the Pokémon tip popups (active and bench/team Pokémon).
- **Move type-effectiveness hints**:
  - Full Gen 6+ type chart (`Type.effectiveness`).
  - "Effectiveness: N× (descriptor)" line in the move tooltip.
  - Coloured multiplier badge (e.g. `2×`, `½×`, `0×`) on each move decision
    button and in the bench/team Pokémon popup, computed against the foe's
    current defending types (Tera-aware).

### Added — Battle visuals (web-client parity)
- **Multiple battle backgrounds**, chosen the same way the web client does
  (derived from the numeric battle id so both players see the same backdrop),
  loaded at runtime from `play.pokemonshowdown.com`.
- **Graphical entry hazards** drawn on the field: Spikes, Toxic Spikes,
  Stealth Rock, Sticky Web and G-Max Steelsurge, using the official `fx`
  sprites (count, layering, scale and opacity mirror the web client). Other
  side conditions (screens, Tailwind, …) keep their compact text tags.

### Fixed — Modern Pokémon Showdown protocol compatibility
The public PS server changed in several ways since the upstream app was
written; sign-in and several flows were broken. Fixed:
- **Login** assertion is now a multi-line hex RSA signature; it is sanitised
  before use (both guest and registered-account paths).
- **`userdetails`** response parsing updated (online detection via `rooms`/
  `status`, `userid` → `id` fallback).
- Hardened a large amount of JSON parsing by switching from `get*` to `opt*`
  across `GlobalMessageObserver`, `ReplayManager`, `NewsDialog` and
  `SearchReplayDialog` (players array with `p1`/`p2` fallback), preventing
  crashes on missing fields.
- **Challenges** now arrive as private messages (`/challenge` PMs) instead of
  `|updatechallenges|`; incoming/accept/cancel/clear states are routed
  correctly so the Accept/Cancel buttons no longer get stuck.
- More robust cookie handling and connection-error reporting during sign-in.

### Changed — Data updated to Generation 9
All locally bundled raw assets were regenerated from live Showdown data:
- `dex.json` (≈1517 species), `moves.json` (≈954 moves), `learnsets.json`,
  `dex_icon_indexes.json`, and the `dex_icons_sheet.png` / `item_icons_sheet.png`
  icon sheets.
- Fixed the `build_dex_icon_indexes.py` source URL (upstream path now 404s).
- See the README for how to regenerate these for future generations.

### Fixed — Sprites
- Added support for **Pokémon HOME artwork** in `GlideHelper`, used as the
  preferred source for dex sprites (correct shiny variants across all gens;
  PS serves identical bytes for `dex/` and `dex-shiny/` in Gen 9).
- Fixed sprite ids for **hyphenated species names** (e.g. the Gen 9 Treasures
  of Ruin: Wo-Chien, Chien-Pao, Ting-Lu, Chi-Yu, plus Nidoran-F/M) which were
  being split incorrectly.

### Changed — Connection / account handling
- Store the signed-in username in preferences and improved the sign-in flow;
  long-press the username in Home shows the full name.
- OkHttp now resolves IPv4 addresses first and sends an
  `Origin: https://play.pokemonshowdown.com` header to better match the
  official web client. (Note: these do not bypass server-side anti-spam
  restrictions, which are tied to account trust + IP reputation, not the
  client.)

### Tooling / project
- Bumped `versionCode`/`versionName` for this fork release.
- Added `LICENSE` (Apache-2.0) and `NOTICE` files (upstream shipped none).
- Updated the CI workflow to build on the `main` branch with JDK 17
  (required by AGP 7.4.2 / Gradle 7.6.4).
- Rewrote the README.

---

Upstream project (for reference):
<https://github.com/MajeurAndroid/Android-Unofficial-Showdown-Client>
