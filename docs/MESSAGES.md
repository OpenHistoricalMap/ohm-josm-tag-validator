# OHM Tags Validator — Message Reference

Most messages use `WARNING` severity. The following codes are `ERROR`
severity (malformed or structurally-invalid data that consumers cannot
interpret, plus the missing-`wikidata` rule):
**4201**, **4202**, **4207**, **4208**, **4217**, **4218**, **4221**,
**4222**, **4228**, **4231**, **4233**, **4234**, **4238**, **4302**.

Titles follow the pattern:
`[ohm] <Category> - <what>; <fixable|unfixable>, please review [<action>]`

References to "rules" below are defined in the javadoc in DateTagTest.java.

---

## DateTagTest (codes 4200–4239)

### Suspicious date — missing start_date

| Code | Title |
|------|-------|
| 4200 | `[ohm] Suspicious date - man-made object without start_date; unfixable, please review` |

**Trigger:** Feature carries at least one tag from a curated **man-made allowlist** but has no `start_date`. The allowlist (see `DateTagTest.MANMADE_KEYS` and `MANMADE_DENYLIST`) covers buildings, highways, railways, amenities, leisure, barriers, addressed features, and other tags that mark a primitive as built / established with a discrete creation date.  
**Fix:** None.  
**Description:** _Please make every effort to attempt a reasonable range for the `start_date:edtf` tag and provide an explanation in the `start_date:source` tag._

The trigger is a positive allowlist (since 2026-04). Earlier versions used a negative test ("any tag except `natural=*`") which over-fired badly on member ways of large boundary relations — see issue #4 (3344 warnings on the British Empire 1921-1922 relation, forum post 2026-04-21). A primitive with only `name=*`, `wikidata=*`, `boundary=*` (on a way), `addr:*`-free metadata, etc. no longer triggers; it must carry a positively man-made key.

Notable allowlist entries:

- **Always trigger (any value):** `building`, `building:part`, `highway`, `railway`, `aeroway`, `aerialway`, `bridge`, `tunnel`, `man_made`, `power`, `pipeline`, `amenity`, `shop`, `office`, `craft`, `tourism`, `historic`, `military`, `emergency`, `public_transport`, `telecom`, `leisure`, `barrier`, plus any `addr:*` key.
- **Trigger unless value is in denylist:** `landuse` (skip `forest`/`meadow`/`grass`/`wood`/`scrub`/`heath`); `waterway` (skip `river`/`stream`/`brook`/`riverbank`/`tidal_channel`/`wadi`); `place` (skip `island`/`islet`/`archipelago`/`peninsula`/`cape`).
- **Relation-only triggers:** `boundary=*` (the political entity has a date; member ways/nodes carrying `boundary=*` do **not** trigger because their date lives on the parent), and `type=route`.

**Example (fires):**  
Trigger: `building=yes` with no `start_date`.  
Suggested manual fix: add `start_date:edtf=1920~/1940~` (or whatever bracket fits) plus `start_date:source=USGS topo 1925`.

**Example (does not fire):**  
A way tagged `boundary=administrative` with no other keys. The line segment's date is implicit from the parent relation; only the relation itself fires.

---

### Ambiguous date

| Code | Title |
|------|-------|
| 4220 | `[ohm] Ambiguous date - trailing hyphen in date; unfixable, please review` |

**4220 trigger:** Value ends with a trailing hyphen (e.g., `2021-`), which is ambiguous between a typo and an open-ended range.  
**4220 description:** _{key}={value}: could be a typo: {suggestion}; an incomplete input; or an open-ended range {suggestion}/. Manual review needed._

**Example:**  
Trigger: `start_date=2021-`  
Suggested manual fix: choose one of `start_date=2021` (typo), `start_date=2021-03-15` (incomplete input), or `start_date:edtf=2021/` (open-ended range).

---

### Ambiguous century/decade

| Code | Title |
|------|-------|
| 4203 | `[ohm] Ambiguous date - unclear century/decade date; autofix as decade` |
| 4204 | `[ohm] Ambiguous date - unclear century/decade date; autofix as century` |

**Trigger:** Values like `1800s` are ambiguous, either a decade (1800–1809) or a century (1800–1899). Two sibling warnings fire together — one offering each interpretation.  
**Fix:** Applies the chosen interpretation to `*_date` and `*_date:edtf`.  
**Description:** _{key}={value} as a decade/century: {key}={normalized}, :edtf={edtf}_

**Example:**  
Before: `start_date=1800s`  
After autofix as decade (4203): `start_date=1800`, `start_date:edtf=180X`, `start_date:raw=1800s` — bounds 1800–1809.  
After autofix as century (4204): `start_date=1800`, `start_date:edtf=18`, `start_date:raw=1800s` — bounds 1800–1899.

---

### Suspicious date — year-boundary

| Code | Title |
|------|-------|
| 4212 | `[ohm] Suspicious date - 01-01 start_date; autofix by removing -01-01` |
| 4213 | `[ohm] Suspicious date - 12-31 end_date; autofix by removing -12-31` |
| 4214 | `[ohm] Suspicious date - 12-31 start_date; unfixable, please review` |
| 4214 | `[ohm] Suspicious date - 01-01 end_date; unfixable, please review` |

