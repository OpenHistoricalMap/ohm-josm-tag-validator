# v0.3.3 — fix JOSM crash on tag values containing `{` or `}`

Hotfix release for [#26](https://github.com/OpenHistoricalMap/ohm-josm-tag-validator/issues/26).
No new rules, no changed rules; bug fix and regression coverage only.

## What was crashing

JOSM crashed (with `IllegalArgumentException: can't parse argument number: z`) whenever the validator examined a feature whose tag value contained literal `{` or `}` — most commonly tile-template URLs like `https://mapwarper.net/maps/tile/{tileset}/{z}/{x}/{y}.png`. The crash bubbled up to JOSM's bug-report dialog and aborted the validation run.

## Why it happened

The plugin was using JOSM's `TestError.Builder.message(String, String, Object...)` API incorrectly. The 2nd argument is supposed to be a `marktr(...)`-style format template with `{0}` placeholders, with the substitution values passed as the variadic 3rd-onward args; JOSM then runs `MessageFormat` once with the args inserted post-parse, so braces in values are safe. The plugin was instead calling `tr(format, args)` itself and passing the *already-substituted* string to `.message(...)` as the description format. JOSM then ran `MessageFormat` on that pre-substituted string, and any literal `{` introduced by an interpolated tag value (most visibly `{z}` in tile URLs) crashed the `MessageFormat` constructor.

## What changed

- **All 64 `.message(...)` call sites** in `DateTagTest` and `TagConsistencyTest` rewritten from `.message(title, tr(format, args))` to `.message(title, marktr(format), args)` — the JOSM-correct idiom.
- **Build-time guard** (`test/MessageApiAuditor.java`, run by `ant test`) scans both validator source files and fails the build if the broken pattern is reintroduced.
- **Regression fixture** at `test/crasher_braces.osm` carries three primitives whose tag values exercise the previously-crashing description paths (bare `source:url` with `{z}/{x}/{y}`, non-URL `source` value with literal braces, two differing brace-bearing URLs).
- **Golden-file diff.** `ant test` now redirects RunTests' findings to `test/results.txt` and diffs against the committed `test/expected.txt`. Any drift in finding count, ordering, or text fails the build with a unified diff.

## Also fixed: source-family autofixes no longer silently overwrite existing keys

Companion bug class surfaced during the audit. Three rules wrote into source-family keys without checking whether the destination slot already held content. On features that had both the source being fixed AND a real value at the destination key, the autofix silently lost the destination value.

The fix splits each affected rule into two paths: autofix when destination slots are clear, unfixable warning when any destination slot is occupied. New codes 4321 / 4322 / 4323 carry the unfixable variants:

- **4306 (`move non-URL source tags to source:name`)** now only autofixes when the companion `source:name` (or `source:N:name`) is empty. The new **4321** fires the unfixable variant when it isn't.
- **4315 (`source contains multiple URLs; autofix by enumerating source:# keys`)** now only autofixes when none of the target `source:1`, `source:2`, … slots is occupied. The new **4322** fires the unfixable variant when any is.
- **4316 (`source contains multiple text strings; autofix by enumerating source:#:name keys`)** now only autofixes when none of the target `source:name`, `source:1:name`, … slots is occupied. The new **4323** fires the unfixable variant when any is.
- **4233 (`Invalid date - Julian date`)** now only autofixes when the target `*_date:note` is empty. The autofix writes a synthesised calendar-conversion note into `:note`, and `:note` is OHM's slot for human-meaningful annotation, so an existing value is exactly the kind of content that must not be silently overwritten. The new **4240** fires the unfixable variant when `:note` is already populated.

The unfixable variants name the occupied slot and its current value so the editor can decide whether to merge, replace, or shift the split to higher indices.

## Side benefit: apostrophes are now rendered correctly

The old double-`MessageFormat` path was silently stripping apostrophes from message text — both from format-string literals (e.g. `'https://'` came out as `https://`) and from tag values that contained apostrophes (e.g. `d'ouvrage` → `douvrage`). The single-pass path renders these correctly. ~20 finding descriptions across the regression dataset now read more naturally; no wording was deliberately changed.

---

# v0.3.2 — wikidata rule detune, historic-tag and historic-in-name warnings, year-boundary swap, icon polish

## New: `[ohm] Name warning - "historic" in name` (4320, WARNING)

Companion rule to 4319 (suspicious `historic=*` tag): any name-family value containing the word `historic` (at a word boundary, so `historic`, `Historic`, `historical`, `Historical` all trigger; `prehistoric` does not) trips the rule. OHM's time-aware data model wants the entity's name as it would have been used at the time it existed, not a present-day historicizing label.

## Year-boundary fixability swap (4212/4213/4214)

The fix-vs-no-fix policy on year-boundary dates is reversed:

- `start_date=YYYY-01-01` (4212) and `end_date=YYYY-12-31` (4213): now **autofixable** by trimming to bare-year. These sit at the year boundary that matches the role — under OHM's conservative year-only convention, the trimmed form bounds the same calendar year the explicit form does, so trimming is safe.
- `start_date=YYYY-12-31` (4214) and `end_date=YYYY-01-01` (4214): now **unfixable, please review**. These sit at the *opposite* year boundary for the role. Could be a typo (year-±1 intended) or a legitimate event-day start/end; ambiguous, so manual review only.

This reverses the previous policy (where 4212/4213 were unfixable and 4214 autofixed by year-shifting). The earlier non-fix on 4212/4213 was a 2026-04 forum-feedback response to over-aggressive auto-removal; the v0.3.2 reasoning is that *these specific* trims (boundary-matches-role) are safe in OHM's data model, while the year-shift on 4214 was the actually-aggressive operation.

## Less noise from the missing-`wikidata` rule (4302)

`[ohm] Missing tag - wikidata` previously fired on every named feature without a `wikidata` tag, which over-fired on routine named buildings, local roads, and the like. It now fires only when the feature also carries a notability signal:

- Any `wikipedia=*`, `historic=*`, or `boundary=administrative`
- A notable value of `place` (city, town, village, hamlet, suburb, neighbourhood, county, state, country, region, island, archipelago, continent)
- A notable value of `tourism` (museum, attraction, monument, artwork, gallery)
- A notable value of `amenity` (place_of_worship, university, courthouse, townhall, library, theatre, hospital, school)
- A notable value of `building` (castle, cathedral, church, chapel, mosque, synagogue, temple, palace)
- A notable value of `military` (castle, fort, barracks)
- Or the primitive is a relation (relations almost always represent compound named entities)

In the bundled regression dataset, the rule's hit count drops from 268 to 73 — a 73% reduction.

## Wikidata QID autofix when `wikipedia=*` is set

If `wikipedia=*` is present but `wikidata` is missing, a fix is now offered that resolves the QID via the Wikidata API (`wbgetentities` against `<lang>wiki` site title) at fix-click time. The lookup is lazy — validation stays offline-fast. If the lookup fails (network error, missing article, no QID in response), the fix is a silent no-op.

## New: `[ohm] Suspicious tag - historic` (4319, WARNING)

OHM convention is that `historic=*` applies only to entities that have actually passed into history; using it on a still-current feature is premature. The rule fires once per feature carrying `historic=*` (any value), prompting the editor to confirm. Unfixable.

## Icon polish

The plugin icon's black background is now transparent. Same shield/checkmark glyph; cleaner blend with JOSM's plugin list.

## Shorter validator titles

Validator messages whose titles ended with content trailing "please review" (e.g. "; unfixable, please review and add a Wikidata QID", "; fixable, please review suggestion") are trimmed to end at "please review". The trailing detail still lives in the description field, so no information is lost — but the validator panel's row no longer wraps so eagerly, making the error count column easier to read.

---

# v0.3.1 — JOSM plugin registry submission prep

Pre-submission housekeeping for getting the plugin into the JOSM
plugin directory (so it appears in every JOSM user's *Available
plugins* list by default). No validator behavior changes.

- **Renamed** the plugin from `ohm-tags` to `OHM_Tag_Validator`. JOSM
  uses the jar/SVN-directory name as the user-facing string in its
  plugin list, and underscores render as visual spaces, so the new
  name reads almost like "OHM Tag Validator" in the UI. The Java
  package (`org.openstreetmap.josm.plugins.ohmtags`) is unchanged —
  only the build artifact and user-facing name have moved. The jar
  is now `dist/OHM_Tag_Validator.jar`.
- **Plugin-Minimum-Java-Version: 17** added to the manifest. The
  plugin already required JDK 17 because of the bundled `edtf-java`
  dependency; this just declares that requirement explicitly so JOSM
  doesn't try to load the plugin on older Java versions.
- **Plugin-Icon** added. The icon ships as `images/preferences/OHM_Tag_Validator.png`
  inside the jar and is what JOSM displays next to the plugin name
  in the plugin list.

---

# v0.3.0 — chronology rules, severity elevations, role=label warning

This release adds structural validation for OHM `type=chronology`
relations, elevates several malformed-date warnings to errors, and
adds a discoverability warning for relations carrying a `role=label`
member.

## New: chronology consistency rules (4234–4239)

`DateTagTest` now validates `type=chronology` relations as structural
units, not just their members in isolation. All comparisons use strict
`start_date` / `end_date` values in `YYYY`, `YYYY-MM`, or `YYYY-MM-DD`
form (no EDTF, no `:raw`, no Julian). Touching boundaries at matching
precision (`A.end_date=1850` next to `B.start_date=1850`) are treated
as adjacency — the canonical OHM successor pattern — and never fire
overlap or gap.

- **4234 ERROR** — member date range outside parent chronology range
- **4235 WARNING** — member date range overlap (non-adjacent)
- **4236 WARNING** — gap between member date ranges (>1 unit at the
  coarser shared precision)
- **4237 WARNING** — member missing required date tag (the youngest
  member may legitimately omit `end_date`)
- **4238 ERROR** — member duplicate to its predecessor (identical in
  both non-date tags **and** geometry — recursive coordinate-based
  check, so different node ids at the same position still count as a
  duplicate; incomplete proxies treated as non-duplicate to avoid
  false positives)
- **4239 WARNING** — member without dates (neither tag present;
  takes precedence over 4237 on incomplete proxies to avoid double-
  reporting)

Findings select only the offending member(s); 4234 additionally
selects the parent relation since it represents a parent-↔-member
relationship.

## Severity elevations

The following codes are now `ERROR` (red) rather than `WARNING`:

- All `[ohm] Invalid date - …` titles previously at WARNING:
  **4202**, **4207**, **4221**, **4228**, **4231**, **4233**
- **4302** — basic missing `wikidata` tag on a named feature (the
  source-key-referenced variant **4309** stays at WARNING)

Plus the new chronology codes **4234** and **4238**.

## New: role=label warning (4318)

`TagConsistencyTest` flags any relation containing a `role=label`
member. OHM renderers generate label points server-side, so editor-
supplied labels are usually unnecessary; the warning directs the
editor to download all parent relations of the shared label object
(*File ▸ Download parent relations / ways*) before making changes.

## See also

`docs/MESSAGES.md` carries the full per-rule reference, including
triggers and examples for each new code.

---

# v0.2.1 — first public release

First public cut of **ohm-tags**, a JOSM validator plugin for
[OpenHistoricalMap](https://www.openhistoricalmap.org/). The plugin
checks OHM-style date tags and source/name consistency, surfacing
errors and warnings in JOSM's Validation Results panel with autofixes
where possible.

## What's in the box

**Date validation (`DateTagTest`, codes 4200–4233)** — checks
`start_date`, `end_date`, and their `:edtf` and `:raw` siblings:

- Normalizes noisy human-entered values (`2003-03..2016`,
  `fall of 1814`, `400 BC`, `1800s`, `j:1582-10-15`) into canonical
  EDTF (ISO 8601-2)
- Flags ambiguous, suspicious, or invalid dates (Feb 30, calendar-
  invalid combinations, far-future values, year-boundary artifacts,
  inverted start/end ranges, trailing-hyphen typos)
- Detects EDTF accidentally placed in a base tag and offers to split
  it across `*_date` / `*_date:edtf` / `*_date:raw`
- Reconciles base ↔ `:edtf` mismatches and recovers the canonical
  triple from a `tagcleanupbot`-authored `:raw` value
- Converts Julian (`j:YYYY-MM-DD`) and Julian Day Number
  (`jd:NNNNNNN`) input to Gregorian, preserving the original in
  `*_date:note`

**Tag consistency (`TagConsistencyTest`, codes 4300–4317)** — checks
names, source attribution, and external identifiers:

- Warns on named features missing a plain `name` or `wikidata` tag
- Cleans up `source` / `source:url` / `source:name` / `source:N`
  tags: misformatted URLs, semicolon-separated lists, conflicting
  duplicates, swapped name/URL pairs
- Cross-checks `wikipedia` / `wikidata` references in attribute-
  source keys

See [`docs/MESSAGES.md`](docs/MESSAGES.md) for the full per-rule
reference, including before → after examples for every message.

## Notable choices in this release

- **Hybrid EDTF parsing.** Uses [`OpenHistoricalMap/edtf-java`
  0.2.0](https://github.com/OpenHistoricalMap/edtf-java) (Maven
  Central, BSD 2-Clause) as the source of truth for EDTF validity.
  The library is shaded into the plugin JAR — JOSM users don't need
  to install anything extra. OHM-specific input normalization (Julian
  conversion, BCE prose, decade/century shorthand, season expressions)
  stays in-tree as a thin shim that emits canonical EDTF for the
  library to consume.

- **Severity scheme.** Most messages are `WARNING`; malformed-date
  messages (4201, 4208, 4217, 4218, 4222) are `ERROR` so they show
  red in JOSM's validator panel.

- **Conservative autofixes.** No autofix is applied silently in
  cases where the input could plausibly be correct. For example,
  `start_date=YYYY-01-01` is now a no-fix warning (Jan 1 is a real
  date for many events); the off-by-one variant (`start=Dec 31` /
  `end=Jan 1`) does keep its autofix because it's a clearer typo
  signal.

## Installation

Download `ohm-tags.jar` from this release and copy it into JOSM's
plugins directory:

- macOS: `~/Library/JOSM/plugins/`
- Linux: `~/.local/share/JOSM/plugins/`
- Windows: `%APPDATA%\JOSM\plugins\`

Restart JOSM, then enable **ohm-tags** under
*Preferences → Plugins*. JOSM 19000+ and Java 17+ required.

## Forum feedback addressed in this release

Thanks to **@danvk** and **@Minh_Nguyễn** for the extensive feedback
on the [first-cut announcement
thread](https://community.openhistoricalmap.org/t/133):

- Severity escalation for malformed dates (#5)
- Toned-down year-boundary autofixes for Jan 1 / Dec 31 (#6)
- Retired the negative-year-ambiguity rule (#7)
- Removed dismissive language from validator messages (#3)
- Added wrong → fixed examples to every rule in the docs (#8)
- Migrated to the off-the-shelf `edtf-java` parser instead of
  in-tree regex (#1)
