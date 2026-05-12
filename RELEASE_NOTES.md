# v0.7.1 — Bundled fixes from the post-v0.7.0 rule review

Several gaps from the post-v0.7.0 rule review, bundled together.

## Prefix-tilde year-month bug fix

Pre-fix: `start_date=~1900-05` (prefix tilde on year-month) produced garbage autofix `start_date=1900, start_date:edtf=1900~/0005` — the normalizer's `QUALIFIED_HYPHEN_RANGE` preprocess pattern was greedy and parsed `~1900-05` as a `~`-prefixed range from year 1900-approximate to year 5 CE.

Post-fix: same input correctly normalizes to `start_date=1900-05, start_date:edtf=1900-05~, start_date:raw=~1900-05` via the existing 4202 "EDTF in base tag" pipeline. The fix is a small guard added to `QUALIFIED_HYPHEN_RANGE` that requires the right side to be unambiguously a year (>12 or 4-digit) before treating the input as a range — mirroring the guard already on the sibling `QUALIFIED_SHORT_YEAR_RANGE`. No new code, no behavior change for legitimate ranges (`~1848-1854` and `~47-50` continue to normalize correctly).

## G1 — rule 4220 (trailing single hyphen) extended to `:edtf`

Before: `end_date:edtf=2021-` fired the generic 4228 "Invalid date - *_date:edtf; unfixable" with the catch-all "cannot be normalized" description. After: same input fires the targeted 4220 "Ambiguous date - trailing hyphen" with the three-way "typo / incomplete / open-ended range" description. Same code, same disposition (unfixable, three interpretations), just a broader trigger surface — base and any top-level `:edtf` key.

## G6 — rule 4245 (packed features) extended to more `*_date` pairs

Curated list of start/end date-key pairs eligible for the packed-features check (semicolon-merged values that should be split):

- `(start_date, end_date)` — primary lifespan (existing)
- `(birth_date, death_date)` — persons (new)
- `(opening_date, closing_date)` — businesses (new)
- `(construction_date, demolition_date)` — buildings (new)

Same autofix shape applies to all four pairs: collapse to min/max bounds, preserve originals in matching `:raw` slots, add `fixme=split into multiple features`. Auto-discovery of arbitrary `*_date` pairs was rejected — too many false positives.

## G5 — new rule 4327: name leading/trailing whitespace

`[ohm] Name warning - leading or trailing whitespace; autofix by trimming` (fixable)
`[ohm] Name warning - whitespace-only name; unfixable, please review` (unfixable, when stripping would empty the value)

Fires on any name-family value (`name`, `name:lang`, `alt_name`, …). Fixable autofix calls `String.strip()`.

## G4 — new rule 4255: backwards EDTF interval

`[ohm] Suspicious date - EDTF interval is backwards (start > end); unfixable, please review`

Fires WARNING when `start_date:edtf` or `end_date:edtf` is a closed slash interval whose lower-bound year is greater than its upper-bound year — e.g. `2000/1900`. The interval is syntactically valid EDTF but semantically inverted; downstream consumers will silently pick one bound and ignore the other. Without 4255, rule 4211 was silently deriving `end_date=1900` from `:edtf=2000/1900` (extracting the upper bound = 1900), freezing the bad state in place. 4255 fires *before* 4211 masks the problem.

Unfixable — the validator can't tell which side the user meant.

## G2 / G3 — new rules 4328 / 4329: malformed wikidata / wikipedia values

`[ohm] Malformed tag - wikidata value is not a QID; unfixable, please review` (4328)
`[ohm] Malformed tag - wikipedia value is not <lang>:<title>; unfixable, please review` (4329)

Catches values like `wikidata=notaqid` and `wikipedia=https://en.wikipedia.org/wiki/Eiffel_Tower` that pass the presence-only checks in 4302 but would silently fail downstream lookups (the 4302 autofix queries the Wikidata API using the `<lang>:<title>` split — malformed values would silent-no-op).

Both unfixable WARNING. The validator can't guess intent (typo? truncated URL? wrong tag entirely?).

## Files touched

- `src/.../DateNormalizer.java` — `QUALIFIED_HYPHEN_RANGE` preprocess gains a right-side disambiguation guard.
- `src/.../validation/DateTagTest.java` — new `CODE_EDTF_INTERVAL_BACKWARDS = 4255`, `checkBackwardsEdtfInterval` method; refactored `checkPackedFeatureSet` into iteration over `PACKED_FEATURE_PAIRS` with per-pair `checkPackedFeatureSetForPair`; extracted `emitTrailingHyphenWarning` shared between base and `:edtf` paths, hooked into `checkAllEdtfKeys`.
- `src/.../validation/TagConsistencyTest.java` — new `CODE_NAME_HAS_WHITESPACE = 4327`, `CODE_WIKIDATA_MALFORMED = 4328`, `CODE_WIKIPEDIA_MALFORMED = 4329`; new `WIKIDATA_QID` and `WIKIPEDIA_VALUE` patterns; new `checkNameWhitespace` helper; wiki-format checks inline alongside the 4302 missing-wikidata block.
- `docs/MESSAGES.md` — rewritten 4220 trigger, rewritten 4245 trigger with curated pair list, new 4255 / 4327 / 4328 / 4329 sections.
- `test/test_data.osm` — 11 new probe fixtures (9101010–9101060).
- `test/expected.txt` — corresponding golden rows.

`MessageApiAuditor` count: 93 → 100. Regression suite green.

---

# v0.7.0 — Attribute-source rule extension + empty-chronology rule

This release expands the source-rule coverage to **attribute-scoped source slots** (e.g. `start_date:source`, `name:source`, `wikidata:source`) and adds a new chronology rule (4254) for empty chronology relations. Minor version bump because the rule-coverage surface grows substantially.

## New rule 4254 — empty chronology relations

`[ohm] Chronology - relation has no members; unfixable, please review`

Fires (WARNING) when a `type=chronology` relation has zero members. Empty chronologies are typically editing accidents — the wrapper was left behind after members were removed, or never had members added. JOSM core has a generic empty-relation warning; this one adds OHM-specific framing ("add the constituent features as members, or delete this relation if it's no longer needed"). Fires before the other chronology rules so the rule output doesn't get cluttered with "no members to compare" noise.

## Source rules extended to attribute-source slots