**4212/4213 trigger:** `start_date=YYYY-01-01` or `end_date=YYYY-12-31` — at the year boundary that matches the role. Under OHM's conservative year-only convention, `start_date=YYYY` already starts at Jan 1 and `end_date=YYYY` already ends Dec 31, so the explicit form is functionally redundant.  
**4212/4213 fix:** Trim to the bare year (e.g. `start_date=1875-01-01` → `start_date=1875`).  
**4212/4213 description:** _{key}={value} → {key}={year}_

**4214 trigger:** `start_date=YYYY-12-31` or `end_date=YYYY-01-01` — at the *opposite* year boundary for the role. Could be a typo (next/previous year intended) or a legitimate event-day boundary (e.g. a treaty signed Dec 31). Ambiguous; manual review only.  
**4214 fix:** None.  
**4214 description:** _{key}={value}: end-of-year used as start_date / start-of-year used as end_date. If the exact day is unknown, manually change to {key}={year} (the year this date falls in) or {key}={shifted} (next/previous year, if a typo)._

**4212/4213 example:**  
Before: `start_date=1875-01-01`  
After autofix: `start_date=1875`.

**4214 example:**  
Trigger: `start_date=1875-12-31`.  
Suggested manual fix: if the start was genuinely on Dec 31, 1875 (e.g. a treaty signed that day), leave alone; otherwise change to `start_date=1875` (the year this date falls in) or `start_date=1876` (the year the entity started, if the original was an off-by-one typo).

---

### Suspicious date — ordering and equality

| Code | Title |
|------|-------|
| 4215 | `[ohm] Suspicious date - start_date > end_date; autofix by swapping these` |
| 4224 | `[ohm] Suspicious date - start_date = end_date; unfixable, please review` |
| 4224 | `[ohm] Suspicious date - start_date = end_date with backslash pattern; autofix by deleting start_date:edtf` |

**4215 trigger:** `start_date` parses to a date after `end_date`.  
**4215 fix:** Swaps `start_date` and `end_date`.  
**4215 description:** _start_date={start}, end_date={end}. Swap?_

**4224 trigger (no backslash):** `start_date` and `end_date` are equal — valid only for a feature that existed for a single day.  
**4224 trigger (backslash):** `start_date:edtf` contains a backslash pattern where start equals end.  
**4224 fix (backslash):** Deletes `start_date:edtf`.
**4224 description (backslash):** _start_date:edtf=/{end} → null_

**4215 example:**  
Before: `start_date=1950`, `end_date=1900`  
After autofix: `start_date=1900`, `end_date=1950`.

**4224 example (no backslash):**  
Trigger: `start_date=1969-07-20`, `end_date=1969-07-20`.  
Suggested manual fix: if this is a single-day event (Apollo 11 landing) leave it; otherwise correct one side.

**4224 example (backslash):**  
Before: `start_date:edtf=\1900`, `end_date=1900`  
After autofix: `start_date:edtf` removed; `end_date=1900` retained as the canonical date.

---

### Suspicious date — future date

| Code | Title |
|------|-------|
| 4216 | `[ohm] Suspicious date - >10 year into the future; autofix as removed` |

**Trigger:** A date tag value is more than 10 years beyond today.  
**Fix:** Deletes the offending key.  
**Description:** _{key}={value} is more than ten years in the future. Likely a typo; delete the key?_

**Example:**  
Before: `end_date=2099` on a feature edited in 2026  
After autofix: `end_date` removed (treated as a typo / data-import artifact).

---

### Invalid date — invalid components

| Code | Title |
|------|-------|
| 4217 | `[ohm] Invalid date - invalid month in start_date or end_date; autofix to YYYY` |
| 4218 | `[ohm] Invalid date - invalid day in start_date or end_date; autofix to YYYY-MM` |
| 4222 | `[ohm] Invalid date - month/day mismatch; too many days in the month; unfixable, please review` |

**4217 trigger:** Month component is out of range (< 1 or > 12). Offers trim-to-YYYY fix.  
**4217 description:** _{key}={value}: month {MM} is > 12. Trim to {YYYY}?_

**4218 trigger:** Day component is out of range (< 1 or > 31). Offers trim-to-YYYY-MM fix.  
**4218 description:** _{key}={value}: day {DD} is out of range. Trim to {YYYY-MM}?_

**4222 trigger:** Full ISO date that passes range checks but isn't a real calendar date (Feb 30, June 31, Feb 29 on non-leap year, etc.).  
**4222 fix:** None.  
**4222 description:** _{key}={value}: {YYYY}-{MM}-{DD} is not a valid date (e.g. 2/30, 6/31, or 2/29 in non-leap year)._

**4217 example:**  
Before: `start_date=1900-13-15`  
After autofix: `start_date=1900` (month 13 is invalid; trim to year).

**4218 example:**  
Before: `start_date=1900-06-32`  
After autofix: `start_date=1900-06` (day 32 is invalid; trim to year-month).

**4222 example:**  
Trigger: `start_date=1900-02-29` (1900 was not a leap year).  
Suggested manual fix: change to a real date such as `1900-02-28` or `1900-03-01`.

---

### Invalid date — present used incorrectly

| Code | Title |
|------|-------|
| 4221 | `[ohm] Invalid date - end_date=present; autofix to no end_date` |
| 4231 | `[ohm] Invalid date - start_date=present; autofix to no start_date` |

**4221 trigger:** `end_date` (or similar) holds the literal value `present` (case-insensitive), which OHM encodes as an absent end_date rather than as a tag value.  
**4221 fix:** Clears `end_date` and `end_date:edtf`; sets `end_date:raw=present`.  
**4221 description:** _{key}={value} means an ongoing feature. Clear base and :edtf, mark with :raw={value}?_

**4231 trigger:** `start_date=present` — `present` describes an ongoing state, not a start point, so it isn't a meaningful start_date value (it's only valid as end_date).  
**4231 fix:** Deletes `start_date` and `start_date:edtf`.  
**4231 description:** _{key}={value}: 'present' describes an ongoing state, not a start point. 'present' is only valid as end_date. Delete {key} and {key}:edtf?_

**4221 example:**  
Before: `end_date=present`  
After autofix: `end_date` cleared, `end_date:edtf` cleared, `end_date:raw=present` (signals ongoing intent without breaking date consumers).

**4231 example:**  
Before: `start_date=present`, `end_date=2010`  
After autofix: `start_date` and `start_date:edtf` deleted; `end_date=2010` retained.

---

### Invalid date — EDTF in base tag

| Code | Title |
|------|-------|
| 4202 | `[ohm] Invalid date - *_date contains a readable EDTF date; fixable, please review` |

**Trigger:** `start_date` or `end_date` contains a value that looks like EDTF (slashes, ranges, uncertainty markers) rather than a plain ISO date.  
**Fix:** Moves value to `*_date:edtf`, derives plain ISO base, stores original in `*_date:raw`.  
**Description:** _{key}={value} → {key}={normalized}, :edtf={edtf}, :raw={value}_

**Example:**  
Before: `start_date=2003-03/2016`  
After autofix: `start_date=2003-03`, `start_date:edtf=2003-03/2016`, `start_date:raw=2003-03/2016`.

---

### Invalid date — *_date unparseable or unnormalizable

| Code | Title |
|------|-------|
| 4201 | `[ohm] Invalid date - *_date cannot be read; unfixable, please review` |
| 4202 | `[ohm] Invalid date - *_date; fixable, please review` |

**4201 trigger:** `start_date` or `end_date` cannot be parsed or normalized to EDTF.  
**4201 fix:** None.  
**4201 description:** _{key}={value} cannot be normalized._

**4202 trigger:** Value can be normalized; see also "EDTF in base tag" above.  
**4202 description:** _{key}={value} → {key}={normalized}, :edtf={edtf}, :raw={value}_

**4201 example:**  
Trigger: `start_date=romain` (unparseable text).  
Suggested manual fix: replace with a real date or remove the tag.

**4202 example:**  
Before: `start_date=fall of 1814`  
After autofix: `start_date=1814`, `start_date:edtf=1814-23` (EDTF season code 23 = fall), `start_date:raw=fall of 1814`.

---

### Invalid date — *_date:edtf invalid

| Code | Title |
|------|-------|
| 4208 | `[ohm] Invalid date - *_date:edtf; unfixable, please review` |
| 4226 | `[ohm] Invalid date - *_date:edtf; fixable, please review` _(backslash truncated — Rule D1)_ |
| 4228 | `[ohm] Invalid date - *_date:edtf; fixable, please review` |
| 4228 | `[ohm] Invalid date - *_date:edtf; unfixable, please review` |