The v0.5 source-content rules previously applied only to plain `source` / `source:N` / `source:url` / `source:name` keys. They now also fire on the attribute-scoped variants — any `<attr>:source*` key, where `<attr>` is any prefix like `start_date`, `end_date`, `name`, `wikidata`, etc. Autofix targets land in the matching `<attr>:source*` companion slots, never overwriting an existing `<attr>:source:N[:*]`.

Rules touched:

- **4307** (URL missing scheme) — now also fires on `<attr>:source`, `<attr>:source:url`, `<attr>:source:N`.
- **4312** (URL conflict between `source` and `source:url`) — now also fires for `<attr>:source` vs `<attr>:source:url`; autofix moves the URL value to `<attr>:source:N+1`.
- **4314** (1 URL + 1 text in single value) — now also fires on `<attr>:source` and splits into `<attr>:source` (text) and `<attr>:source:url` (URL).
- **4315** (multi-URL) — now also fires on `<attr>:source` with autofix enumeration into `<attr>:source:N+1`, …
- **4316** (multi-text) — now also fires on `<attr>:source`. Unfixable.
- **4317** (mixed types, 3+ items) — now also fires on `<attr>:source`. Unfixable.
- **4324** (URL in `source:name`) — now also fires on `<attr>:source:name`.
- **4325** (text in `source:url`) — now also fires on `<attr>:source:url`.

**4304 / 4305** (`source=wikipedia` / `source=wikidata` literal — "not a reasonable source for geometry") **are NOT extended** to attribute-source. The attribution-completeness rules (4308 / 4309) already cover the wikipedia/wikidata-literal case on attribute-source with the right semantic.

## 4308 / 4309 refined

`[ohm] Missing tag - wikipedia, referenced in source keys; unfixable, please review & add tag` (4308)  
`[ohm] Missing tag - wikidata, referenced in source keys; fixable, please review` (4309 new fixable)  
`[ohm] Missing tag - wikidata, referenced in source keys; unfixable, please review & add tag` (4309 existing unfixable)

The suppression model is now:

- `wikidata=*` is treated as canonical attribution for **both rules** — its QID resolves to a Wikipedia article via Wikidata sitelinks, so neither 4308 nor 4309 needs to fire.
- `wikipedia=*` is also accepted for 4308 (article exists), but for 4309 a `wikipedia=*` is a **fix path**, not a substitute.

So 4309's new behavior:

- `wikidata=*` present → silent.
- no `wikidata`, exactly one canonical `wikipedia=*` → **fire fixable**, autofix queries the Wikidata API and adds `wikidata=Q…` (same lookup mechanism as 4302's autofix).
- no `wikidata`, multiple `wikipedia*` tags (e.g. `wikipedia:en` + `wikipedia:fr`) → fire unfixable, ambiguous which article is canonical.
- neither `wikidata` nor `wikipedia` → fire unfixable.

4308's new behavior: silent if `wikipedia=*` OR `wikidata=*` present; fire unfixable otherwise.

## Files touched

- `src/.../validation/DateTagTest.java` — new `CODE_CHRONOLOGY_EMPTY = 4254`, empty-relation check at the head of `checkChronologyConsistency`.
- `src/.../validation/TagConsistencyTest.java` — `checkAttrSourceTag` refactored to apply the new 4308/4309 suppression and to delegate non-literal values to the unified source-content pipeline; `checkSourceTag` / `checkSemicolonSeparatedSource` / `emitMultiUrlSplit` / `nextSourceIndex` / `checkSourceUrlPair` / `checkSourceNameContents` parameterized by an attribute prefix; new `ANY_SOURCE_URL_KEY` / `ANY_SOURCE_NAME_KEY` patterns covering both plain and attribute-scoped variants.
- `docs/MESSAGES.md` — new 4254 section, rewritten attribute-source section covering the unified source-rule surface and the 4308/4309 suppression model.
- `test/test_data.osm` — 8 new probe fixtures (attribute-source variants + empty chronology + 4309 wikipedia-lookup paths).
- `test/expected.txt` — 9 new golden rows including one real-data hit (`w/201800179`, which had `start_date:source=URL; text` and now correctly fires 4314).

`MessageApiAuditor` count: 91 → 93 (4309 fixable variant + 4254 add two new emission sites). Regression suite green.

---

# v0.6.2 — Date-range fixes, name-date detection, release helper

A multi-feature bundle: new `DateNormalizer` patterns, an existing-rule fix, a new rule, and a small release helper script.

## New `DateNormalizer` rewrites (surface as 4202 fixable on base / 4228 fixable on `:edtf`)

**Double-bracket dotdot range.** `[[date1..date2]]` is now stripped of its `[[…]]` wrappers (with optional whitespace between the brackets and the inner) so the standard RANGE branch can normalize the inner. `[[1900..1950]]` → `1900/1950`; `[[1900-05..1950-08]]` → `1900-05/1950-08`. The inner is validated by the recursive normalizer — if either side isn't parseable, the value falls through to unfixable.

**`early` / `mid` / `late` on a year or year-month** (new — case-insensitive on the modifier, BCE supported). The modifier splits a year or month into non-overlapping thirds at the next-finer precision:

- `early YYYY` → `YYYY-01/YYYY-04`; `mid YYYY` → `YYYY-05/YYYY-08`; `late YYYY` → `YYYY-09/YYYY-12`.
- `early YYYY-MM` → `YYYY-MM-01/YYYY-MM-10`; `mid` → `…-11/…-20`; `late` → `…-21/…-{30|31}` (`30` for Apr/Jun/Sep/Nov).
- February special case: `early YYYY-02` → `…-01/…-09`; `mid` → `…-10/…-19`; `late` → `…-20/…-{28|29}`, proleptic-Gregorian leap-year aware.
- BCE: `early 100 BC` → `-0099-01/-0099-04`; `early 1 BC` → `0000-01/0000-04`.

Case-insensitive on the modifier — `Early 1900`, `MID 1850s`, `EARLY C19` all match. Same case-insensitivity was extended to the existing `early/mid/late YYYY0s` (decade) and `early/mid/late CN` (century) rules in the same pass.

**X-form decade with modifier.** `early|mid|late YYY[Y]X` is rewritten in preprocess to `early|mid|late YYY[Y]0s` so the existing `THIRD_DECADE` path handles it: `mid 197X` → `mid 1970s` → `1973~/1976~`. The X is accepted in either case.

## Existing-rule fix: decade and century thirds no longer overlap

The existing `early/mid/late YYYY0s` and `early/mid/late CN` rules previously had a one-year overlap at the boundaries:

- `early 1850s` ended at 1853 and `mid 1850s` started at 1853 (shared 1853).
- `mid C19` ended at 1870 and `late C19` started at 1870 (shared 1870).

Updated to non-overlapping `3 / 4 / 3` (decade) and `30 / 40 / 30` (century) splits. `early 1850s` → `1850~/1852~`; `mid 1850s` → `1853~/1856~`; `late 1850s` → `1857~/1859~`. `early C19` → `1800~/1829~`; `mid C19` → `1830~/1869~`; `late C19` → `1870~/1899~`.

Six pre-existing regression rows updated to reflect the corrected bounds.

## New rule: dates in name (extends 4301 to fixable + adds inline detection)

Rule **4301** is extended from a single unfixable warning to three paths:

1. **Fixable** — parens group whose content matches a strict date shape (`YYYY`, `YYYY-`, `-YYYY`, `YYYY-MM`, `YYYY-MM-DD`, `YYYY-YYYY`, `YYYY-MM-YYYY-MM`, plus `c.` / `ca.` / `circa` / `before` / `after` / qualifier prefixes). Autofix strips the parens span and collapses whitespace. Title: `[ohm] Name warning - dates in name; autofix by stripping the date range`.
2. **Fixable** — clean inline date range matching `\bYYYY-YYYY\b` or `\bYYYY-MM-YYYY-MM\b`. Single-year inline (e.g. `Building 1950`) deliberately NOT detected — too easily a building / model / route number.
3. **Unfixable** — parens with year-like content but not a clean shape (e.g. `(Springfield 1950)`). Title: existing `[ohm] Name warning - parentheses in name; unfixable, please review`.

Examples:

- `name=Wild West (1950-)` → autofix to `Wild West`.
- `name=Wild West 1942-04-1948-09` → autofix to `Wild West`.
- `name=Wild West (Springfield 1950)` → fires unfixable.

## Release helper

New `release.sh` script in the repo root. No-arg invocation, picks the latest local `v*` tag, extracts the matching `# vX.Y.Z` section from `RELEASE_NOTES.md` as the body, shows the literal `gh release create` command it will run plus a preview of the notes, prompts `[y/N]`. Replaces the long inline `gh` command the previous release flows passed around.

## Files touched

- `DateNormalizer.java`: new `YYYY_MM_DOTDOT_MONTH_TAIL_AMBIGUOUS` guard (already in v0.6.0); new step 6c (double-bracket dotdot strip); new step 8a (single-dot rewrite, already in v0.6.1); new step 8b (X-form with modifier rewrite); new `THIRD_PARTIAL_YEAR_OR_MONTH` pattern and handler; new `isLeapYear` / `monthEndDay` helpers; `(?i)` added to `THIRD_DECADE` / `THIRD_CENTURY`; lowercase the captured modifier before switch; decade/century offset tables updated to non-overlapping.
- `TagConsistencyTest.java`: new `CLEAN_DATE_SHAPE` / `INLINE_DATE_RANGE` patterns; new `checkNameForDateContent` helper and refactored name-handling call site; new `removeSpanCollapseWhitespace` helper.
- `docs/MESSAGES.md`: new bullets under the 4202 preprocess pattern list (double-bracket, early/mid/late, X-form, overlap fix); 4301 section rewritten for three paths.
- `test/test_data.osm`: 21 new fixture nodes (3 for double-bracket, 11 for early/mid/late + BCE + X-form, 6 for 4301 name-date + parens variants, 1 extra).
- `test/expected.txt`: 21 new golden rows + 8 existing rows updated for the overlap fix.
- `release.sh`: new file, executable.

`MessageApiAuditor` count: 89 → 91 (two new fixable emission sites in `checkNameForDateContent`). Regression suite green.

---

# v0.6.1 — Single-dot open-ended marker rewrite

`*_date:edtf` (and any top-level `*:edtf` key) values whose entire content is a single `.` immediately before or after a clean ISO date are now rewritten to the EDTF open-ended-interval form. Surfaces as **4228 fixable** (`[ohm] Invalid date - *_date:edtf; fixable, please review`), with the autofix writing the slash form and preserving the original in `:edtf:raw`:

- `.YYYY[-MM[-DD]]` → `/YYYY[-MM[-DD]]` ("up to and including the date")
- `YYYY[-MM[-DD]].` → `YYYY[-MM[-DD]]/` ("the date onward")

Anchored on the whole value — only fires when the dot is the sole leading/trailing character. Internal `..` (the standard OHM range form, e.g. `1900..1950`) is untouched.

## Also affects base `*_date` tags

Because the rewrite lives in `DateNormalizer.preprocess`, base `*_date` values matching the same shape also normalize. This converts two real-data nodes (`n/2091813675`, `n/2091813676`) that had `start_date=.2012-03-29` from previously unfixable to fixable. The autofix writes the full triple:

- `start_date=2012-03-29` (base derived from the open-ended bound)
- `start_date:edtf=/2012-03-29`
- `start_date:raw=.2012-03-29`

## Files touched

- `DateNormalizer.java` — new step 8a in `preprocess`.
- `docs/MESSAGES.md` — new bullet under the 4202 preprocess pattern list.
- `test/test_data.osm` — six probe nodes (`9100910`–`9100915`) covering both directions × Y/YM/YMD precisions.
- `test/expected.txt` — six new golden rows + two updated rows for the real-data nodes.

No new error codes; surfaces under existing 4202 (base) and 4228 (`:edtf`). MessageApiAuditor 89, regression suite green.

---

# v0.6.0 — New rule 4253: ambiguous `YYYY-MM..MM` tail

New WARNING **4253** `[ohm] Ambiguous date - YYYY-MM..MM tail could be month or year; unfixable, please review`. Fires on `*_date` or any top-level `*:edtf` key matching `^(\d{4})-(0[1-9]|1[0-2])\.\.(0[1-9]|1[0-2])$` — a year-month with a `..` tail that's *also* a valid month value (01-12). Two readings, both legitimate in OHM tagging:

- tail-as-month sharing the year prefix → `YYYY-MM/YYYY-tail` (e.g. `1904-05..08` → "May to August 1904")
- tail-as-2-digit-year sharing the century prefix → `YYYY-MM/{century}{tail}` (e.g. `1904-05..08` → "May 1904 to 1908"), parallel to the existing `YYYY..YY` abbreviated-tail-range rule (Path 0b').

No signal in the value alone to pick between them; rule fires unfixable with both interpretations spelled out in the description.

## Bad legacy autofix removed

Before this change, `DateNormalizer.toEdtf("1904-05..08")` returned `1904-05/0008` — the generic RANGE branch interpreted `08` as year 8 CE, padded to four digits, producing a backwards/nonsense interval. This was surfaced by 4228 as a fixable normalization, so the autofix would silently rewrite to that garbage on click. `DateNormalizer.toEdtf` now returns `Optional.empty()` for the same shape, so the bad autofix can never fire.

Scope is restricted to month-valid tails (01-12) — the indeterminate band. Tails outside that range (e.g. `1904-05..13`) are unambiguously year-only but currently produce backwards intervals; left untouched here to avoid quietly changing unrelated outputs.

## Files touched

- `DateNormalizer.java` — new `YYYY_MM_DOTDOT_MONTH_TAIL_AMBIGUOUS` pattern + early-return guard before the generic RANGE branch.
- `DateTagTest.java` — new `CODE_AMBIGUOUS_MONTH_YEAR_TAIL = 4253`, helper `checkAmbiguousMonthYearTail`, called from both `checkDateFamily` (Path 0a', before the abbreviated-tail-range Path 0b) and `checkAllEdtfKeys` (early, before the normalize path).
- `docs/MESSAGES.md` — new section between trailing-hyphen and century/decade ambiguity rules.
- `test/test_data.osm` — two probe nodes (9100907 `:edtf` side, 9100908 base side).
- `test/expected.txt` — two new golden rows.

MessageApiAuditor count 88 → 89, regression suite green.

---

# v0.5.8 — Message title pattern cleanup

Every `tr("[ohm] …")` title is now aligned with the convention from `CLAUDE.md`: `[ohm] <Category> - <what>; <fixable|unfixable, please review | autofix by …>`. No rule-coverage changes, no semantic changes — purely a consistency pass. 10 distinct title fixes, ~310 fixture rows updated, MessageApiAuditor site count 87 → 88.

## Title fixes

- **4207** — stripped trailing `.` from the title (only title in the codebase with one).
- **4211** — `; autofix *_date based on *_date:edtf` → `; autofix by deriving *_date from *_date:edtf`.
- **4216** — `; autofix as removed` → `; autofix by deleting the key`.
- **4223** — `; autofix to delete tags` → `; autofix by deleting tags`.
- **4307** — `Source optimization - repair URL missing 'http[s]://'` → `Source optimization - URL missing 'http[s]://'; autofix by prepending https://` (the title now carries the convention's severity clause).
- **4319** — `; unfixable, should only be used once an object actually is historic` → `; unfixable, please review` (the advice was already in the description).
- **4326** — `; fixable, remove all node tags` → `; autofix by removing all node tags` (rule has an autofix; the title now uses the autofix-pattern form).
- **4245** — split into two distinct titles. The fixable branch now emits `; autofix by collapsing to min/max bounds` and the unfixable branch (when `start_date:raw` or `end_date:raw` would be clobbered) emits `; unfixable, please review` with a description explaining the `:raw` conflict.

## `and` → `&` standardization

Nine titles standardized to use `&` instead of `and` to save horizontal space in the validator panel:

- **4236** (×2) — `gap between parent start & oldest member`, `gap between latest member end & parent end`.
- **4245** — `please review & consider splitting` (now folded into the new split titles above).
- **4303** — `please review & add`.
- **4308** / **4309** — `please review & add tag`.
- **4312** — `source & source:url are different URLs`.
- **4324** (unfixable) — `URL in source:name & source:url already set`.
- **4325** (unfixable) — `text value in source:url & all sibling slots full`.

`and/or` (rule 4205) is left alone as a fixed idiom.

## Documentation

- `docs/MESSAGES.md` synced to all the above title changes.
- Fixed a long-standing misattribution: the "Invalid date — *_date:edtf invalid" section listed code **4226** with the title `; fixable, please review (backslash truncated — Rule D1)`, but in source that title is actually emitted by code **4228** (the unified fixable `:edtf` path). 4226 (`CODE_BACKSLASH_TRUNCATED`) emits only the `Suspicious date - start_date:edtf range extends after end_date; unfixable, please review` title, correctly listed in the "Backslash patterns" section. The misattributed row has been removed and the Rule D1 backslash-strip example moved under 4228 where it belongs.

## Test fixtures

`test/test_data.osm` adds three nodes (`9100904`, `9100905`, `9100906`) covering the `YYYY..YY` tail-range, century-wrap, and long EDTF range cases. These pair with `test/expected.txt` rows that have been in the repo since v0.5.5/0.5.6 — the data nodes themselves were missed in those earlier commits and ride along here.

---

# v0.5.7 — Rule 4211 also clears redundant `*_date:edtf` after move to base

When rule **4211** (`[ohm] Date mismatch - *_date:edtf & no *_date tag; autofix *_date based on *_date:edtf`) fires and the existing `*_date:edtf` value would equal the derived `*_date`, the autofix now also deletes `*_date:edtf` in the same `SequenceCommand`. Same redundancy-suppression philosophy already codified in `edtfWriteValue` and applied by `buildTripleFix` / `buildBaseAndEdtfFix` (v0.5.0): `:edtf` exists to carry info beyond the base — ranges, qualifiers, X-forms, open-ended bounds — and a `:edtf` that duplicates the base is noise.

- **Cleared (redundant):** `start_date:edtf=1850`, no `start_date` → autofix to `start_date=1850`, `start_date:edtf` deleted.
- **Preserved (informative):** `start_date:edtf=1900/1950`, no `start_date` → autofix to `start_date=1900`, `start_date:edtf=1900/1950` left intact (the range still carries info beyond the lower bound).

Rules 4250 / 4251 also derive a base from `:edtf`, but they legitimately rewrite `:edtf` to a richer form (range, open interval) — out of scope for this change. No new codes; no test-fixture diffs (existing fixtures don't exercise the redundant case).

---

# v0.5.6 — Two new suspicious-date rules (4250/4251) + three normalizer fixes

## New rules

- **4250** `[ohm] Suspicious date - negative *_date:edtf with X digit(s); autofix to EDTF range` (WARNING). Fires when `*_date:edtf` is a negative year with trailing EDTF X-digit placeholders, e.g. `-07XX` or `-123X`. The X digits represent unspecified digits, but the bounds go in the opposite direction for negative (BCE) years: `-07XX` spans `-0799` (earlier/more ancient) to `-0700` (later/less ancient). Autofix rewrites to an explicit EDTF range (`-0799/-0700`) and updates `start_date`/`end_date` to the appropriate bound. Also fixes a latent `formatDateBound` bug where the upper/lower bounds were inverted for negative X-forms.
- **4251** `[ohm] Suspicious date - *_date:edtf with ? at interval endpoint; autofix by stripping ?` (WARNING). Fires when `*_date:edtf` is of the form `?/YYYY` or `YYYY/?` — a `?` at an interval endpoint is not valid EDTF syntax. The correct open-ended forms are `/YYYY` and `YYYY/`. Autofix strips the `?` and updates `start_date`/`end_date` to the bound of the resulting interval.

## DateNormalizer fixes (no new rule codes; surface as existing 4228 fixable)

Three preprocess bugs fixed so previously-unfixable values now normalize correctly:

- **`5 - 1 BCE`-style spaced-hyphen BCE ranges** now normalize to EDTF intervals. `5 - 1 BCE` → `-0004/0000`. A preprocess step added before whitespace-collapsing catches `N - N BCE` and rewrites to `N..N BCE` so standard BCE handling applies. Also fixed a latent bug where `1 BCE` (astronomical year 0) was emitting `-0000` instead of `0000`.
- **`YYYY/..~` junk-tail after slash** now normalizes to `YYYY/`. The qualifier-adjacent-to-`..` rewrite step now guards against values already containing a `/`, so `1900/..~` correctly reaches the existing `/[.~]+$` strip and becomes `1900/`.
- **`[YYYY-ZZZZ]` bracket-enclosed ranges** now normalize to `YYYY/ZZZZ`. A preprocess step strips the brackets so `HYPHEN_RANGE_YY` can handle the content: `[1900-1950]` → `1900-1950` → `1900..1950` → `1900/1950`.

---

# v0.5.5 — DateNormalizer expansion: many new fixable patterns, latent-bug fixes, retired 4232

A substantial expansion of `DateNormalizer`'s coverage of OHM-style date shorthand, plus a few latent bugs in existing autofix paths surfaced and fixed. Driven by direct user-Claude conversation working through inventories of unfixable values from real OHM data.

## New rules

- **4246** `[ohm] Invalid date - 5+ digit number; unfixable, please review` (ERROR). Fires on any `*_date` value containing a run of 5+ consecutive digits that doesn't parse as valid EDTF — catches typos like `20251`, `12345-06`, `1997-04..1997-06006`, and Wikidata Q-numbers mistakenly placed in date tags (`Q1438579`). Legitimate EDTF long-year forms (e.g. `Y20251`, `Y-20251`) and any other syntax `edtf-java` accepts are exempt. Suppresses downstream per-key checks for that key.
- **4247** `[ohm] Suspicious date - 02/29; autofix by stripping to year` (WARNING). Fires on any `*_date` value with month=02, day=29, regardless of leap-year status. Almost nothing in history actually happened on Feb 29; in OHM it's widely used as a placeholder for approximate dates. Fires alongside 4222 on non-leap years; both warnings appear and the editor picks.
- **4248** `[ohm] Date normalization - *_date:edtf not canonical; autofix to canonical form` (ERROR). Fires when `*_date:edtf` is valid EDTF (the parser accepts it) but is not in canonical form — typically unpadded years like `700~`, `/787`, `636/700`. Downstream bound-extraction misbehaves on unpadded years (returns `7000` for `700~`) which causes spurious mismatch warnings; the autofix preserves the original in `:edtf:raw` and writes the padded canonical form.
- **4249** `[ohm] Invalid date - day 31 in a 30-day month; autofix day to 30` (ERROR). Fires when a month with 30 days (Apr/Jun/Sep/Nov) is paired with day=31. Clamps to 30. February cases stay in 4222 (too many possible interpretations).
- **4326** `[ohm] Suspicious tags - node with no unique tags from parent way; fixable, remove all node tags` (WARNING). Fires when every tag on a node is duplicated on at least one of its parent ways — accidental over-tagging where corner nodes carry the same tags as the way. Autofix removes every tag from the node.

## Retired rule

- **4232** (`[ohm] Date mismatch - *_date more precise than *_date:edtf; …`) retired. A `*_date` more precise than its `:edtf` (with base falling within `:edtf`'s bounds) is the *expected* OHM convention — the high-precision authoritative value lives on `:base`, the wider/qualified context on `:edtf`. Flagging it as a warning was wrong. Only true mismatches (base outside `:edtf`'s bounds) fire now, via the existing 4210. The `isBaseMoreSpecificWithinBounds` helper is preserved as the suppression guard for 4210.

## DateNormalizer expansion (all surface as 4202 fixable, code unchanged)

A round of inventory-driven coverage expansion against unfixable values from real OHM data:

- **`by X` / `as of X`** — aliases for `before X` → `/X` (e.g. `by 1844` → `/1844`).
- **`during X`** — pure unwrap; the prefix adds no information (`during 1934` → `1934`).
- **Recursive `before`/`by`/`as of`/`after`** — the inner value is normalized recursively through `toEdtf`, so OHM shorthand on the bound side normalizes too (`before C12` → `/11XX`, `by c1900` → `/1900~`, `as of 1850s` → `/185X`).
- **Dash-separated DM/MD/Y in `before:`/`after:`** — `before:01-01-1882` → `/1882` (coarsens to year only because day/month order is ambiguous).
- **`end of YYYY`** → `YYYY-12` (last calendar month of the named year).
- **`Nth - [early|mid|late] Mth Century [BC]`** — ordinal-century range with optional modifier on either side. `5th - mid 8th Century` → `0400/0770`.
- **Qualifier on decade/century (prefix or suffix)** — `~1960s`, `670s~`, `~C3`, `C19~` — emits an explicit slash range with the qualifier on each bound (`~C19` → `1800~/1899~`) since EDTF rejects qualifiers attached to X-forms (`196X~` is parser-invalid).
- **`early/mid/late` with leading qualifier** — `~late C1` → `0070~/0099~` (the inner output is already qualifier-bearing; the input qualifier is consumed for free).
- **Short-year range with qualifier** — `~47-50` → `0047~/0050`. Ambiguity-guarded so `5-10` (could be year-month) still falls through.
- **Hyphen range with negatives** — `-0800 - -0600` → `-0800/-0600` (after preprocess strips internal whitespace). Mixed signs work too: `-0800-1500` → `-0800/1500`.
- **Per-bound BCE markers in `..` ranges** — `182 BC..174 BC` → `-0181/-0173` (existing N-1 convention preserved per OHM canon). The RANGE handler previously corrupted the start as `"182 BC BC"` by appending the global BCE-suffix capture to a side that already had its own.
- **`Nth Century BC[E]`** — preprocess BCE_SUFFIX double-space bug fixed; `2nd Century BC` and `8th Century BCE` now normalize cleanly to `-0199/-0100` and `-0799/-0700`.
- **Open-ended slash forms** — `..1945-05-20` → `/1945-05-20` (also `1945-05-20..` → `1945-05-20/`). The `/`-form was always EDTF-valid but no `toEdtf` pattern recognized it as canonical; new `LEADING_SLASH` / `TRAILING_SLASH` patterns recurse into the bounded side so canonicalization runs (year padding, `cYYYY` → `~YYYY`, etc.).
- **Junk-tail strip** — `1959/..~` / `1959/..` / `1959/.~` / `1959/~` → `1959/`.
- **Junk `..` markers around `/`** — `1839../..1859-12-02` → `1839/1859-12-02`. When this strip fires AND a leading or trailing `..` remains alongside the `/`, that `..` is also treated as junk (`..1839/..1859` → `1839/1859`); gated on the inner-strip firing so a clean leading `..` like `...15/11/1997` (genuine open-ended-left marker) is left for the standard rewrite to convert to `/`.
- **Multi-dot collapse** — runs of 3+ dots normalize to two dots before pattern matching (`[1907...]` → `[1907..]`, `1839...1859` → `1839..1859`).
- **Unicode dash normalization** — en-dash `–`, em-dash `—`, figure-dash `‒`, minus-sign `−` all normalize to ASCII `-` early in preprocess. `0544–0595` (en-dash, common copy-paste artifact) now parses.
- **X-form with stray qualifier** — `/196X?` and `18XX~` strip the qualifier (since X-form can't carry one). `/196X?` → `/196X`.
- **Qualifier adjacent to `..`** — `~..1907` → `/1907~` (open-ended-left, ends ~1907); `1907..~` → `1907~/`. Qualifier promotes to the bound year via the slash form.
- **`..` as separator after `before`/`by`/`as of`/`during`** — after dot-collapse reduces `by...1907` to `by..1907`, the BEFORE/AFTER/DURING patterns accept `..` as a separator alongside space and colon.
- **Valid-EDTF passthrough** — at the end of `toEdtf`, after preprocess and pattern-matching haven't matched, if the result is itself valid EDTF (e.g. `192X`, `[1907..]`, `199X`) it's returned as-is. Required for canonicalization to recurse cleanly through wrappers like `192X/..` → `192X/`.
- **Path 2a in `checkDateFamily`** — when the original `*_date` value was already canonical EDTF and `toEdtf` returned it unchanged, the autofix takes a special "promote to `:edtf`" path with a friendlier title and no `:raw` write (since the input is recoverable from `:edtf`). Restores the cleaner output for cases like `start_date=1958~`, `start_date=/2013`, `start_date=1880/1891`.

## Latent bugs fixed

- **`~CN` `:edtf` autofix** previously emitted `18XX~` / `19XX~` / `12XX~` — **invalid EDTF** (the parser rejects qualifiers attached to X-forms). Now emits valid range form `1800~/1899~` etc. Affects 4 fixtures in the test corpus that had `~C19`, `~C20`, `~C13` on their base tag.
- **BCE_SUFFIX double-space**: regex was producing `"2nd Century  BC"` (two spaces) which broke downstream "Nth century BC" head-extraction; tightening the lookbehind to `(?<=\d)|\s+` (consume the existing space when present) fixed both `2nd Century BC` and `8th Century BCE`.
- **RANGE handler with per-bound BCE markers**: was appending the trailing-`BC` capture group to the start side too, corrupting `182 BC` to `182 BC BC` and producing empty output. Now skips the append when the start already ends with ` BC`.
- **`checkAllEdtfKeys` key matching**: previously used `endsWith(":edtf")` which would match nested annotation keys like `note:start_date:edtf`. Now uses `^[^:]+:edtf$` (single-namespace `:edtf` only), so `birth_date:edtf` / `lifespan:edtf` still work but `note:start_date:edtf` and `source:foo:edtf` are correctly skipped.

## Behavior change

- **4248 severity elevated to ERROR** — joins 4208 / 4228 as the unified `:edtf`-malformed cluster. Even though the parser accepts the input, downstream bound-extraction misbehaves on unpadded years and produces spurious mismatch warnings, so this is a real defect that needs the editor's attention.

## Test coverage

`test/expected.txt` regenerated to absorb routing improvements: existing `~CN`-on-base fixtures shift from invalid `:edtf=NXX~` autofix output to valid `:edtf=NNNN~/NNNN~`; existing `..YYYY` and `/YYYY` base-tag fixtures shift between Path 2a (no `:raw`, friendly title) and Path 2b (with `:raw`) according to whether the input was already canonical EDTF; one fixture (`...15/11/1997`) reclassifies from unfixable to fixable thanks to the multi-dot collapse cascading through the slash-date interpreter. No regressions; ~50 lines of churn in expected.txt across the routing improvements.

---

# v0.5.0 — Source-slot type contract; redundant `:edtf` suppression

Loosens the source-tag rules around URL vs text placement. The pre-v0.5
validator treated `source` as URL-leaning: any non-URL value triggered a
fix moving it to `source:name`, and `source:url` was treated as an alias
to be folded back into `source`. v0.5 establishes a three-slot contract
that better matches how OHM mappers actually use these tags.

## The new source-slot contract

- `source` (and numbered variants `source:N`) — **URL or text**, both valid
- `source:name` (and `source:N:name`) — **text only**
- `source:url` (and `source:N:url`) — **URL only**

The validator enforces the slot typing on `:name` and `:url` and warns
when values land in the wrong type-slot.

## Retired rules

Seven codes retired because their premise (that `source` should always be
a URL) is no longer true:

- **4306** Non-URL `source` rename — text in `source` is valid
- **4310** Lone `source:name` without companion — text-only citations are valid
- **4311** Identical `source` / `source:url` — harmless redundancy
- **4312 case 1** `source:url` with empty `source` — `source:url` is now first-class
- **4313** Text-in-`source` + URL-in-`source:url` swap — valid layout
- **4321** Non-URL `source` with populated `source:name` — both valid
- **4322 / 4323** Multi-URL / multi-text split target conflict — replaced by always-appendable enumeration

The 4312 conflict variant (different URLs in `source` and `source:url`)
remains active.

## New rules

- **4324** `source:name` contains a URL → autofix moves it to
  `source:url` when that slot is empty; unfixable when the URL slot
  already holds a different URL.
- **4325** `source:url` contains non-URL text → autofix moves it
  through a fallback chain (`source` → `source:name` → `source:note`)
  to the first empty sibling. Unfixable when all three are full.

## Modified rules

- **4307** URL-missing-scheme autofix now also fires on `source:url`
  and `source:N:url` (was previously limited to `source` and
  `source:N`).
- **4314** semicolon `URL;text` split now writes `source=text`,
  `source:url=URL` (flipped from the old `source=URL`,
  `source:name=text` direction). Unfixable variant when `source:url`
  already holds a different URL.
- **4315** multi-URL split is now always autofixable: items append
  past the highest existing `source:N` index instead of producing a
  conflict warning when slots are occupied. Shared enumeration
  convention extracted as `nextSourceIndex(p)`.
- **4316** multi-text split is now **unfixable** — semicolons in text
  are ambiguous (legitimate punctuation vs. multi-source delimiter)
  and the prior autofix risked destroying real citations. Just warns.

## Also: never write `*_date:edtf` equal to `*_date`

The date-normalization autofix builders (`buildTripleFix`,
`buildBaseAndEdtfFix`) now suppress `:edtf` when its value would equal
the base — `start_date=1900` shouldn't carry a redundant
`start_date:edtf=1900`. Affected cases include BC dates that normalize
to padded astronomical years (`273 BC` → `-0272`), year-padding
(`500` → `0500`), separator cleanup (`1855_12` → `1855-12`), and ISO
typos (`29/11/2024` → `2024-11-29`). The `:edtf` slot is reserved for
forms that carry information the base can't express — ranges,
qualifiers, unspecified-digit notation, open-ended bounds. Description
text shows `:edtf=(absent)` to signal the suppression.

Existing redundant pairs (where the tagger has manually written
identical values to base and `:edtf`) are not flagged by v0.5 — only
the autofix output is constrained.

## Test coverage

Repurposed and added synthetic fixtures in `test/crasher_braces.osm`
covering the new rules' fixable and unfixable variants, plus the
4325 fallback chain (paths to `source`, `source:name`, `source:note`,
all-full unfixable). The golden-file diff in `ant test` regenerated
to match: ~250 lines removed (retired rules no longer firing on
real-data fixtures), with new code/title strings replacing the
displaced ones.

---

# v0.4.0 — JOSM crash hotfix, autofix-safety guards, normalization wins

Originally scoped as a v0.3.3 hotfix for [#26](https://github.com/OpenHistoricalMap/ohm-josm-tag-validator/issues/26) (a JOSM crash on tag values containing `{` or `}`). Bumped to v0.4.0 once the audit it triggered surfaced a class of autofix-safety bugs and the testing pass uncovered several normalization wins worth shipping in the same release.

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
- **4203 / 4204 (decade/century normalization)** and **4221 (`end_date=present`)** now check the target `*_date:raw` slot before writing. If it already holds a different value, the new **4242** fires the unfixable variant. Same shape as 4321/4322/4323/4240: name both the proposed and existing values so the editor can decide.

The unfixable variants name the occupied slot and its current value so the editor can decide whether to merge, replace, or shift the split to higher indices.

## New: 4245 — packed-features warning ("1 feature that should be N")

When `start_date` and `end_date` both contain semicolon-delimited entries with the same count (≥ 2 each) and every entry parses as a strict ISO date, the feature is almost certainly an attempt to encode N temporally-distinct features in one — e.g. a building rebuilt twice, recorded as one feature with three start/end pairs.

The new **4245** warning fires "1 feature that should be N; please review and consider splitting" with an autofix that:

- Collapses `start_date` to the minimum of the start values
- Collapses `end_date` to the maximum of the end values
- Preserves the original semicolon strings in `start_date:raw` and `end_date:raw`
- Adds `fixme=split into multiple features` so the editor remembers to do the actual split manually

When the autofix would clobber an existing `:raw` (same protection as 4242), the warning fires unfixable. When 4245 fires with or without autofix, the per-key date checks for `start_date` and `end_date` are suppressed on this primitive — they'd otherwise produce noisy "cannot be read" warnings against the same semicolon strings the new rule already explains.

Real-world impact: 4 primitives in the regression dataset (one node, three ways) flip from opaque "cannot be read" warnings to the new actionable autofix.

## New: 4243 — boundary chronology has non-relation members

Per [issue #21](https://github.com/OpenHistoricalMap/ohm-josm-tag-validator/issues/21), a "boundary chronology" — defined as a `type=chronology` relation with 2 or more `type=boundary` relations as members — must contain only relation members. Non-relation members (ways, nodes attached directly) indicate the contributor mistakenly bypassed the member boundary relations and attached the geometry to the chronology instead. Severity ERROR.

The 2+ threshold avoids flagging single-boundary chronologies (degenerate cases the editor probably hasn't finished assembling).

## New: 4236 boundary-gap variants

Per [issue #22](https://github.com/OpenHistoricalMap/ohm-josm-tag-validator/issues/22), the chronology gap rule (4236) now fires for **two new boundary cases** in addition to the existing "gap between consecutive members":

- **Gap between parent `start_date` and the oldest member's `start_date`**, when the gap exceeds 1 unit at the coarser shared precision. e.g. chronology declares `start_date=0100` but the oldest member relation starts at `0300` — 199-year gap at the start.
- **Gap between the latest member's `end_date` and parent `end_date`**, same threshold. Skipped entirely if any eligible member is open-ended (no `end_date`), since open-ended coverage extends to infinity.

Both variants use the same code (4236), severity, and overall description shape as the original consecutive-pair gap. The titles differentiate the three cases. Real OHM data turns out to have plenty of these — ~14 boundary gaps fire across the regression dataset.

## Generalization: source/source:url consolidation now handles every `source[:N]?:url`

Per [issue #27](https://github.com/OpenHistoricalMap/ohm-josm-tag-validator/issues/27), rules 4311 / 4312 / 4313 (`source` vs `source:url` consolidation) now apply to every `source[:N]?:url` key on a primitive, not just the literal `source:url`. So a feature with `source:1:url=https://...` gets paired against `source:1`, `source:7:url` against `source:7`, etc. Per-pair, the four existing sub-cases (blank companion → rename, identical values → delete duplicate, both URLs → move to next free `source:N` slot, text companion → swap with `:name`) run independently.

The fix descriptions name the actual key pair so the editor sees `source:1:url=... should live in source:1` rather than the static `source:url should live in source`. The autofix targets are derived per-pair: text→name swap on `source:1` writes to `source:1:name` (not `source:name`).

## Suppression: 4303 (`Missing tag - source on named feature`) skips chronology relations

Per [issue #23](https://github.com/OpenHistoricalMap/ohm-josm-tag-validator/issues/23), `type=chronology` relations are now exempt from the missing-source warning. They're aggregator wrappers around member relations (each of which carries its own provenance), so requiring a top-level source on the chronology itself adds noise without signal. ~3 chronology relations in the regression dataset stop firing this warning.

## Behavior change: 4212 / 4213 are now unfixable

`[ohm] Suspicious date - 01-01 start_date` (4212) and `[ohm] Suspicious date - 12-31 end_date` (4213) previously offered a one-click autofix to trim to the bare year (`start_date=1875-01-01` → `start_date=1875`). Per [issue #28](https://github.com/OpenHistoricalMap/ohm-josm-tag-validator/issues/28), these are now unfixable WARNINGs. Jan 1 and Dec 31 are legitimate real dates often enough (laws taking effect, treaties signed, terms ending, fiscal year boundaries) that even an opt-in autofix proved too easy to apply by mistake when batch-fixing. The descriptions still suggest the manual trim for the false-precision case.

## Also fixed: packed-date typo (`YYYY-MMDD` / `YYYY-MDD`)

Common typo where the user wrote a packed date with the month-day hyphen missing (e.g. `end_date=1930-0630` for `1930-06-30`). Pre-fix, these flowed through the slash-range pipeline as if they were ranges, producing wrong base values like `end_date=0630` with `:edtf=1930/0630`. Now handled as a typo-fix:

- **`YYYY-MMDD`** (4-digit suffix) → autofix to `YYYY-MM-DD` when `YYYY > 1200` AND the implied `MM-DD` is a real calendar date (leap-year-aware via `java.time.LocalDate`).
- **`YYYY-MDD`** (3-digit suffix; leading zero on the month omitted) → autofix to `YYYY-0M-DD` when `YYYY > 1000` AND the implied `M-DD` is real.
- **Held-back cases** — year below the 1200 threshold, or the implied MM-DD isn't a real calendar date (`1875-1131`, `1875-0229` on a non-leap year), but the input still looks like a packed-date attempt (`YYYY > MMDD`) — fire the new **4244** unfixable warning.

Three real-world rows in `test_data.osm` flip from broken slash-range output to clean ISO triples on this fix alone.

## Also fixed: abbreviated-tail and implausibly-ancient range normalization

Two normalization fixes that surfaced during testing:

- **`YYYY/YY` abbreviated-tail range** (e.g. `1716/17`, `1850/52`). Pre-fix, edtf-java accepted the short form as valid EDTF and produced wildly wrong base values — `end_date=1850/52` came out as `end_date=5299`. Now expanded to the full pair before normalization: `end_date=1850/52` → `end_date=1852, :edtf=1850/1852, :raw=1850/52`. For `start_date`, the base is the lower year; for `end_date`, the upper. Wrap cases like `1899/01` (where the suffix would resolve to a year less than the start) deliberately fall through and are not autofixed.

- **`0000..YYYY` implausibly-ancient range** (e.g. `0..1850`, `00..1900`). When the upper bound `YYYY > 400`, the validator now collapses the leading-zeros placeholder to the open-start EDTF form `/YYYY` and uses `YYYY` as the base. `start_date=0..1850` → `start_date=1850, :edtf=/1850, :raw=0..1850`. Below the threshold the input could be a real ancient range; falls through.

Dozens of rows in real OHM data flip from broken or ambiguous output to clean form on these two paths alone.

## Also fixed: cYYYY shorthand normalization

Pre-fix, the compact `cYYYY` shorthand (e.g. `start_date=c1920`) flowed through the existing decade/century pipeline and produced **invalid EDTF output** like `start_date:edtf=1919XX` — a malformed string that JOSM's downstream EDTF check would then re-flag, leaving the user worse off than before they accepted the fix.

The validator now handles `cYYYY` shorthand in five magnitude/sign cases:

- **`abs(YYYY) >= 100`** — unambiguous "circa year": rewrite to the canonical EDTF `~YYYY` triple. `c1920` → `start_date=1920, :edtf=1920~, :raw=c1920`. `c-1500` → `start_date=-1500, :edtf=-1500~`. `c1920bc` flips the sign directly with no N-1 offset → `start_date=-1920, :edtf=-1920~`.
- **`22 <= YYYY <= 99`** — ambiguous between "circa year YY" and "the YYth century". Fires the new **4241** unfixable warning. Manual review required to pick one.
- **`1 <= YYYY <= 21`** — highly probable positive century shorthand. Continues to flow through the existing CN/YY00s century pipeline unchanged (so `c19` is still treated the same as `1800s`).
- **`-99 <= YYYY <= -1`** — negative magnitudes ≤ 21 (e.g. `c-19`) and the full negative ambiguous band. The existing CN pipeline silently strips the sign and produces a CE century, so these values now fire **4241** as well.
- **`YYYY == 0`** (`c0`, `c-0`, `c0bc`) — degenerate. Year zero / century zero are both nonsense in OHM's astronomical-year convention. Fires **4241**.

The `c` prefix is case-insensitive and accepts a `bc` / `BCE` suffix.

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