**4208 trigger:** `*_date:edtf` is invalid EDTF and there is no corresponding base tag to fall back on.  
**4226 trigger (Rule D1):** `*_date:edtf` starts with `\` and the remainder, after stripping the backslash, can be normalised.  
**4228 fixable trigger:** `*_date:edtf` is invalid EDTF but can be auto-corrected.  
**4228 unfixable trigger:** `*_date:edtf` is invalid EDTF and cannot be corrected automatically.

**4208 example:**  
Trigger: `start_date:edtf=garbage`, no `start_date` present.  
Suggested manual fix: replace `:edtf` with valid EDTF, or delete the tag.

**4226 example:**  
Before: `start_date:edtf=\1900`, with `start_date=1900`  
After autofix: `start_date:edtf=1900` (backslash prefix stripped, remainder is valid).

**4228 example (fixable):**  
Before: `start_date:edtf=199x` (lowercase X)  
After autofix: `start_date:edtf=199X` (canonical form), `start_date:edtf:raw=199x` preserves the original.

**4228 example (unfixable):**  
Trigger: `start_date:edtf=2020-13-99` — invalid and not normalizable.  
Suggested manual fix: replace with a valid EDTF expression.

---

### Date mismatch — base vs. :edtf disagreement

| Code | Title |
|------|-------|
| 4210 | `[ohm] Date mismatch - *_date does not match *_date:edtf; unfixable, please review` |
| 4211 | `[ohm] Date mismatch - *_date:edtf & no *_date tag; autofix *_date based on *_date:edtf` |
| 4232 | `[ohm] Date mismatch - *_date more precise than *_date:edtf; autofix *_date:edtf=*_date` |

**4210 trigger:** `*_date` is present and valid, but disagrees with the bound implied by `*_date:edtf`.  
**4210 description:** _{key}={value} but {key}:edtf={edtf} implies {key}={expected}. Manual review needed._

**4211 trigger:** `*_date:edtf` is valid but no `*_date` base tag exists.  
**4211 fix:** Derives and sets `*_date` from `*_date:edtf`.  
**4211 description:** _{key}:edtf={edtf} implies {key}={derived}._

**4232 trigger:** `*_date` is more specific (e.g. full ISO date) than `*_date:edtf` (e.g. year only).  
**4232 fix:** Replaces `*_date:edtf` with the value from `*_date`.

**4210 example:**  
Trigger: `start_date=2020`, `start_date:edtf=1900/1950` (base year is well outside the EDTF range).  
Suggested manual fix: pick the authoritative value and update the other to match.

**4211 example:**  
Before: `start_date:edtf=1900/1950`, no `start_date`  
After autofix: `start_date=1900` derived as the lower bound.

**4232 example:**  
Before: `start_date=1900-03-15`, `start_date:edtf=1900`  
After autofix: `start_date:edtf=1900-03-15` (lifts to match the more specific base).

---

### Date mismatch — :raw disagreement

| Code | Title |
|------|-------|
| 4205 | `[ohm] Suspicious date - *_date:raw exists, but no *_date{:edtf}; autofix to reconstruct *_date and/or *_date:edtf` |
| 4206 | `[ohm] Date mismatch - across date tags; autofix by deleting :raw` |
| 4207 | `[ohm] Invalid date - Unparseable data preserved in *_date:raw tag, no valid *_date:edtf or *_date tags; unfixable, please review.` |

**4205 trigger (Rule A):** `tagcleanupbot` wrote a `:raw` value and the derived `*_date` / `*_date:edtf` can be reconstructed from it.  
**4205 fix:** Reconstructs the triple from `:raw`.  
**4205 description:** _{key}:raw={raw} implies {key}={date}, {key}:edtf={edtf}._

**4206 trigger:** Non-bot editor; `*_date` and `*_date:edtf` don't match the `:raw` value.  
**4206 fix:** Offers deletion of the stale `:raw` tag.  
**4206 description:** _{key} and {key}:edtf don't match {key}:raw={raw}. Delete the machine-generated :raw tag?_

**4207 trigger:** `*_date:raw` is set but `*_date:edtf` and `*_date` are absent or unparseable.  
**4207 fix:** None.

**4205 example:**  
Before: `start_date:raw=ca. 1900` (last editor: `tagcleanupbot`), no `start_date` or `:edtf`  
After autofix: `start_date=1900`, `start_date:edtf=1900~`, `start_date:raw=ca. 1900` (triple reconstructed from the bot-authored :raw).

**4206 example:**  
Before: `start_date=1950`, `start_date:edtf=1950`, `start_date:raw=ca. 1900` (last editor was a human, who edited base/edtf away from the bot's :raw)  
After autofix: `start_date:raw` deleted (the human edit is canonical; the stale :raw is removed).

**4207 example:**  
Trigger: `start_date:raw=garbage`, no valid `start_date` or `:edtf`.  
Suggested manual fix: hand-correct the date based on whatever source produced the :raw value.

---

### Backslash patterns (Rules A and C)

| Code | Title |
|------|-------|
| 4223 | `[ohm] Suspicious date - start_date:edtf=\[end_date]; autofix to delete tags` _(Rule A, bot rollback)_ |
| 4225 | `[ohm] Suspicious date - start_date:edtf range extends after end_date; unfixable, please review` _(Rule C)_ |

**4223 trigger (Rule A):** `start_date:edtf` matches the pattern written by `tagcleanupbot`. Full rollback offered.

**4225 trigger (Rule C):** `start_date:edtf` is a slash-range that extends past `end_date`.

**4223 example:**  
Before: `start_date=1900`, `start_date:edtf=\1900`, `end_date=1900` (last editor `tagcleanupbot`)  
After autofix: `start_date` and `start_date:edtf` deleted (bot-induced rollback).

**4225 example:**  
Trigger: `start_date:edtf=1900/1960`, `end_date=1950` (range extends past end).  
Suggested manual fix: pick the authoritative end and tighten one or the other.

---

### Julian calendar conversion

| Code | Title |
|------|-------|
| 4233 | `[ohm] Invalid date - Julian date; fixable, please review` |

**Trigger:** `start_date` or `end_date` uses `j:YYYY-MM-DD` (Julian) or `jd:NNNNNNN` (Julian Day Number) notation.  
**Fix:** Converts to Gregorian, stores converted value in base tag, preserves original in `*_date:note`.  
**Description:** _{key}={julian} → {key}={gregorian} (Gregorian), {key}:note added_

**Example:**  
Before: `start_date=j:1582-10-04` (Julian calendar)  
After autofix: `start_date=1582-10-14` (Gregorian equivalent), `start_date:note=j:1582-10-04` (original preserved).

---

### Chronology — relation structural checks

| Code | Title |
|------|-------|
| 4234 | `[ohm] Chronology - member date range outside parent chronology range; unfixable, please review` |
| 4235 | `[ohm] Chronology - member date range overlap; unfixable, please review` |
| 4236 | `[ohm] Chronology - gap between member date ranges; unfixable, please review` |
| 4237 | `[ohm] Chronology - member missing required date tag; unfixable, please review` |
| 4238 | `[ohm] Chronology - member duplicate to its predecessor; unfixable, please review` |
| 4239 | `[ohm] Chronology - member without dates; unfixable, please review` |

These four rules apply only to `type=chronology` relations. Comparisons use only strict `start_date` / `end_date` values in `YYYY`, `YYYY-MM`, or `YYYY-MM-DD` form (no `:edtf`, no `:raw`, no Julian, no EDTF intervals). Members whose dates can't be parsed strictly are skipped from the range comparisons but still flagged by 4237 if a tag is missing. Findings always attach to the parent chronology relation; offending member ids appear in the description text.

**4234 (ERROR) trigger:** Any member's `start_date` falls before the parent chronology relation's own `start_date`, or any member's `end_date` falls after the parent's `end_date`. Skipped if neither parent date is strictly parseable.

**4235 (WARNING) trigger:** Any pair of members has overlapping date ranges. Touching boundaries at matching precision (e.g. member A `end_date=1850`, member B `start_date=1850`) are treated as adjacency and **don't** fire — this is the canonical OHM successor pattern. Day-level expansion is used for the strict intersection test otherwise (year-only `1850` expands to Jan 1 – Dec 31). A member with `start_date == end_date` (instantaneous event) only collides if another member's range strictly contains the instant.

**4236 (WARNING) trigger:** After sorting members by `start_date`, any consecutive pair has more than one unit of gap at the coarser of the two boundary precisions. `end=1850 → start=1851` (year precision) is no gap; `end=1850 → start=1852` is a one-year gap (acceptable); `end=1850 → start=1853` fires. Same logic applies at month and day precision.

**4237 (WARNING) trigger:** Any member is missing a strictly-parseable `start_date`, OR any non-youngest member is missing a strictly-parseable `end_date`. The youngest member (highest parseable `start_date`, ties broken by latest `end_date` then by absent `end_date`) may legitimately lack `end_date` — it is the still-current successor.

**4234 example:**  
Parent chronology has `start_date=1800`, `end_date=2000`. Member relation has `start_date=1750`. Fires: member's start before parent's start.

**4235 example:**  
Member A: `start_date=1800`, `end_date=1850`. Member B: `start_date=1840`, `end_date=1900`. Fires: ranges overlap from 1840 to 1850.

**4236 example:**  
Sorted members: A `1800–1850`, B `1855–1900`. Five missing years between A's end and B's start. Fires.

**4237 example:**  
Chronology with three members. The youngest (start=1980) has no `end_date` (still in use — allowed). Another member has `start_date=1900` but no `end_date`. Fires for the second member.

**4238 (ERROR) trigger:** A chronology member's non-date tags (everything except keys matching the OHM `_date` family — so `start_date`, `end_date`, `*_date:edtf`, `*_date:raw`, `*_date:source`, `*_date:note`, etc. are excluded) are exactly equal to its predecessor's, **and** the two have identical geometry. Predecessor is the previous member after sorting by `start_date`. Applies to all member types — nodes, ways, and relations.

Geometry comparison is coordinate-based and recursive: two nodes match only if their `lat`/`lon` are equal; two ways match only if they have the same number of nodes and each pair of corresponding nodes shares coordinates (different node ids are fine if they sit at the same position); two relations match only if their member lists agree on `(role, recursive geometry)` for each entry. Incomplete primitives (members not yet downloaded) cannot be compared and are treated as "not duplicate" to avoid false positives. The rule also skips when both members have no non-date tags at all (nothing to compare).

The implication: if the entity didn't change in any meaningful way between successive time periods — neither tags nor shape — it shouldn't be split into separate chronology members.

**4238 example:**  
Member A: `name=Town Hall`, `building=yes`, `wikidata=Q12345`, `start_date=1850`, `end_date=1900`.  
Member B: `name=Town Hall`, `building=yes`, `wikidata=Q12345`, `start_date=1900`, `end_date=1950`.  
Same name, building tag, and wikidata QID — the only differences are date fields. Fires.

**4239 (WARNING) trigger:** A chronology member has neither a `start_date` tag nor an `end_date` tag (both completely absent — not just unparseable). Fires once per such member. Takes precedence over 4237 to avoid noisy double-reporting on members with no date info at all (the typical case being a member primitive that is referenced by the relation but hasn't been downloaded into the dataset yet — JOSM exposes it as an incomplete proxy with no tags). Members with at least one of the two date tags present, even if unparseable, still go through 4237.

**4239 example:**  
Trigger: chronology relation references member ways that haven't been downloaded; each appears as an incomplete proxy with no tags. Fires once per missing member.  
Suggested manual fix: download the missing members (Ctrl+Alt+Down on the chronology relation) and re-run the validator.

---

## TagConsistencyTest (codes 4300–4320)

### Name consistency

| Code | Title |
|------|-------|
| 4300 | `[ohm] Missing tag - name=*; unfixable, please review` |
| 4301 | `[ohm] Name warning - parentheses in name; unfixable, please review` |
| 4320 | `[ohm] Name warning - "historic" in name; unfixable, please review if this is date appropriate` |

**4300 trigger:** Feature has language-variant name keys (e.g. `name:en`) but no plain `name` key. **Skipped on `type=route` relations** — routes are conventionally identified by `ref` (route number / designation), so name-family-only routes are legitimate.  
**4300 description:** _Feature has name-family keys ({key}, etc.) but no plain 'name' key. Please add a canonical name._

**4301 trigger:** A `name` key contains parentheses **and** the parenthesised content includes a year-like number (3-4 consecutive digits). The rule is narrowed to date-bearing parens — `(Springfield)` or `(north section)` no longer fire; `(1880-1922)` and `(c. 1900)` do.  
**4301 description:** _{key}={value}: dates in parentheses are discouraged in names; move the date to start_date / end_date instead._

**4300 example (fires):**  
Trigger: way with `name:en=Empire State Building`, `name:fr=Empire State Building`, no plain `name`.  
Suggested manual fix: add `name=Empire State Building` (or whichever language is canonical for the location).

**4300 example (does not fire):**  
A relation with `type=route`, `route=bus`, `name:en=Pacific Coast Highway`, `ref=1`. Routes can rely on `ref` for canonical identity.

**4301 example (fires):**  
Trigger: `name=Old Town Hall (1880-1922)`  
Suggested manual fix: change to `name=Old Town Hall`; encode dates in `start_date`/`end_date` instead.

**4301 example (does not fire):**  
`name=City Park (Springfield)` — parenthesised disambiguator with no year-like content; left alone.

**4320 trigger:** Any name-family value contains the substring "historic" (case-insensitive). Matches `Historic`, `historical`, `Prehistoric`, `Ahistorical`, etc. — any historicizing frame, however constructed. The reasoning: "historic" framing reflects a present-day vantage; in OHM's time-aware data model, the entity at the time it existed wouldn't have called itself "historic". The Forum in Rome was just a Forum, not "historic", in 50 BCE.  
**4320 description:** _{key}={value}: "historic" in a name often reflects a present-day perspective. In OHM, confirm the entity was actually called this at the time it existed._

**4320 example (fires):**  
`name=Historic Town Hall`, `name=Historical Society`, `name=Prehistoric Cave` — all trip the rule. Confirm whether the actual entity at its time was so named, or whether the qualifier is being added retrospectively.

---

### Missing attribution

| Code | Title |
|------|-------|
| 4302 | `[ohm] Missing tag - wikidata; unfixable, please review` |
| 4303 | `[ohm] Missing tag - source; named feature without source; unfixable, please review` |

**4302 trigger:** Named feature has no `wikidata` tag **and** carries a notability signal: any `wikipedia=*`, `historic=*`, `boundary=administrative`, or a notable value of `place` (city/town/village/hamlet/suburb/neighbourhood/county/state/country/region/island/archipelago/continent), `tourism` (museum/attraction/monument/artwork/gallery), `amenity` (place_of_worship/university/courthouse/townhall/library/theatre/hospital/school), `building` (castle/cathedral/church/chapel/mosque/synagogue/temple/palace), or `military` (castle/fort/barracks). Relations always count.

**4302 exception:** Ways with `maritime=yes` that are members of a `type=boundary` relation are skipped — they're segments of a larger boundary entity and don't have their own Wikidata identity. The parent boundary relation still fires the rule.

**4302 fix:** If `wikipedia=*` is present, an autofix is offered that resolves the QID via the Wikidata API (`wbgetentities` against `<lang>wiki` site title) at fix-click time. If the lookup fails (network error, missing article, no QID in response), the fix is a silent no-op. No autofix is offered when `wikipedia=*` is absent.

**4302 description:** _This named feature has no 'wikidata' tag. Wikidata is the preferred identifier for cross-referencing._

**4303 trigger:** Named feature has no `source*` tag of any kind.  
**4303 description:** _This named feature has no 'source' tag. Please document the provenance of this feature._

**4302 example (no autofix):**  
Trigger: `name=Eiffel Tower`, `tourism=attraction`, no `wikidata`, no `wikipedia`.  
Suggested manual fix: add `wikidata=Q243`.

**4302 example (with autofix):**  
Trigger: `name=Eiffel Tower`, `wikipedia=en:Eiffel Tower`, no `wikidata`.  
Click Fix → plugin queries `https://www.wikidata.org/w/api.php?action=wbgetentities&sites=enwiki&titles=Eiffel%20Tower&props=info&format=json`, extracts `Q243`, adds `wikidata=Q243`.

**4302 example (does not fire):**  
`name=Ordinary Building`, `building=residential`, no `wikidata`. No notability signal; rule is silent.

**4303 example:**  
Trigger: `name=Old Mill` with no `source*` tags.  
Suggested manual fix: add `source=https://www.usgs.gov/...` or `source:name=USGS topo 1925`.

---

### Suspicious source values

| Code | Title |
|------|-------|
| 4304 | `[ohm] Suspicious source - source=wikipedia; unfixable, please review` |
| 4305 | `[ohm] Suspicious source - source=wikidata; unfixable, please review` |

**4304/4305 trigger:** `source` (or numbered variant) is set to `wikipedia` or `wikidata` — not valid sources for geometry.  
**Description:** _{key}={value}: Wikipedia/Wikidata is not a reasonable source for geometry claims. Please link to an actual map, image, or survey._

**4304/4305 example:**  
Trigger: `source=wikipedia` (or `source=wikidata`).  
Suggested manual fix: replace with a primary source — a map URL, aerial imagery, or survey reference. Use `:source` keys (e.g. `name:source=wikipedia`) for *attribute* sourcing, not geometry.

---

### Source URL format

| Code | Title |
|------|-------|
| 4306 | `[ohm] Source optimization - move non-URL source tags to source:name` |
| 4307 | `[ohm] Source optimization - repair URL missing 'http[s]://'` |
| 4310 | `[ohm] Source optimization - source[:#]:name is present, but source[:#] is not; please review` |

**4306 trigger:** `source` (or numbered variant) contains a non-URL text string.  
**4306 fix:** Moves value to `source:name`, leaves `source` blank for a URL.  
**4306 description:** _{key}={value} is not a URL. Move to {source:name} and leave {key} blank for a URL?_

**4307 trigger:** `source` value looks like a URL but is missing `http://` or `https://`.  
**4307 fix:** Prepends `https://`.  
**4307 description:** _{key}={value} looks like a URL missing the scheme. Prepend 'https://'?_

**4310 trigger:** `source:name` (or `source:N:name`) is present but the corresponding `source` (or `source:N`) URL is absent.  
**4310 fix:** None — prompts user to add the URL.  
**4310 description:** _{key}={value} is set, but {source_key} is empty. Would you like to add a URL for the source?_

**4306 example:**  
Before: `source=USGS topo map 1925`  
After autofix: `source=` (blank), `source:name=USGS topo map 1925`. The user can later fill in a URL for `source`.

**4307 example:**  
Before: `source=usgs.gov/maps/topo1925`  
After autofix: `source=https://usgs.gov/maps/topo1925`.

**4310 example:**  
Trigger: `source:name=USGS topo 1925`, no `source` URL.  
Suggested manual fix: add `source=https://...` pointing at the actual scanned map.

---

### source vs. source:url consolidation

| Code | Title |
|------|-------|
| 4311 | `[ohm] Source keys with duplicate values - source=source:url; autofix by deleting source:url` |
| 4312 | `[ohm] Source mismatch - no source tag and valid source:url tag; autofix by moving *:url value to source=` |
| 4312 | `[ohm] Source mismatch - source and source:url are different URLs; autofix by moving source:url to source:N` |
| 4313 | `[ohm] Source optimization - source contains a name and source:url contains a URL; autofix by swapping these` |

**4311 trigger:** `source` and `source:url` hold the same value.  
**4311 fix:** Deletes `source:url`.

**4312 trigger (no source):** `source:url` is set but `source` is absent.  
**4312 fix:** Moves `source:url` value to `source`.  
**4312 description:** _source:url={url} should live in source._

**4312 trigger (both URLs):** Both `source` and `source:url` are URLs but hold different values.  
**4312 fix:** Moves `source:url` to the next available `source:N` slot (N = max existing + 1). Leaves `source` untouched.  
**4312 description:** _source={url1} and source:url={url2} are different URLs. Move source:url to the next numbered source key?_

**4313 trigger:** `source` holds a name string while `source:url` holds a URL — they are in the wrong keys.  
**4313 fix:** Swaps values: URL → `source`, name → `source:name`.  
**4313 description:** _Consolidate: source:url → source, source → source:name?_

**4311 example:**  
Before: `source=https://example.org/map`, `source:url=https://example.org/map`  
After autofix: `source:url` deleted; `source` retained.

**4312 example (no source):**  
Before: `source:url=https://example.org/map`, no `source`  
After autofix: `source=https://example.org/map`, `source:url` deleted.

**4312 example (different URLs):**  
Before: `source=https://a.example/map`, `source:url=https://b.example/map`  
After autofix: `source=https://a.example/map` (unchanged), `source:1=https://b.example/map`, `source:url` deleted.

**4313 example:**  
Before: `source=USGS topo 1925`, `source:url=https://usgs.gov/topo1925`  
After autofix: `source=https://usgs.gov/topo1925`, `source:name=USGS topo 1925`, `source:url` deleted.

---

### Semicolon-separated source values

| Code | Title |
|------|-------|
| 4314 | `[ohm] Source optimization - source contains 1 URL & 1 text string; autofix by splitting into source & source:name` |
| 4315 | `[ohm] Source optimization - source contains multiple URLs; autofix by enumerating source:# keys` |
| 4316 | `[ohm] Source optimization - source contains multiple text strings; autofix by enumerating source:#:name keys` |
| 4317 | `[ohm] Source mismatch - source contains multiple values of different types; unfixable, please review` |

**4314 trigger:** `source` contains two semicolon-separated values: one URL and one text string.  
**4314 fix:** Splits into `source` (URL) and `source:name` (text).  
**4314 description:** _{key}={value}: move URL to source and text to source:name?_

**4315 trigger:** `source` contains two or more semicolon-separated URLs.  
**4315 fix:** Enumerates into `source`, `source:1`, `source:2`, …  
**4315 description:** _{key}={value}: enumerate into source, source:1, source:2, …?_

**4316 trigger:** `source` contains two or more semicolon-separated non-URL strings.  
**4316 fix:** Enumerates into `source:name`, `source:1:name`, …  
**4316 description:** _{key}={value}: enumerate into source:name, source:1:name, …?_

**4317 trigger:** `source` contains 3+ semicolon-separated items mixing URLs and text.  
**4317 fix:** None — too ambiguous to autofix.  
**4317 description:** _{key}={value}: 3 or more items mixing URLs and text. Manual review needed — split into source, source:N, source:name, source:N:name as appropriate._

**4314 example:**  
Before: `source=https://usgs.gov/topo1925; USGS topo 1925`  
After autofix: `source=https://usgs.gov/topo1925`, `source:name=USGS topo 1925`.

**4315 example:**  
Before: `source=https://a.example; https://b.example`  
After autofix: `source=https://a.example`, `source:1=https://b.example`.

**4316 example:**  
Before: `source=USGS topo 1925; Sanborn 1933`  
After autofix: `source:name=USGS topo 1925`, `source:1:name=Sanborn 1933`.

**4317 example:**  
Trigger: `source=https://a.example; USGS topo; https://b.example` (mixed types, 3+ items).  
Suggested manual fix: split by hand into `source`, `source:1`, `source:name`, `source:N:name` slots as appropriate.

---

### Attribute-source references

| Code | Title |
|------|-------|
| 4308 | `[ohm] Missing tag - wikipedia, referenced in source keys; unfixable, please review and add tag` |
| 4309 | `[ohm] Missing tag - wikidata, referenced in source keys; unfixable, please review and add tag` |

**4308 trigger:** A `*:source` tag references Wikipedia but no `wikipedia` tag exists on the feature.  
**4308 description:** _{key}={value}: please add an appropriate 'wikipedia' tag._

**4309 trigger:** A `*:source` tag references Wikidata but no `wikidata` tag exists on the feature.  
**4309 description:** _{key}={value}: please add an appropriate 'wikidata' tag._

**4308 example:**  
Trigger: `name:source=wikipedia` but no `wikipedia=*` on the feature.  
Suggested manual fix: add `wikipedia=en:Some Article Title`.

**4309 example:**  
Trigger: `name:source=wikidata` but no `wikidata=*` on the feature.  
Suggested manual fix: add `wikidata=Q12345`.

---

### Suspicious tag — historic

| Code | Title |
|------|-------|
| 4319 | `[ohm] Suspicious tag - historic; unfixable, should only be used once an object actually is historic` |

**4319 trigger:** Any feature carrying a `historic=*` tag (any value). OHM convention is that `historic=*` applies to entities that have actually passed into history; using it on a still-current feature is premature.

**4319 description:** _historic={value}: confirm the entity has actually passed into history before applying this tag._

**4319 example:**  
Trigger: a node tagged `historic=castle` representing a castle that is still standing as a tourist attraction. The warning prompts the editor to consider whether the tag is appropriate or whether it should be removed (or paired with an `end_date` indicating the historical scope).

---

### Suspicious member — role=label

| Code | Title |
|------|-------|
| 4318 | `[ohm] Suspicious member - role=label; unfixable, please review` |

**4318 trigger:** A relation has at least one member with `role=label`. OHM renderers automatically generate label points server-side, so editor-supplied labels are usually unnecessary. The warning fires once per relation regardless of how many `role=label` members it has.

**4318 description:** _OHM servers automatically generate label points; only use these when necessary. To verify, download all parent relations of this label object (File ▸ Download parent relations / ways). role=label members on this relation: {ids}._

**4318 example:**  
Trigger: a `boundary=administrative` relation contains `<member type="node" role="label" ref="123"/>`.  
Suggested manual fix: confirm the label object is genuinely needed; if it is shared across multiple parent relations, download those parents (Ctrl+Alt+Down in JOSM) before editing.

---

## Retired codes

| Code | Reason |
|------|--------|
| 4209 | Merged into `CODE_EDTF_INVALID_NO_BASE` (4208) — invalid `:edtf` with base now fires 4208 alone |
| 4219 | Retired — negative astronomical years are legitimate OHM notation; the rule's false-positive rate outweighed its signal value (forum feedback 2026-04-21) |
| 4227 | Rule D2 now fires the unified "Invalid *_date:edtf" fixable/unfixable messages (4228) |
| 4229 | Merged with `CODE_EDTF_INVALID_NO_BASE` (4208) under the unified unfixable message |
| 4230 | Retired as redundant — invalid `:edtf` with a base tag now fires only the unified `:edtf` message (4208/4228) |
