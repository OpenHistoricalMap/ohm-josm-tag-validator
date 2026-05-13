# OHM Tags Validator — Message Reference

Most messages use `WARNING` severity. The following codes are `ERROR`
severity (malformed or structurally-invalid data that consumers cannot
interpret, plus the missing-`wikidata` rule):
**4201**, **4202**, **4207**, **4208**, **4217**, **4218**, **4221**,
**4222**, **4228**, **4231**, **4233**, **4234**, **4238**, **4248**,
**4249**, **4302**.

Titles follow the pattern:
`[ohm] <Category> - <what>; <fixable|unfixable>, please review [<action>]`

References to "rules" below are defined in the javadoc in DateTagTest.java.

---

## DateTagTest (codes 4200–4258)

**`*_date:edtf` is never written equal to `*_date`.** When an autofix
would set `start_date=1900` and `start_date:edtf=1900`, the validator
suppresses the redundant `:edtf` write (and deletes any existing one).
`:edtf` only carries information beyond the base — ranges (`1900/1950`),
qualifiers (`1900~`), unspecified-digit forms (`19XX`), open-ended
bounds (`/1900`). Plain ISO values live in the base tag alone. In
description text, `:edtf=(absent)` indicates this suppression.

### Suspicious date — missing start_date

| Code | Title |
|------|-------|
| 4200 | `[ohm] Suspicious date - man-made object w/out start_date; unfixable, please review & add a reasonable start_date:edtf range & explain the reasons in start_date:source` |

**Trigger:** Feature carries at least one tag from a curated **man-made allowlist** but has no `start_date`. The allowlist (see `DateTagTest.MANMADE_KEYS` and `MANMADE_DENYLIST`) covers buildings, highways, railways, amenities, leisure, barriers, addressed features, and other tags that mark a primitive as built / established with a discrete creation date.  
**Fix:** None.  
**Description:** _(empty — the actionable advice now lives in the title itself.)_

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

**4220 trigger:** Value ends with a trailing hyphen (e.g., `2021-`, `2021-03-`), which is ambiguous between a typo, an incomplete input, and an open-ended range. Fires on `*_date` base values AND on any top-level `*_date:edtf` value matching the same shape. Suppressed on base when `*_date:edtf` or `*_date:raw` is already set (the editor has been there already).  
**4220 description:** _{key}={value}: could be a typo: {suggestion}; an incomplete input; or an open-ended range {suggestion}/. Manual review needed._

**Example:**  
Trigger: `start_date=2021-`  
Suggested manual fix: choose one of `start_date=2021` (typo), `start_date=2021-03-15` (incomplete input), or `start_date:edtf=2021/` (open-ended range).

---

### Ambiguous YYYY-MM..MM tail

| Code | Title |
|------|-------|
| 4253 | `[ohm] Ambiguous date - YYYY-MM..MM tail could be month or year; unfixable, please review` |

**Trigger:** `*_date` or `*_date:edtf` (in fact any top-level `:edtf` key) matches `^(\d{4})-(0[1-9]|1[0-2])\.\.(0[1-9]|1[0-2])$` — a year-month with a `..` tail that's *also* a valid month value (01-12). Two readings, both legitimate in OHM tagging and supported elsewhere by separate autofix rules:

- **tail-as-month sharing the year prefix** → `YYYY-MM/YYYY-tail` (e.g. `1904-05..08` → "May to August 1904").
- **tail-as-2-digit-year sharing the century prefix** → `YYYY-MM/{century}{tail}` (e.g. `1904-05..08` → "May 1904 to 1908"), parallel to the existing `YYYY..YY` abbreviated-tail-range rule (Path 0b').

There's no signal in the value alone to pick the right reading. `DateNormalizer.toEdtf` refuses to normalize this shape (the legacy generic-RANGE-branch behavior produced a backwards interval like `1904-05/0008` by interpreting `08` as year 8 CE — neither of the user-intended readings), so this rule fires the unfixable warning instead.

**Fix:** None. Manual review required: rewrite the value explicitly as `YYYY-MM/YYYY-MM` (month interpretation) or `YYYY-MM/YYYY` (year interpretation).

**Description:** _{key}={value}: tail "{tail}" could be a month sharing the year prefix ({YYYY-MM/YYYY-tail}) or a 2-digit year sharing the century prefix ({YYYY-MM/expanded-year}). Manual review needed: rewrite the value explicitly._

**Coverage:** The rule restricts the tail to 01-12 — i.e. only the indeterminate cases. Tails outside that range (e.g. `1904-05..13`) are unambiguously year-only but produce a backwards interval; left to existing normalize behavior, not in scope here.

**Example:**  
Trigger: `end_date:edtf=1904-05..08`.  
Suggested manual fix: rewrite as `end_date:edtf=1904-05/1904-08` for "May to August 1904", or `end_date:edtf=1904-05/1908` for "May 1904 to 1908".

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

### Compact "cYYYY" shorthand

| Code | Title |
|------|-------|
| 4202 | `[ohm] Invalid date - *_date; fixable, please review` |
| 4241 | `[ohm] Ambiguous date - cYY (year or century unclear); unfixable, please review` |

OHM contributors sometimes write a compact `cYYYY` form for "circa YYYY" (e.g. `start_date=c1920`). The validator handles five magnitude/sign cases separately:

- **`YYYY >= 100`** or **`YYYY <= -100`** — unambiguous "circa year". The existing 4202 normalization rule rewrites it via `DateNormalizer` to `~YYYY`, producing the standard triple.
- **`22 <= YYYY <= 99`** — ambiguous: could be "circa year YY" or "the YYth century". Fires the new **4241** unfixable warning. Manual review required to pick one.
- **`1 <= YYYY <= 21`** — highly probable positive century shorthand. Falls through to the existing `CN` century pipeline (so `c19` is treated the same as `1800s`).
- **`-99 <= YYYY <= -1`** — negative magnitudes 1–99 (e.g. `c-19`, `c-50`) all fire **4241**. The existing CN pipeline silently strips the sign and produces a CE century, which is the wrong direction; firing unfixable forces the editor to write the date out explicitly.
- **`YYYY == 0`** (`c0`, `c-0`, `c0bc`) — degenerate. Year zero and century zero are both nonsense in OHM's astronomical-year convention. Fires **4241**.

The `c` prefix is case-insensitive and accepts a `bc` / `BCE` suffix. BCE flips the sign of `YYYY` directly with no N-1 offset (so `c1920bc` becomes `~-1920`, not `~-1919`) — the historian's astronomical-year convention is too persnickety for typical OHM editing.

**4202 example (cYYYY band, abs >= 100):**  
Before: `start_date=c1920`  
After autofix: `start_date=1920`, `start_date:edtf=1920~`, `start_date:raw=c1920`.

**4202 example (cYYYY with BCE suffix):**  
Before: `start_date=c1920bc`  
After autofix: `start_date=-1920`, `start_date:edtf=-1920~`, `start_date:raw=c1920bc`.

**4202 — packed-date typo (`YYYY-MMDD` or `YYYY-MDD`):** Common typo where the user wrote a packed date with the month-day hyphen missing. The validator autofixes when:
- Input matches `^YYYY-MMDD$` AND `YYYY > 1200` AND the implied `MM-DD` is a real calendar date for that year (leap-year-aware).
- Input matches `^YYYY-MDD$` (3-digit suffix; leading zero on the month) AND `YYYY > 1000` AND the implied `M-DD` is a real calendar date.

Wrap to standard ISO: `start_date=1875-1031` → `start_date=1875-10-31`. The 1200/1000 thresholds avoid silently rewriting old or borderline-ambiguous inputs.

When autofix is held back, code **4244** fires the unfixable variant whenever the input still looks like a packed-date attempt. The "looks like an attempt" heuristic fires in either of two sub-cases:
- The implied MM-DD (or M-DD) **is** a real calendar date but the year is below the autofix threshold — e.g. `0500-1031` (May 31 of year 500), `0900-731` (July 31 of year 900).
- The implied MM-DD (or M-DD) is **not** a real calendar date AND the year is the larger of the two halves — e.g. `1875-1131` (Nov 31 doesn't exist), `1500-732` (July 32 doesn't exist).

Both 4-digit (`YYYY-MMDD`) and 3-digit (`YYYY-MDD`) suffixes get the same treatment. Inputs where the implied MM-DD is invalid AND the year is the smaller half (e.g. `0001-2024`) deliberately fall through — they're more plausibly some other shape entirely.

**4202 example (packed-date autofix):**  
Before: `end_date=1930-0630`.  
After autofix: `end_date=1930-06-30`.

**4244 example:**  
Trigger: `start_date=1875-1131` (Nov 31 doesn't exist).  
Suggested manual fix: rewrite to a real `start_date=YYYY-MM-DD` or trim to `start_date=YYYY` if the day-precision was unintentional.

**4241 example:**  
Trigger: `start_date=c50`.  
Suggested manual fix: rewrite as `~50` if "circa year 50" was intended, or as a century form if "century 50" was intended.

---

### Suspicious date — year-boundary

| Code | Title |
|------|-------|
| 4212 | `[ohm] Suspicious date - 01-01 start_date; unfixable, please review` |
| 4213 | `[ohm] Suspicious date - 12-31 end_date; unfixable, please review` |
| 4214 | `[ohm] Suspicious date - 12-31 start_date; unfixable, please review` |
| 4214 | `[ohm] Suspicious date - 01-01 end_date; unfixable, please review` |

**4212/4213 trigger:** `start_date=YYYY-01-01` or `end_date=YYYY-12-31` — at the year boundary that matches the role. Under OHM's conservative year-only convention, `start_date=YYYY` already starts at Jan 1 and `end_date=YYYY` already ends Dec 31, so the explicit form is often functionally redundant. But Jan 1 / Dec 31 are also legitimate real dates often enough (laws taking effect, treaties signed, terms ending, fiscal year boundaries) that the autofix can't safely run on its own.  
**4212/4213 fix:** None. As of v0.4.0 these are unfixable; even an opt-in autofix proved too easy to apply by mistake when batch-fixing. If the exact day is unknown, manually trim to the bare year.  
**4212/4213 description:** _{key}={value}: Jan 1 / Dec 31 is suspicious as start_date / end_date — often false precision but also a legitimate real date. If the exact day is unknown, manually trim to {key}={year}._

**4214 trigger:** `start_date=YYYY-12-31` or `end_date=YYYY-01-01` — at the *opposite* year boundary for the role. Could be a typo (next/previous year intended) or a legitimate event-day boundary (e.g. a treaty signed Dec 31). Ambiguous; manual review only.  
**4214 fix:** None.  
**4214 description:** _{key}={value}: end-of-year used as start_date / start-of-year used as end_date. If the exact day is unknown, manually change to {key}={year} (the year this date falls in) or {key}={shifted} (next/previous year, if a typo)._

**4212/4213 example:**  
Trigger: `start_date=1875-01-01`.  
Suggested manual fix: if the start was genuinely on Jan 1, 1875 (e.g. a law taking effect that day), leave alone; otherwise change to `start_date=1875` (the year only, dropping the false-precision day).

**4214 example:**  
Trigger: `start_date=1875-12-31`.  
Suggested manual fix: if the start was genuinely on Dec 31, 1875 (e.g. a treaty signed that day), leave alone; otherwise change to `start_date=1875` (the year this date falls in) or `start_date=1876` (the year the entity started, if the original was an off-by-one typo).

---

### Suspicious date — ordering and equality (Rules B and C)

| Code | Title |
|------|-------|
| 4215 | `[ohm] Suspicious date - start_date > end_date; autofix by swapping these` |
| 4224 | `[ohm] Suspicious date - start_date = end_date; unfixable, please review` |
| 4225 | `[ohm] Suspicious date - start_date = end_date with backslash pattern; autofix by deleting start_date:edtf` |

**4215 trigger:** `start_date` parses to a date after `end_date`.  
**4215 fix:** Swaps `start_date` and `end_date`.  
**4215 description:** _start_date={start}, end_date={end}. Swap?_

**4224 trigger (Rule B):** `start_date` and `end_date` are equal — valid only for a feature that existed for a single day, month, or year (depending on date precision). No backslash pattern, non-bot last editor.  
**4224 fix:** None.  
**4224 description:** _did this feature exist for just 1 day/month/year?_

**4225 trigger (Rule C):** `start_date:edtf` matches the tagcleanupbot backslash signature (`\<end_date_value>`) AND `start_date` equals `end_date`, but the last editor was not the bot — i.e., a human edit landed on top of a bot-introduced anomaly.  
**4225 fix:** Deletes `start_date:edtf`.  
**4225 description:** _The start_date and end_date values are equal and should only be that way for an object that existed only for a day. Delete start_date:edtf?_

**4215 example:**  
Before: `start_date=1950`, `end_date=1900`  
After autofix: `start_date=1900`, `end_date=1950`.

**4224 example:**  
Trigger: `start_date=1969-07-20`, `end_date=1969-07-20`.  
Suggested manual fix: if this is a single-day event (Apollo 11 landing) leave it; otherwise correct one side.

**4225 example:**  
Before: `start_date=1900`, `start_date:edtf=\1900`, `end_date=1900` (last editor was a human, not the bot)  
After autofix: `start_date:edtf` removed; `start_date=1900` and `end_date=1900` retained.

---

### Suspicious date — future date

| Code | Title |
|------|-------|
| 4216 | `[ohm] Suspicious date - end_date >10 year into the future; autofix by deleting the key` |
| 4256 | `[ohm] Suspicious date - start_date >10 year into the future; unfixable, please review` |

**Trigger:** A 4-digit date tag value is more than 10 years beyond today.  
**Fix (end_date, 4216):** Deletes the offending key. Far-future end_date is almost always a stale planned-demolition or a typo.  
**Fix (start_date, 4256):** Unfixable — deletion is not safe for a far-future start_date (it may legitimately describe a planned future construction whose details belong elsewhere). Manual review required.

**4216 description:** _end_date={value} is more than ten years in the future. Delete the key?_

**4256 description:** _start_date={value} is more than ten years in the future. Unfixable; please review whether this represents a planned future entity or is a typo._

---

### Invalid date — 5+ digit number

| Code | Title |
|------|-------|
| 4246 | `[ohm] Invalid date - 5+ digit number; unfixable, please review` |

**Trigger:** A `*_date` value contains a run of five or more consecutive digits (anywhere in the string) and does not parse as valid EDTF. Catches typos like `20251`, `12345-06`, `1997-04..1997-06006`, and Wikidata Q-numbers mistakenly placed in date tags (`Q1438579`). Legitimate EDTF long-year forms (e.g. `Y20251`, `Y-20251`) and any other syntax the upstream `edtf-java` library accepts are exempt.  
**Fix:** None — we can't tell whether the intent was `2025`, `12025`, or something else, so the editor must decide. When this rule fires, the rest of the per-key date checks are suppressed for that key (they would otherwise emit a generic "cannot be read" warning that obscures the specific typo diagnosis).  
**Description:** _{key}={value} contains a run of 5 or more digits and is not valid EDTF. Review and correct manually._

**Example:**  
`start_date=20251` → flagged; editor decides whether the intent was `2025` or `12025`.  
`end_date=Q1438579` → flagged; editor moves the QID to `wikidata=*`.

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

### Invalid date — day 31 in a 30-day month

| Code | Title |
|------|-------|
| 4249 | `[ohm] Invalid date - day 31 in a 30-day month; autofix day to 30` |

**Trigger:** A `*_date` value with day `31` and a month that has 30 days (Apr / Jun / Sep / Nov). The user's most likely error is forgetting the month is 30-day; the rest of the date is well-formed.  
**Fix:** Clamps the day to `30`.  
**Description:** _{key}={value}: month {MM} has 30 days, not 31. Change to {fixed}?_

**Why only Apr/Jun/Sep/Nov?** February cases (Feb 30, Feb 29 on non-leap years) are deliberately not autofixed here — too many possible interpretations (28 vs 29 vs strip-to-month vs the month was wrong). Feb 29 already has its own `4247` strip-to-year autofix, and Feb 30 stays in `4222` for manual review.

**Example:**  
Before: `start_date=1900-06-31`  
After autofix: `start_date=1900-06-30`.

---

### Suspicious date — 02/29 placeholder

| Code | Title |
|------|-------|
| 4247 | `[ohm] Suspicious date - 02/29; autofix by stripping to year` |

**Trigger:** A `*_date` value with month `02` and day `29`, regardless of year. Almost nothing in history actually happened on Feb 29; in OHM the date is widely used as a placeholder for approximate or made-up values, and the validator nudges editors away from it.  
**Fix:** Strips the value to `YYYY` (drops the suspect month/day, preserving the year).  
**Description:** _{key}={value}: Feb 29 is widely used in OHM as a placeholder for approximate or made-up dates. Strip to {YYYY}?_

**Behavior alongside 4222:** This rule fires on every Feb 29. When the year is non-leap, **4222** also fires (calendar-invalid, no fix); both warnings appear and the editor can either accept this rule's autofix or hand-edit per 4222.

**Example (leap year):**  
Before: `start_date=2024-02-29`  
After autofix: `start_date=2024`. Only 4247 fires (2024-02-29 is a real calendar date).

**Example (non-leap year):**  
Before: `start_date=1900-02-29`  
After autofix: `start_date=1900`. Both 4222 (calendar-invalid, no fix) and 4247 (suspicious, autofix) fire.

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

**4202 — abbreviated-tail range (`YYYY/YY`):** OHM contributors sometimes write a short form for ranges that share a century-decade prefix, e.g. `start_date=1716/17` for "1716/1717" or `end_date=1850/52` for "1850/1852". The validator expands the 2-digit suffix and writes a full triple. For `start_date`, the base is the lower year; for `end_date`, the upper year. Pre-fix, edtf-java accepted the short form as valid EDTF and produced gibberish base values (e.g. `end_date=1850/52` came out as `end_date=5299`). Wrap cases like `1899/01` (where the suffix would resolve to a year less than the start) deliberately fall through and are not autofixed.

Before: `end_date=1850/52`  
After autofix: `end_date=1852`, `end_date:edtf=1850/1852`, `end_date:raw=1850/52`.

**4202 — abbreviated-tail range (`YYYY..YY`):** Same expansion as `YYYY/YY` above but using `..` as the separator (e.g. `start_date=1944..48` for "1944/1948"). The 2-digit suffix replaces the last two digits of the 4-digit start year to form the end year. Must be intercepted before the normalizer, which would otherwise misread the 2-digit tail as a year in its own right (e.g. "48" → year 48 CE, producing "1944/0048").

Before: `start_date=1944..48`  
After autofix: `start_date=1944`, `start_date:edtf=1944/1948`, `start_date:raw=1944..48`.

**4201 -- abbreviated-tail range (`YYYY..YY`) wraps a century boundary:** When the 2-digit suffix resolves to a year before the start (e.g. `start_date=1985..05` → naive end = 1905 < 1985), the intended century is ambiguous (1905? 2005?). Flagged unfixable; contributor must write the full form (`1985/2005`).

**4202 — implausibly-ancient leading zeros (`0000..YYYY`):** Inputs like `start_date=0000..1850` or `end_date=00..1900` are common when a contributor wanted to express "no known start" but wrote a placeholder year zero. When the upper bound `YYYY > 400` (clearly post-classical), the validator collapses to the open-start EDTF form `/YYYY` and uses `YYYY` as the base for both `start_date` and `end_date`. Below the threshold the input could be a real ancient range; falls through.

Before: `start_date=0000..1850`  
After autofix: `start_date=1850`, `start_date:edtf=/1850`, `start_date:raw=0000..1850`.

**4202 — `before:` / `by:` / `as of:` / `after:` / `during:` shorthand:** OHM contributors often write open-ended bounds with a natural-language prefix (also accepted with a space separator: `before 1900`, `by 1844`, etc.):

- `before X`, `by X`, `as of X` → `/X` (open-ended interval ending at X)
- `after X` → `X/` (open-ended interval starting at X)
- `during X` → `X` (the prefix adds no information; just unwrap)

The inner value `X` is normalized in three steps:

- **Strict ISO** (`YYYY`, `YYYY-MM`, `YYYY-MM-DD`): preserved at full precision.
- **Dash-separated `DD-MM-YYYY` or `MM-DD-YYYY`** (e.g. `01-01-1882`): coarsened to year only — day/month order is ambiguous and a `before`/`after` bound is fuzzy enough that losing the day makes no material difference. (Slash-separated `01/01/1882` is normalized to ISO `1882-01-01` earlier in `preprocess`, so it hits the strict-ISO path.)
- **Recursive normalization** otherwise: the inner is fed back through `toEdtf` so OHM shorthand on the bound side normalizes too. Handles `before C12` → `/11XX`, `by c1900` → `/1900~`, `as of 1850s` → `/185X`, etc. Only accepted if the recursive result is itself valid EDTF, to avoid feeding garbage into the slash interval.

Inner values that match nothing (`before:gibberish`) fall through and fire 4201.

Before: `start_date=before:01-01-1882`  
After autofix: `start_date=1882`, `start_date:edtf=/1882`, `start_date:raw=before:01-01-1882`.

Before: `end_date=after:1999-02-03`  
After autofix: `end_date=1999-02-03`, `end_date:edtf=1999-02-03/`, `end_date:raw=after:1999-02-03`.

Before: `start_date=before C12`  
After autofix: `start_date=1100`, `start_date:edtf=/11XX`, `start_date:raw=before C12`.

Before: `end_date=during 1975`  
After autofix: `end_date=1975` (no `:edtf`, no `:raw` — `during X` collapses to plain `X`).

**4202 — qualifier on decade / century:** Decade and century shorthand accept a `~`, `?`, or `%` qualifier in either prefix or suffix position (`~1960s`, `670s~`, `~C3`, `C19~`). Plain (unqualified) inputs emit the EDTF unspecified-digit form (`196X`, `18XX`); qualified inputs emit an explicit slash range with the qualifier on each bound (`~1960s` → `1960~/1969~`, `C19~` → `1800~/1899~`). Qualifiers can't attach to X-form years in EDTF (`196X~` is rejected by the parser), so the explicit-bounds form is the only canonical option when a qualifier is present.

**4202 — `early` / `mid` / `late` partial year or month (case-insensitive):** The modifier splits a year or a month into non-overlapping thirds, emitted as a slash interval at the next-finer precision. Year-thirds use 4-month buckets; month-thirds use 10-day buckets (with a 9/10/9-or-10 split on February so all three thirds fit within 28/29 days). All three buckets are mutually exclusive — `mid` does not share an endpoint with `early` or `late`.

- `early YYYY` → `YYYY-01/YYYY-04`; `mid YYYY` → `YYYY-05/YYYY-08`; `late YYYY` → `YYYY-09/YYYY-12`.
- `early YYYY-MM` → `YYYY-MM-01/YYYY-MM-10`; `mid YYYY-MM` → `YYYY-MM-11/YYYY-MM-20`; `late YYYY-MM` → `YYYY-MM-21/YYYY-MM-{30|31}` (`30` for Apr/Jun/Sep/Nov, `31` otherwise).
- **February special case**: `early YYYY-02` → `YYYY-02-01/YYYY-02-09`; `mid YYYY-02` → `YYYY-02-10/YYYY-02-19`; `late YYYY-02` → `YYYY-02-20/YYYY-02-{28|29}`, with proleptic-Gregorian leap-year detection (`29` on years divisible by 4, except century years not divisible by 400; applied to the astronomical year for BCE input).

**Case-insensitive on the modifier.** `Early 1900`, `MID 1850s`, `EARLY C19` all match. The existing `early/mid/late YYYY0s` (decade) and `early/mid/late CN` (century) rules also became case-insensitive in this pass.

**BCE for THIRD_DECADE / THIRD_CENTURY (fixed in v0.7.2).** The BCE branch of the existing decade and century handlers previously had a `+1` shift on the rendered year that produced output one year too negative under the project's "round-hundreds" convention. Removed in v0.7.2. Examples after the fix:

- `early 850s BC` → `-0852~/-0850~` (BC 850-852)
- `mid 850s BC` → `-0856~/-0853~` (BC 853-856)
- `late 850s BC` → `-0859~/-0857~` (BC 857-859)
- `early C6 BC` → `-0529~/-0500~` (BC 500-529, matching the plain `C6 BC` → `-0599~/-0500~` round-hundreds convention)

The output magnitudes correspond to BC year numbers directly (the project's loose convention, where `-0500` is "read" as BC 500 by a human eyeballing the tag). EDTF Level 1 strict astronomical convention would put these one year more negative; the project deliberately diverges for human readability (see the comment block on the plain CN BC handler in `DateNormalizer`).

**BCE support.** Trailing `BC` / `BCE` is accepted on both the year and year-month forms. The year is converted to astronomical form via `astro = -(BC - 1)` so `1 BC` → `0000`, `100 BC` → `-0099`. The month/day buckets are the same regardless of sign — they describe the position within the named year/month, not direction in time. Examples: `early 100 BC` → `-0099-01/-0099-04`; `late 100 BC` → `-0099-09/-0099-12`; `early 100-05 BC` → `-0099-05-01/-0099-05-10`; `early 1 BC` → `0000-01/0000-04`.

**X-form decade with modifier.** `early|mid|late YYY[Y]X` is rewritten in preprocess to the equivalent `YYY[Y]0s` form and then normalized by the existing `THIRD_DECADE` path: `mid 197X` → `mid 1970s` → `1973~/1976~`. The X is accepted in either case (`MID 197x` works the same as `mid 197X`).

**Non-overlap fix for decade and century thirds.** The existing `THIRD_DECADE` and `THIRD_CENTURY` offset tables previously had a one-year overlap at each boundary (`early 1850s` ended at 1853, `mid 1850s` started at 1853; `mid C19` ended at 1870, `late C19` started at 1870). Updated to non-overlapping `3/4/3` (decade) and `30/40/30` (century) splits — `early 1850s` → `1850~/1852~`, `mid 1850s` → `1853~/1856~`, `late 1850s` → `1857~/1859~`; `early C19` → `1800~/1829~`, `mid C19` → `1830~/1869~`, `late C19` → `1870~/1899~`.

**Known limitation:** BCE on the existing `THIRD_DECADE` / `THIRD_CENTURY` paths has a long-standing off-by-2 bug (the BC-to-astronomical conversion increments instead of decrements). Not exercised by any regression fixture. Tracked separately; not in scope of this rule. BCE on the new year and year-month forms (this rule) is correct.

The early/mid/late century / decade forms (`late C1`, `mid 1850s`) accept an optional leading qualifier (`~late C1`) which is consumed for free — the output is already an explicit range with `~` on each bound (`0070~/0099~`).

**4202 — ordinal-century range:** Inputs of the form `<ordinal>[ - <ordinal>] Century [BC]`, where each side may carry an optional `early`/`mid`/`late` modifier and the trailing word `Century` applies to both halves. The two halves normalize as `CN` expressions and the bounds combine: `5th - mid 8th Century` → `0400/0770` (low of `04XX`, high of `0730~/0770~`).

**4202 — short-year range with qualifier:** Two 1- or 2-digit years separated by a hyphen, with an optional leading qualifier on the left bound (`~47-50` → `0047~/0050`, `47-50` → `0047/0050`). The qualifier applies to the left side only — the syntactic position of the `~` before the first year. Only fires when interpretation as a year-range is unambiguous: a qualifier is present, or the right side is `>12` (can't be a month). Otherwise falls through to year-month parsing (`5-10` → `0005-10`).

**4202 — hyphen-as-range with negative years:** Astronomical BCE notation works on either side of a hyphen-range: `-0800 - -0600` (preprocess strips the spaces around the hyphens, leaving `-0800--0600`) → `-0800/-0600`. Mixed signs work too (`-0800-1500` → `-0800/1500`).

**4202 — per-bound BCE markers in dotdot range:** `182 BC..174 BC` correctly distributes each side's BCE marker rather than corrupting the start as `"182 BC BC"`. Output uses the existing N-1 convention for individual years: `-0181/-0173`.

**4202 — junk-tail strip:** Open-ended slash forms with garbage tails — `1959/..~`, `1959/..`, `1959/.~`, `1959/~` — all collapse to `1959/`. Typically arise from incomplete edits.

**4202 / 4228 — single-bracket single-dot range:** Values matching `[<ISO date>.<ISO date>]` (single brackets, single-dot separator, each side a clean ISO shape `YYYY` / `YYYY-MM` / `YYYY-MM-DD`) are rewritten to `<inner>..<inner>` so the standard RANGE branch can normalize to a slash interval. The single dot is almost always a typo for `..` or `/`. Strict on each side being a 4-digit-year ISO date to avoid splitting non-range values that contain a `.`. Double-dot bracket forms (`[1900..1950]`, EDTF set notation) are EDTF that the parser accepts and are NOT matched. Examples:

- `[1900.1950]` → `1900/1950`
- `[1900-05.1950-08]` → `1900-05/1950-08`
- `[1900-05-15.1950-08-20]` → `1900-05-15/1950-08-20`

Surfaces as 4228 fixable on `*_date:edtf` keys; 4202 fixable on base `*_date` keys (autofix writes the full triple).

**4202 / 4228 — double-bracket dotdot range:** Values wrapped in `[[...]]` with a `..` separator inside are unwrapped so the standard RANGE branch can normalize the inner. Optional whitespace between the brackets and the inner is tolerated. Each side of the `..` is validated as a date by the recursive normalizer; if either side fails, the whole value falls through to the unfixable path. Examples:

- `[[1900..1950]]` → `1900/1950`
- `[[1900-05..1950-08]]` → `1900-05/1950-08`
- `[[ 1900..1950 ]]` → `1900/1950`

Surfaces as 4228 fixable on `*_date:edtf` keys (autofix to the slash form, original preserved in `:edtf:raw`); surfaces as 4202 fixable on base `*_date` keys (autofix writes the full triple).

**4202 / 4228 — single-dot open-ended marker:** Leading or trailing `.` (single, not the standard `..`) before/after a clean ISO date is rewritten to `/` so the value becomes a valid EDTF open-ended interval:

- `.YYYY[-MM[-DD]]` → `/YYYY[-MM[-DD]]` (open-ended-left, "anything up to and including the date")
- `YYYY[-MM[-DD]].` → `YYYY[-MM[-DD]]/` (open-ended-right, "the date onward")

Anchored on the whole value — only fires when the dot is the sole leading/trailing character. Internal `..` (the standard OHM range form, e.g. `1900..1950`) is untouched. Most commonly seen on `*_date:edtf` (surfaces as 4228 fixable), but also normalizes the base side (surfaces as 4202 fixable, autofix derives the bound for the base tag).

**4202 — Unicode dash normalization:** En-dash (`–`, U+2013), em-dash (`—`, U+2014), figure-dash (`‒`, U+2012) and minus-sign (`−`, U+2212) are normalized to ASCII hyphen-minus before pattern matching. Catches inputs pasted from word processors that auto-replace `-`. Example: `0544–0595` → `0544/0595`.

**4202 — multi-dot collapse:** Runs of three or more dots collapse to two — three is always a typo, an ellipsis, or a copy-paste artifact (`[1907...]` → `[1907..]`, `1839...1859` → `1839..1859`, `...15/11/1997` → `..15/11/1997`).

**4202 — junk `..` markers around `/`:** `..` directly adjacent to a `/` (on either side) is stripped. Cleans up partial-edit artifacts like `1839../..1859-12-02` → `1839/1859-12-02`. When this strip fires AND a leading or trailing `..` remains alongside the `/`, that `..` is treated as redundant junk too (`..1839/..1859` → `1839/1859`). The "remaining" strip is gated on whether inner-`..`-adjacent-to-`/` actually fired — so a clean leading `..` like `...15/11/1997` (an open-ended-left marker) is left for the standard step-8 rewrite to convert to `/`.

**4202 — qualifier adjacent to `..`:** A leading or trailing qualifier on a `..` range marker promotes to the bound year via the slash form, since EDTF can't attach a qualifier to `..`:
- `~..1907` → `/1907~` (open-ended-left, ends approximately 1907)
- `1907..~` → `1907~/` (open-ended-right, starts approximately 1907)

**4202 — `..` as `before`/`by`/`as of`/`during` separator:** After multi-dot collapse reduces `by...1907` to `by..1907`, the BEFORE pattern accepts `..` as a separator alongside the existing space and colon (`by..1907` → `/1907`, `during..1934` → `1934`). Same applies to the symmetric AFTER and DURING patterns.

**4202 — X-form with stray qualifier:** EDTF rejects qualifiers attached to X-forms (`196X?`, `18XX~`). Preprocess strips the qualifier, leaving the unspecified-digit form unchanged: `/196X?` → `/196X`. (The semantically distinct option of expanding the X-form to a specific year — e.g. `/196X?` → `/1960` — is not done because it changes the bound's meaning.)

**4202 / 4228 — short positive X-form padding:** Unpadded positive X-form years (`9XX`, `99X`, `9X`) are left-zero-padded to the canonical 4-char form (`09XX`, `099X`, `009X`). EDTF year bodies are 4 chars; the parser rejects shorter forms outright, so without padding these slip through as 4228 unfixable. Lowercase 'x' is uppercased as part of the rewrite. Mirrors the v0.7.3 negative-side fix that broadened rule 4250 to accept `-7XX`. All-X bodies (no digit anchor) are not matched — those remain unfixable. Surfaces as 4228 fixable on `*_date:edtf` keys (autofix preserves original in `:edtf:raw`); 4202 fixable on base `*_date` keys (autofix writes the full triple).

**4202 — qualified hyphen range:** Hyphen ranges with a leading qualifier (`~1848-1854`, `?47-50`) propagate the qualifier to the start side and rewrite as a slash interval: `~1848-1854` → `1848~/1854`, `?47-50` → `47?/50`. Year padding still happens (`~47-50` → `0047~/0050`).

**4202 — "end of YYYY":** `end of 1955` → `1955-12` (collapses the year-level "end-of" qualifier to the last calendar month). Symmetric handlers for `beginning of` and `mid of` aren't implemented yet.

**4202 — valid-EDTF passthrough:** When preprocess produces a result that the EDTF parser already accepts (e.g. `192X`, `[1907..]`, `199X`) and no specific normalizer matched, the result is returned as-is. This lets the canonicalization pipeline recurse cleanly through wrappers like `192X/..` → `192X/`.

The same path runs from `checkAllEdtfKeys` for `*_date:edtf` siblings, so `end_date:edtf=before:1882` autofixes to `end_date:edtf=/1882` with the original moved to `end_date:edtf:raw`.

---

### Invalid date — *_date:edtf invalid

| Code | Title |
|------|-------|
| 4208 | `[ohm] Invalid date - *_date:edtf; unfixable, please review` |
| 4228 | `[ohm] Invalid date - *_date:edtf; fixable, please review` |
| 4228 | `[ohm] Invalid date - *_date:edtf; unfixable, please review` |

**4208 trigger:** `*_date:edtf` is invalid EDTF and there is no corresponding base tag to fall back on.  
**4228 fixable trigger:** `*_date:edtf` is invalid EDTF but can be auto-corrected. Also covers the Rule D1 backslash-strip path: `*_date:edtf` starts with `\` and the remainder, after stripping the backslash, normalises (or is already valid EDTF).  
**4228 unfixable trigger:** `*_date:edtf` is invalid EDTF and cannot be corrected automatically.

**4208 example:**  
Trigger: `start_date:edtf=garbage`, no `start_date` present.  
Suggested manual fix: replace `:edtf` with valid EDTF, or delete the tag.

**4228 example (fixable, normalization):**  
Before: `start_date:edtf=199x` (lowercase X)  
After autofix: `start_date:edtf=199X` (canonical form), `start_date:edtf:raw=199x` preserves the original.

**4228 example (fixable, backslash-strip — Rule D1):**  
Before: `start_date:edtf=\1900`, with `start_date=1900`  
After autofix: `start_date:edtf=1900` (backslash prefix stripped, remainder is valid).

**4228 example (unfixable):**  
Trigger: `start_date:edtf=2020-13-99` — invalid and not normalizable.  
Suggested manual fix: replace with a valid EDTF expression.

---

### Date normalization — *_date:edtf not canonical

| Code | Title |
|------|-------|
| 4248 | `[ohm] Date normalization - *_date:edtf not canonical; autofix to canonical form` |

**Trigger:** `*_date:edtf` is valid EDTF (the parser accepts it) but is not in the canonical form — typically because year segments are unpadded (`700~`, `/787`, `636/700`) or because slash-form intervals retain non-canonical sub-expressions. The EDTF library is lenient about year padding (it accepts 3-digit years), but downstream bound-extraction misbehaves on unpadded years (e.g. `lowerBoundIso("/787")` returns `7870`), which causes spurious base-vs-`:edtf` mismatch warnings (4232/4242). Canonicalizing the `:edtf` value resolves both the cosmetic non-canonical state and the downstream mismatches.

**Severity:** ERROR — even though the parser accepts the input, downstream bound-extraction misbehaves on unpadded years and causes spurious mismatch warnings, so this is a real defect that needs the editor's attention. Treated alongside the other malformed-`:edtf` codes (4208, 4228).

**Fix:** Re-runs the value through `DateNormalizer.toEdtf` and writes the result, preserving the original in `*_date:edtf:raw`.

**Description:** _{key}={value} is valid EDTF but not canonical. Normalize to {newEdtf} and preserve original in {raw}?_

**Examples:**  
- `start_date:edtf=700~` → `start_date:edtf=0700~`, `start_date:edtf:raw=700~`  
- `start_date:edtf=/787` → `start_date:edtf=/0787`  
- `start_date:edtf=636/700` → `start_date:edtf=0636/0700`

**Already-canonical values** (e.g. `0700~`, `1880/1891`, `185X`) silently pass — no warning.

---

### Negative *_date:edtf with X digit(s)

| Code | Title |
|------|-------|
| 4250 | `[ohm] Suspicious date - negative *_date:edtf with X digit(s); autofix to EDTF range` |

**Trigger:** `*_date:edtf` is a negative (BCE) year body of **1-3 digits** (padded or unpadded) followed by one or two trailing X unspecified-digit characters: e.g. `-07XX`, `-7XX`, `-123X`, `-7X`. This form is misleading because the X-digit bounds are reversed relative to positive years — for positive `07XX` the range is `0700-0799`; for negative `-07XX` (or the equivalent unpadded `-7XX`) the range is `-0799` to `-0700` (799 BCE to 700 BCE, with the more-negative year at the lower/start end). Writing the X form directly risks incorrect base-tag derivation and confuses consumers that don't account for the sign inversion.

**Severity:** WARNING with autofix.

**Fix:** Replaces the X form with an explicit EDTF slash interval. Bounds are computed via integer math: `moreNegative = -(prefix * 10^xCount + (10^xCount - 1))`, `lessNegative = -(prefix * 10^xCount)`. Both bounds are rendered with 4-digit zero-padded magnitude (`%05d` accounting for the leading minus), so unpadded inputs like `-7XX` produce the same padded output `-0799/-0700` as the padded input `-07XX`. Updates the base `start_date` / `end_date` tag to the correct bound (earlier for start, later for end). Only `start_date:edtf` and `end_date:edtf` receive the base-tag update; other `:edtf` keys get the `:edtf` fix only.

**Description:** _{key}={value}: negative year with X digit(s). Bounds are {earlier} (earlier) to {later} (later). Replace with {range}?_

**Examples:**
- `start_date:edtf=-07XX` (4-digit padded, no base) → `start_date=-0799`, `start_date:edtf=-0799/-0700`
- `start_date:edtf=-7XX` (3-digit unpadded, no base) → same: `start_date=-0799`, `start_date:edtf=-0799/-0700`
- `end_date:edtf=-123X` → `end_date=-1230`, `end_date:edtf=-1239/-1230`
- `end_date:edtf=-7X` (single-digit body, single X) → `end_date=-0070`, `end_date:edtf=-0079/-0070`

**Why both padded and unpadded fire the same autofix (v0.7.3):** The leading zero on `-07XX` is just formatting, not semantic — the EDTF parser would accept `-0700/-0799` either way, and an editor typing `-7XX` almost certainly means the same as `-07XX`. The 4250 trigger pattern was originally limited to 2-3 digit body (`-NNX`, `-NNNX`, `-NNXX`, `-NNNXX`) which left short unpadded forms falling through to the generic 4228 unfixable. Broadened in v0.7.3 to also accept 1-digit body (`-NX`, `-NXX`).

---

### *_date:edtf with ? at interval endpoint

| Code | Title |
|------|-------|
| 4251 | `[ohm] Suspicious date - *_date:edtf with ? at interval endpoint; autofix by stripping ?` |
| 4252 | `[ohm] Suspicious date range - >100 year EDTF range in start or end; unfixable, please review` |

**Trigger:** `*_date:edtf` is an interval with a bare `?` as one endpoint: `?/YYYY` (intended open-ended left) or `YYYY/?` (intended open-ended right). `?` is a date-level uncertainty qualifier — it cannot stand alone as an interval endpoint. The intended meaning is an open-ended interval, which EDTF expresses with an empty slot: `/YYYY` or `YYYY/`.

**Severity:** WARNING with autofix.

**Fix:** Strips the `?` to produce a valid EDTF open-ended interval. Also updates the base `start_date` / `end_date` tag to the bound derived from the corrected interval (the one specific year present), consistent with OHM's open-endpoint fallback convention. Only `start_date:edtf` and `end_date:edtf` receive the base-tag update; other `:edtf` keys get the `:edtf` fix only.

**Description:** _{key}={value}: ? is not a valid EDTF interval endpoint. Strip ? to get open-ended form {fixed}_

**Examples:**  
Before: `start_date:edtf=?/1900` (no base)  
After autofix: `start_date=1900`, `start_date:edtf=/1900`

Before: `end_date=1850`, `end_date:edtf=1850/?`  
After autofix: `end_date=1850`, `end_date:edtf=1850/`

---

### 4252 — Long EDTF range

| Code | Title |
|------|-------|
| 4252 | `[ohm] Suspicious date range - >100 year EDTF range in start or end; unfixable, please review` |

**Trigger:** `start_date:edtf` or `end_date:edtf` is a closed interval whose two year bounds differ by more than 100 years. Open-ended intervals (`YYYY/` or `/YYYY`) are skipped. Year bounds are extracted by stripping qualifiers (`~`, `?`, `%`) and replacing unspecified-digit placeholders (`X`) with `0`.

**Severity:** WARNING, no autofix.

**Description:** _{key}={value}: interval spans {N} years._

**Example:**  
`start_date:edtf=1800/1950` → spans 150 years → WARNING fires.  
`start_date:edtf=1850/1950` → spans 100 years → does not fire (threshold is strictly > 100).

---

### Backwards EDTF interval (4255)

| Code | Title |
|------|-------|
| 4255 | `[ohm] Suspicious date - EDTF interval is backwards (start > end); unfixable, please review` |

**Trigger:** `start_date:edtf` or `end_date:edtf` is a closed slash interval (`start/end`) whose lower-bound year is greater than its upper-bound year — e.g. `start_date:edtf=2000/1900`. The interval is syntactically valid EDTF but semantically inverted; downstream consumers will silently pick one bound and ignore the other, producing wrong renders. Open-ended intervals (`YYYY/` or `/YYYY`) are skipped. Year bounds extracted by stripping qualifiers (`~`, `?`, `%`) and replacing `X` with `0`, matching the helper used by 4252.

**Fix:** None. The validator can't tell which side the user meant. The fix is either to swap the bounds (if the user meant the natural ordering) or correct whichever bound is wrong.

**Description:** _{key}={value}: interval start year ({start}) is later than end year ({end}). The validator can't tell which side you meant; fix by swapping the bounds or correcting whichever is wrong._

**Example:**
`start_date:edtf=2000/1900` → fires unfixable.

**Why this matters:** Without 4255, rule 4211 (the only rule that previously fired on a backwards interval) would silently derive `start_date=1900` from `:edtf=2000/1900` by extracting the upper bound — freezing the bad state in place. 4255 fires *before* that autofix would otherwise mask the problem.

**v0.8 extension:** 4255 now also fires on the bracket-set form `[A..B]` (in addition to slash `A/B`). The pattern `[2000..1900]` is detected the same way.

---

### Cross-key EDTF range checks (4257, 4258)

| Code | Title |
|------|-------|
| 4257 | `[ohm] Suspicious date - start_date:edtf is entirely later than end_date:edtf; unfixable, please review` |
| 4258 | `[ohm] Suspicious date - start_date:edtf and end_date:edtf overlap; unfixable, please review` |

**4257 trigger:** the lowest possible year of `start_date:edtf` is greater than the highest possible year of `end_date:edtf` — i.e., the entity ended before it could have started. Open-ended intervals are skipped to avoid false positives.

**4258 trigger:** the two ranges share at least one year (max of lower bounds ≤ min of upper bounds). The starting period should be entirely before the ending period; an overlap is logically inconsistent. Open-ended intervals are skipped.

**Fix (both):** None. Manual review needed; the validator can't tell which side is wrong.

**4257 description:** _start_date:edtf={start} ({start_lo}–{start_hi}) is entirely later than end_date:edtf={end} ({end_lo}–{end_hi}). The entity ended before it started — review whether the two values were swapped or one is wrong._

**4258 description:** _start_date:edtf={start} ({start_lo}–{start_hi}) and end_date:edtf={end} ({end_lo}–{end_hi}) overlap on {overlap_lo}–{overlap_hi}. The starting period should be entirely before the ending period; review and tighten whichever bound is wrong._

---

### Date mismatch — base vs. :edtf disagreement

| Code | Title |
|------|-------|
| 4210 | `[ohm] Date mismatch - *_date does not match *_date:edtf; unfixable, please review` |
| 4211 | `[ohm] Date mismatch - *_date:edtf & no *_date tag; autofix by deriving *_date from *_date:edtf` |

**4210 trigger:** `*_date` is present and valid, but disagrees with the bound implied by `*_date:edtf` — specifically, it falls **outside** the bounds. A `*_date` that is *more precise within bounds* is the expected OHM convention (the high-precision authoritative value lives on `*_date`, the wider/qualified context on `:edtf`) and is not flagged.  
**4210 description:** _{key}={value} but {key}:edtf={edtf} implies {key}={expected}. Manual review needed._

**4211 trigger:** `*_date:edtf` is valid but no `*_date` base tag exists.  
**4211 fix:** Derives and sets `*_date` from `*_date:edtf`. If `*_date:edtf` would equal the derived `*_date` (i.e. it carries no info beyond the base — no range, no qualifier), `*_date:edtf` is also deleted so the base alone holds the value.  
**4211 description:** _{key}:edtf={edtf} implies {key}={derived}._

**4210 example:**  
Trigger: `start_date=2020`, `start_date:edtf=1900/1950` (base year is well outside the EDTF range).  
Suggested manual fix: pick the authoritative value and update the other to match.

**4210 non-example (silent):**  
`start_date=1890-03-15`, `start_date:edtf=1890~` — base is more precise than `:edtf` and falls within the implied bounds. Expected state, no warning.

**4211 example:**  
Before: `start_date:edtf=1900/1950`, no `start_date`  
After autofix: `start_date=1900` derived as the lower bound. (`:edtf` is preserved because the range carries info beyond the base.)

**4211 example — redundant `:edtf` cleared:**  
Before: `start_date:edtf=1850`, no `start_date`  
After autofix: `start_date=1850`, `start_date:edtf` deleted.

---

### Date mismatch — :raw disagreement

| Code | Title |
|------|-------|
| 4206 | `[ohm] Date mismatch - across date tags; unfixable, please review (:raw is preserved as-is)` |
| 4207 | `[ohm] Invalid date - Unparseable data preserved in *_date:raw tag, no valid *_date:edtf or *_date tags; unfixable, please review` |
| 4242 | `[ohm] Date mismatch - normalize would overwrite *_date:raw; unfixable, please review` |

**v0.8 :raw philosophy.** `:raw` is by design the human-authored original input — preserved verbatim, never auto-rewritten or deleted. The validator uses it as a reference for what `:base` / `:edtf` should mean, but only modifies `:base` / `:edtf` when fixing. Comparisons between `:raw` and `:edtf` are **semantic** (both passed through `toEdtf` so equivalent forms like `:raw="between 1920 and 1940"` and `:edtf="1920/1940"` count as matching). Rule 4205 (which had auto-rewritten `:base`/`:edtf` from `:raw` when the last editor was `tagcleanupbot`) is retired.

**4206 trigger:** `*_date:raw` is present and the triple is genuinely inconsistent — e.g., `:edtf` doesn't match the EDTF that `:raw` normalizes to, OR `*_date` disagrees with the bound implied by `:edtf`. Specifically excluded: cases where `*_date` is missing and `:edtf` is valid (rule 4211 handles by deriving `*_date` from `:edtf`).  
**4206 fix:** None. `:raw` is never auto-deleted; the editor must reconcile manually.  
**4206 description:** _{key}={value} (or absent), {key}:edtf={edtf} (or absent), and {key}:raw={raw} do not agree._ (Specific phrasing varies by which side disagrees.)

**4207 trigger:** `*_date:raw` is set but `*_date:edtf` and `*_date` are absent or unparseable.  
**4207 fix:** None.

**4206 example:**  
Trigger: `start_date=early 1100` (decade-early), `start_date:raw=early C12` (century-early). Both human-authored, semantically different.  
Suggested manual fix: decide which is canonical and correct the other; `:raw` stays.

**4207 example:**  
Trigger: `start_date:raw=garbage`, no valid `start_date` or `:edtf`.  
Suggested manual fix: hand-correct the date based on whatever source produced the :raw value.

**4242 trigger:** A normalization autofix would write `*_date:raw` with a value different from what the user already has there. Currently fires for the decade/century rule (4203/4204) and the `end_date=present` rule (4221) when their respective autofix path's intended `:raw` write would clobber a hand-authored or pre-existing machine-generated value.  
**4242 fix:** None. Manual review required: delete or merge the existing `:raw` before re-running the validator.  
**4242 description:** _{key}={value}: would normalize to {key}={newBase}, {key}:edtf={newEdtf}, {key}:raw={proposedRaw}, but {key}:raw={existingRaw} already holds a different value. Manual review needed: delete or merge the existing :raw before re-running the validator._

**4242 example:**  
Trigger: `start_date=1800s` AND `start_date:raw=around 1800 (hand-authored)`.  
Suggested manual fix: decide whether the user's hand annotation is canonical (delete or pre-edit `:raw` to match what the autofix would produce, then re-run) or whether the user wanted the normalized form (delete the hand annotation and accept the autofix). Same shape applies to `end_date=present` overwriting a pre-existing `end_date:raw`.

---

### Backslash patterns (Rules A and D1)

Rules B and C also relate to the `\<end_date>` pattern but live with the equality section above. This section covers the two backslash rules that don't depend on `start_date == end_date`.

| Code | Title |
|------|-------|
| 4223 | `[ohm] Suspicious date - start_date:edtf=\[end_date]; autofix by deleting tags` _(Rule A, bot rollback)_ |
| 4226 | `[ohm] Suspicious date - start_date:edtf range extends after end_date; unfixable, please review` _(Rule D1)_ |

**4223 trigger (Rule A):** `start_date:edtf` matches the tagcleanupbot signature (`\<end_date_value>`) AND the last editor was the bot. Full rollback offered.

**4226 trigger (Rule D1):** `start_date:edtf` starts with `\` and the remainder, after stripping the backslash, is a *prefix* of `end_date` but not the exact value. The implication: the bot's `\<end_date>` pattern was truncated by a subsequent human edit, leaving a partial match.

**4223 example:**  
Before: `start_date=1900`, `start_date:edtf=\1900`, `end_date=1900` (last editor `tagcleanupbot`)  
After autofix: `start_date` and `start_date:edtf` deleted (bot-induced rollback).

**4226 example:**  
Trigger: `start_date:edtf=\190`, `end_date=1900` — the backslash remainder `190` is a prefix of `1900` but not equal.  
Suggested manual fix: confirm the intended start_date manually; the bot pattern is truncated and ambiguous.

---

### Julian calendar conversion

| Code | Title |
|------|-------|
| 4233 | `[ohm] Invalid date - Julian date; fixable, please review` |
| 4240 | `[ohm] Invalid date - Julian date but *_date:note already populated; unfixable, please review` |

**4233 trigger:** `start_date` or `end_date` uses `j:YYYY-MM-DD` (Julian) or `jd:NNNNNNN` (Julian Day Number) notation AND `*_date:note` is empty or absent.  
**4233 fix:** Converts to Gregorian, stores converted value in base tag, writes a calendar-conversion annotation into `*_date:note`.  
**4233 description:** _{key}={julian} → {key}={gregorian} (Gregorian), {key}:note added_

**4240 trigger:** Same as 4233 — Julian or Julian Day Number notation — but `*_date:note` already holds a value. The autofix would synthesise its own calendar-conversion note and silently overwrite the user's annotation, so this case is split out as unfixable. Per OHM wiki convention, `:note` is the slot for human-meaningful annotation, so preserving its content is non-negotiable.  
**4240 fix:** None. Manual review required: keep, merge, or replace the existing note before re-running the validator.  
**4240 description:** _{key}={julian}: would convert to {gregorian} (Gregorian) and add a calendar-conversion :note, but {noteKey}={existingNote} already holds a value. Manual review needed: keep, merge, or replace the existing note before re-running the validator._

**4233 example:**  
Before: `start_date=j:1582-10-04` (Julian calendar), no `start_date:note`.  
After autofix: `start_date=1582-10-14` (Gregorian equivalent), `start_date:note=Converted from j:1582-10-04`.

**4240 example:**  
Trigger: `start_date=j:1582-10-04` AND `start_date:note=From archival entry, see ledger p.42`.  
Suggested manual fix: decide whether to merge the calendar-conversion note with the existing archival note, replace one with the other, or keep the original and switch to a manually-converted Gregorian date.

---

### Chronology — relation structural checks

| Code | Title |
|------|-------|
| 4234 | `[ohm] Chronology - member date range outside parent chronology range; unfixable, please review` |
| 4235 | `[ohm] Chronology - member date range overlap; unfixable, please review` |
| 4236 | `[ohm] Chronology - gap between member date ranges; unfixable, please review` |
| 4236 | `[ohm] Chronology - gap between parent start & oldest member; unfixable, please review` |
| 4236 | `[ohm] Chronology - gap between latest member end & parent end; unfixable, please review` |
| 4237 | `[ohm] Chronology - member missing required date tag; unfixable, please review` |
| 4238 | `[ohm] Chronology - member duplicate to its predecessor; unfixable, please review` |
| 4239 | `[ohm] Chronology - member without dates; unfixable, please review` |
| 4243 | `[ohm] Chronology - boundary chronology has non-relation members; unfixable, please review` |
| 4254 | `[ohm] Chronology - relation has no members; unfixable, please review` |
| 4245 | `[ohm] Suspicious feature - 1 feature that should be {N}; autofix by collapsing to min/max bounds` |
| 4245 | `[ohm] Suspicious feature - 1 feature that should be {N}; unfixable, please review` |

**4245 trigger:** Any **curated start/end date-key pair** has both sides containing semicolon-delimited entries with the same count (≥ 2 each), where every entry on each side parses as a strict ISO date (`YYYY`, `YYYY-MM`, or `YYYY-MM-DD`). The pattern almost always indicates that a single OSM/OHM feature has been used to encode N temporally-distinct features (e.g. a building rebuilt twice, recorded as one feature with three start/end pairs).

**Curated date-key pairs (v0.7.1):**
- `(start_date, end_date)` — primary lifespan
- `(birth_date, death_date)` — persons
- `(opening_date, closing_date)` — businesses / operational period
- `(construction_date, demolition_date)` — buildings

The same autofix shape applies to all pairs: collapse to min/max bounds, preserve originals in the matching `:raw` slots, add `fixme=split into multiple features`. Auto-discovery of arbitrary `*_date` pairs is deliberately NOT used — it would produce false positives on coincidentally-named pairs that aren't actually semantically matched start/end keys. A curated list stays predictable.


**4245 fix:** Collapses `start_date` to the minimum of the start values and `end_date` to the maximum of the end values; preserves the original semicolon strings in `start_date:raw` and `end_date:raw`; adds `fixme=split into multiple features` so the editor remembers to do the actual split manually after accepting the fix.  
**4245 description:** _start_date={starts} and end_date={ends}: looks like {N} features merged into one. The autofix collapses to start_date={min} and end_date={max} (min/max), preserves the originals in start_date:raw={starts} and end_date:raw={ends}, and adds fixme=split into multiple features so the editor remembers the manual follow-up._

**4245 example:**  
Before: `start_date=1850;1900;1950`, `end_date=1899;1949;2000`.  
After autofix: `start_date=1850`, `end_date=2000`, `start_date:raw=1850;1900;1950`, `end_date:raw=1899;1949;2000`, `fixme=split into multiple features`.

**4245 unfixable variant:** When the autofix would clobber an existing `start_date:raw` or `end_date:raw` (the same shape protection used by 4242), the warning fires under its unfixable title (`; unfixable, please review`) and the description explains the `:raw` conflict. The editor must clear or merge the conflicting `:raw` before re-running.

**Per-key suppression:** When 4245 fires (with or without autofix), the per-key date checks for `start_date` and `end_date` are skipped on this primitive — they would otherwise flag the semicolon strings as `Invalid date - *_date cannot be read`, which is true but redundant noise once 4245 has explained the situation.

These six rules apply only to `type=chronology` relations. Comparisons use only strict `start_date` / `end_date` values in `YYYY`, `YYYY-MM`, or `YYYY-MM-DD` form (no `:edtf`, no `:raw`, no Julian, no EDTF intervals). Members whose dates can't be parsed strictly are skipped from the range comparisons but still flagged by 4237 if a tag is missing. Findings select only the offending member(s); the outside-parent rule (4234) additionally selects the parent chronology relation since the violation is intrinsically about the parent ↔ member relationship.

**4254 (WARNING) trigger:** `type=chronology` relation has zero members (`getMembers().size() == 0`). Typically an editing accident — relation created from a template but members never added, or all members removed, leaving the wrapper behind. JOSM core has a generic empty-relation warning; this one fires on top of it with OHM-specific framing ("add the constituent features or delete the relation"). Fires before the other chronology rules so the noise of "no members to compare" doesn't compound. No autofix — the user has to choose between adding members or deleting the relation.

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

## TagConsistencyTest (codes 4300–4332)

**Source slot contract (v0.5).** Three keys, three roles:

- `source` (and numbered variants `source:N`) — URL **or** text. Both
  placements are valid.
- `source:name` (and `source:N:name`) — text only.
- `source:url` (and `source:N:url`) — URL only.

Codes 4306, 4310, 4311, 4313, 4321, 4322, 4323 retired in v0.5 along
with the prior URL-leaning interpretation of `source` (see Retired codes
table at the bottom).


### Name consistency

| Code | Title |
|------|-------|
| 4300 | `[ohm] Missing tag - name=*; unfixable, please review` |
| 4301 | `[ohm] Name warning - dates in name; autofix by stripping the date range` |
| 4301 | `[ohm] Name warning - parentheses in name; unfixable, please review` |
| 4320 | `[ohm] Name warning - "historic" in name; unfixable, please review if this is date appropriate` |

**4300 trigger:** Feature has language-variant name keys (e.g. `name:en`) but no plain `name` key. **Skipped on `type=route` relations** — routes are conventionally identified by `ref` (route number / designation), so name-family-only routes are legitimate.  
**4300 description:** _Feature has name-family keys ({key}, etc.) but no plain 'name' key. Please add a canonical name._

**4301 fixable trigger (parens with clean date shape):** A `name`-family key contains a parens group whose trimmed contents match a strict date-shape regex — a single year, year-month, full ISO date, open-ended-left / right, two-bound year range, two-bound year-month range, or any of those with a `~` / `?` / `%` qualifier, `c.` / `ca.` / `circa`, or `before` / `after` prefix (case-insensitive). Autofix strips the parens span and collapses whitespace.

**4301 fixable trigger (clean inline date range):** A `name`-family key contains a substring matching `\bYYYY-YYYY\b` or `\bYYYY-MM-YYYY-MM\b` (two-bound only — single-year inline like `Building 1950` is deliberately NOT caught, too easily a building/model number). Autofix strips the matched span and collapses whitespace.

**4301 unfixable trigger (parens with year-like content but not a clean shape):** A `name`-family key contains a parens group whose contents have a year-like number (3-4 digits) but don't match the clean date shape — e.g. `(Springfield 1950)` mixes a place name with a year. No autofix; manual review needed.

**4301 fixable description:** _{key}={value}: dates in names are discouraged; move to start_date / end_date. Strip the date range to leave {key}={fixed}?_

**4301 unfixable description:** _{key}={value}: dates in parentheses are discouraged in names; move the date to start_date / end_date instead._

Detection order: parens-with-clean-shape, then inline-clean-range, then parens-with-year-like-but-not-clean. At most one warning fires per name value.

**4300 example (fires):**  
Trigger: way with `name:en=Empire State Building`, `name:fr=Empire State Building`, no plain `name`.  
Suggested manual fix: add `name=Empire State Building` (or whichever language is canonical for the location).

**4300 example (does not fire):**  
A relation with `type=route`, `route=bus`, `name:en=Pacific Coast Highway`, `ref=1`. Routes can rely on `ref` for canonical identity.

**4301 example (fixable, parens):**  
Before: `name=Wild West (1880-1922)`  
After autofix: `name=Wild West`. Encode dates in `start_date` / `end_date` instead.

**4301 example (fixable, parens with open-end):**  
Before: `name=Wild West (1950-)`  
After autofix: `name=Wild West`.

**4301 example (fixable, parens with circa prefix):**  
Before: `name=Wild West (c. 1900)`  
After autofix: `name=Wild West`.

**4301 example (fixable, inline two-bound range):**  
Before: `name=Wild West 1942-04-1948-09`  
After autofix: `name=Wild West`. Move the date range to `start_date=1942-04`, `end_date=1948-09`.

**4301 example (unfixable, parens with mixed content):**  
Trigger: `name=Wild West (Springfield 1950)`. The parens have a year-like token but also non-date text, so the autofix can't safely strip the whole parens; manual review required.

**4301 example (does not fire):**  
`name=City Park (Springfield)` — parenthesised disambiguator with no year-like content; left alone.

`name=Building 1950` — single 4-digit number inline; could be a year but also a building / model / route number, so the rule does not fire.

**4320 trigger:** Any name-family value contains the substring "historic" (case-insensitive). Matches `Historic`, `historical`, `Prehistoric`, `Ahistorical`, etc. — any historicizing frame, however constructed. The reasoning: "historic" framing reflects a present-day vantage; in OHM's time-aware data model, the entity at the time it existed wouldn't have called itself "historic". The Forum in Rome was just a Forum, not "historic", in 50 BCE.  
**4320 description:** _{key}={value}: "historic" in a name often reflects a present-day perspective. In OHM, confirm the entity was actually called this at the time it existed._

**4320 example (fires):**  
`name=Historic Town Hall`, `name=Historical Society`, `name=Prehistoric Cave` — all trip the rule. Confirm whether the actual entity at its time was so named, or whether the qualifier is being added retrospectively.

---

### Missing attribution

| Code | Title |
|------|-------|
| 4302 | `[ohm] Missing tag - wikidata; unfixable, please review` |
| 4303 | `[ohm] Missing tag - source on named feature; unfixable, please review & add` |

**4302 trigger:** Named feature has no `wikidata` tag **and** carries a notability signal: any `wikipedia=*`, `historic=*`, `boundary=administrative`, or a notable value of `place` (city/town/village/hamlet/suburb/neighbourhood/county/state/country/region/island/archipelago/continent), `tourism` (museum/attraction/monument/artwork/gallery), `amenity` (place_of_worship/university/courthouse/townhall/library/theatre/hospital/school), `building` (castle/cathedral/church/chapel/mosque/synagogue/temple/palace), or `military` (castle/fort/barracks). Relations always count.

**4302 exception:** Ways with `maritime=yes` that are members of a `type=boundary` relation are skipped — they're segments of a larger boundary entity and don't have their own Wikidata identity. The parent boundary relation still fires the rule.

**4302 fix:** If `wikipedia=*` is present, an autofix is offered that resolves the QID via the Wikidata API (`wbgetentities` against `<lang>wiki` site title) at fix-click time. If the lookup fails (network error, missing article, no QID in response), the fix is a silent no-op. No autofix is offered when `wikipedia=*` is absent.

**4302 description:** _Wikidata QIDs help link OHM data to other databases._

**4303 trigger:** Named feature has no `source*` tag of any kind. As of v0.4.0, `type=chronology` relations are exempt — they're aggregator wrappers around member relations (each of which carries its own provenance), so requiring a top-level source on the chronology itself adds noise without signal.  
**4303 description:** _other mappers are lost without it._

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

### Source slot type-check

| Code | Title |
|------|-------|
| 4307 | `[ohm] Source optimization - URL missing 'http[s]://'; autofix by prepending https://` |
| 4324 | `[ohm] Source mismatch - URL value in source:name; autofix by moving to source:url` |
| 4324 | `[ohm] Source mismatch - URL in source:name & source:url already set; unfixable, please review` |
| 4325 | `[ohm] Source mismatch - text value in source:url; autofix by moving to source / source:name / source:note` |
| 4325 | `[ohm] Source mismatch - text value in source:url & all sibling slots full; unfixable, please review` |

**4307 trigger:** Any `source[:N]?[:url]?` value matches the URL-shape regex but is missing `http://` or `https://`. Generalized in v0.5 from `source` / `source:N` to also fire on `source:url` / `source:N:url`.  
**4307 fix:** Prepends `https://`.  
**4307 description:** _{key}={value} looks like a URL missing the scheme. Prepend 'https://'?_

**4324 trigger:** `source:name` (or `source:N:name`) holds a value matching the strict URL regex (must have `http://` or `https://`).  
**4324 fix (slot free):** Moves the URL to the matching `source[:N]?:url` slot and clears `source[:N]?:name`.  
**4324 fix (slot occupied with a different URL):** None — manual review.  
**4324 description (fixable):** _{key}={value} is a URL. Move to {url_key}?_  
**4324 description (unfixable):** _{key}={value} is a URL but {url_key}={existing_url} already holds a different URL. Manual review needed._

**4325 trigger:** `source:url` (or `source:N:url`) holds a non-URL value (no scheme, and not even URL-shaped enough to fire 4307). Plain free-form text in a URL-only slot.  
**4325 fix (fallback chain):** Walks the companion siblings in order — `source[:N]?` → `source[:N]?:name` → `source[:N]?:note` — and moves the text to the first empty one, clearing `source[:N]?:url`.  
**4325 fix (all three full):** None — manual review.  
**4325 description (fixable):** _{key}={value} is not a URL. Move to {target}?_  
**4325 description (unfixable):** _{key}={value} is not a URL but {source}, {source:name}, and {source:note} all hold values. Manual review needed._

**4307 example (source):**  
Before: `source=usgs.gov/maps/topo1925`  
After autofix: `source=https://usgs.gov/maps/topo1925`.

**4307 example (source:url):**  
Before: `source:url=example.org/secondary`  
After autofix: `source:url=https://example.org/secondary`.

**4324 example (autofix):**  
Before: `source:name=https://example.org/scan`, `source:url=` (blank)  
After autofix: `source:name=` (blank), `source:url=https://example.org/scan`.

**4324 example (unfixable):**  
Trigger: `source:1:name=https://example.org/foo`, `source:1:url=https://example.org/different`. Two different URLs occupy the URL slot and the name slot — manual review required.

**4325 example (fallback to source):**  
Before: `source=` (blank), `source:url=Sketch in archive box 12`  
After autofix: `source=Sketch in archive box 12`, `source:url=` (blank).

**4325 example (fallback to source:name):**  
Before: `source=https://example.org/primary`, `source:name=` (blank), `source:url=Field notes 1923`  
After autofix: `source:name=Field notes 1923`, `source:url=` (blank).

**4325 example (fallback to source:note):**  
Before: `source=https://example.org/primary`, `source:name=Existing label`, `source:note=` (blank), `source:url=Note about provenance`  
After autofix: `source:note=Note about provenance`, `source:url=` (blank).

**4325 example (unfixable):**  
Trigger: `source`, `source:name`, and `source:note` all hold values, and `source:url` holds non-URL text. No empty fallback slot — manual review.

---

### source vs. source:url consolidation

| Code | Title |
|------|-------|
| 4312 | `[ohm] Source mismatch - source & source:url are different URLs; autofix by moving source:url to source:#` |

The pair iteration is generalized to every `source[:N]?:url` key on a primitive (issue #27, since v0.4.0). So `source:1:url` is paired with `source:1`, `source:7:url` with `source:7`, etc.

**4312 trigger:** `source` (or `source:N`) holds a URL, `source:url` (or `source:N:url`) holds a different URL. Two distinct URLs in what should be a single source slot is treated as a likely user error — preserve the value in `source[:N]?` and demote the `:url` value to a numbered slot.  
**4312 fix:** Moves the `:url` value to `source:M+1` where M is the highest existing `source:N` index on the primitive (the shared enumeration convention; see Semicolon-separated rules below).  
**4312 description:** _{companion}={url1} and {url_key}={url2} are different URLs. Move {url_key} to the next numbered source key?_

**4312 example:**  
Before: `source=https://a.example/map`, `source:url=https://b.example/map`  
After autofix: `source=https://a.example/map` (unchanged), `source:1=https://b.example/map`, `source:url` deleted.

**Note on retired companion rules:** Under the v0.5 contract, identical URLs in both slots (formerly 4311) and text-in-`source` + URL-in-`source:url` (formerly 4313) and bare `source:url` with empty `source` (formerly 4312 case 1) are all valid layouts and no longer warned. See Retired codes table.

---

### Semicolon-separated source values

| Code | Title |
|------|-------|
| 4314 | `[ohm] Source optimization - source contains 1 URL & 1 text string; autofix by splitting into source & source:url` |
| 4314 | `[ohm] Source mismatch - source contains 1 URL & 1 text string but source:url already holds a different value; unfixable, please review` |
| 4315 | `[ohm] Source optimization - source contains multiple URLs; autofix by enumerating source:# keys` |
| 4316 | `[ohm] Source mismatch - source contains multiple text strings separated by semicolons; unfixable, please review` |
| 4317 | `[ohm] Source mismatch - source contains multiple values of different types; unfixable, please review` |

**Enumeration convention (v0.5).** Rules 4312 and 4315 share one rule for placing values into numbered slots: scan the primitive for `source:N` keys, take `M = max(N)` (or 0 if none), and write new items to `source:M+1`, `source:M+2`, …. The bare `source` key is only overwritten by 4315 when the primitive has no enumerated `source:N` keys at all (in which case the autofix writes `source=items[0]`, `source:1=items[1]`, …). Otherwise `source` is left alone and items are appended past the existing enumeration. This guarantees no clobbering and tolerates gaps.

**4314 trigger:** `source` contains two semicolon-separated values: one URL and one text string.  
**4314 fix (slot empty or matching):** Writes `source=text`, `source:url=URL`. (If `source:url` already equals the URL part, only `source` is updated.)  
**4314 fix (slot occupied with a different value):** None — emits the unfixable variant under the same code 4314.  
**4314 description (fixable):** _{key}={value}: move text to source and URL to source:url?_  
**4314 description (unfixable):** _{key}={value}: cannot split into source={text} and source:url={url} because source:url already holds {existing}. Manual review needed._

**4315 trigger:** `source` contains two or more semicolon-separated URLs.  
**4315 fix:** Enumerates per the shared convention.  
**4315 description:** _{key}={value}: enumerate into source, source:1, source:2, …?_

**4316 trigger:** `source` contains two or more semicolon-separated non-URL strings.  
**4316 fix:** None — semicolons in text are ambiguous. They may delimit separate sources, but they may also be legitimate punctuation inside a single citation (e.g. `Archive folder 12; see also p. 7`). Manual review.  
**4316 description:** _{key}={value}: semicolons in text are ambiguous. If these are separate sources, split manually into source, source:1, source:2, …; if the semicolons are punctuation in a single citation, leave alone._

**4317 trigger:** `source` contains 3+ semicolon-separated items mixing URLs and text.  
**4317 fix:** None — too ambiguous to autofix.  
**4317 description:** _{key}={value}: 3 or more items mixing URLs and text. Manual review needed — split into source, source:N, source:url, source:N:url as appropriate._

**4314 example (fixable):**  
Before: `source=https://usgs.gov/topo1925; USGS topo 1925`  
After autofix: `source=USGS topo 1925`, `source:url=https://usgs.gov/topo1925`.

**4315 example:**  
Before: `source=https://a.example; https://b.example`  
After autofix: `source=https://a.example`, `source:1=https://b.example`.

**4315 example (with existing source:1):**  
Before: `source=https://a.example;https://b.example;https://c.example`, `source:1=https://existing.example/preserved`  
After autofix: `source` cleared; `source:1=https://existing.example/preserved` (unchanged), `source:2=https://a.example`, `source:3=https://b.example`, `source:4=https://c.example`.

**4316 example (warn only):**  
Trigger: `source=Archive folder 12; Field notes 1923`. Two semicolon-separated text strings.  
Suggested manual fix: if these are two distinct sources, split into `source=Archive folder 12`, `source:1=Field notes 1923`. If the semicolon is punctuation inside a single citation, leave alone.

**4317 example:**  
Trigger: `source=https://a.example; USGS topo; https://b.example` (mixed types, 3+ items).  
Suggested manual fix: split by hand into `source`, `source:1`, `source:url`, `source:N:url` slots as appropriate.

---

### Attribute-source references

| Code | Title |
|------|-------|
| 4308 | `[ohm] Missing tag - wikipedia, referenced in source keys; unfixable, please review & add tag` |
| 4309 | `[ohm] Missing tag - wikidata, referenced in source keys; fixable, please review` |
| 4309 | `[ohm] Missing tag - wikidata, referenced in source keys; unfixable, please review & add tag` |

**Suppression model (v0.7).** A `wikidata=*` tag is treated as canonical attribution — its QID resolves to a Wikipedia article via Wikidata sitelinks. Both 4308 and 4309 silently accept this. A `wikipedia=*` tag is also accepted for 4308 (the article exists), but for 4309 a `wikipedia=*` is a *fix path* (lookup the QID) rather than a substitute.

**4308 trigger:** A `*:source` tag references Wikipedia but neither `wikipedia=*` nor `wikidata=*` exists on the feature.  
**4308 description:** _{key}={value}: please add an appropriate 'wikipedia' or 'wikidata' tag._

**4309 fixable trigger:** A `*:source` tag references Wikidata, no `wikidata=*` exists, and exactly one canonical `wikipedia=*` tag is present. Autofix queries the Wikidata API (same mechanism as 4302's autofix) and adds `wikidata=Q…`.  
**4309 fixable description:** _{key}={value}: derive 'wikidata=Q…' by looking up the wikipedia article on the Wikidata API._

**4309 unfixable trigger:** A `*:source` tag references Wikidata and either (a) multiple `wikipedia*` tags exist (ambiguous which is canonical) or (b) no `wikipedia=*` exists at all.  
**4309 unfixable description:** _{key}={value}: please add an appropriate 'wikidata' tag._

**4308 example:**  
Trigger: `name:source=wikipedia` but no `wikipedia=*` or `wikidata=*` on the feature.  
Suggested manual fix: add `wikipedia=en:Some Article Title` (preferred — the source mentioned Wikipedia) or `wikidata=Q…`.

**4309 example (fixable):**  
Trigger: `start_date:source=wikidata` with `wikipedia=en:Eiffel Tower`, no `wikidata=*`.  
Click Fix → plugin queries Wikidata API, extracts `Q243`, writes `wikidata=Q243`.

**4309 example (unfixable, multiple wikipedia):**  
Trigger: `start_date:source=wikidata` with `wikipedia:en=Eiffel Tower` AND `wikipedia:fr=Tour Eiffel`, no `wikidata=*`.  
Suggested manual fix: add `wikidata=Q243` (or whichever QID is canonical).

### Attribute-source content rules (v0.7)

In addition to 4308/4309 above, the v0.5 source-content rules (4304/4305 are plain-source-only; 4307, 4312, 4314, 4315, 4316, 4317, 4324, 4325) now also fire on attribute-source slots whenever the value is not a wikipedia/wikidata literal. The same autofix logic applies, with autofix targets landing in the matching `<attr>:source*` companion slots:

- **4307** (URL missing scheme) fires on `<attr>:source` and `<attr>:source:url` and `<attr>:source:N` values that look URL-shaped without scheme.
- **4312** (URL conflict) fires when `<attr>:source` and `<attr>:source:url` hold different URLs. Autofix moves `<attr>:source:url` value to `<attr>:source:N+1`.
- **4314** (1 URL + 1 text) fires on `<attr>:source` with semicolon-separated URL + text. Autofix splits to `<attr>:source` (text) and `<attr>:source:url` (URL).
- **4315** (multi-URL) fires on `<attr>:source` with multiple semicolon-separated URLs. Autofix enumerates into `<attr>:source:N+1`, `<attr>:source:N+2`, … (never overwriting existing N).
- **4316** (multi-text) fires on `<attr>:source` with semicolon-separated text-only items. Unfixable.
- **4317** (mixed types) fires on `<attr>:source` with 3+ semicolon-separated items mixing URLs and text. Unfixable.
- **4324** (URL in source:name) fires on `<attr>:source:name` containing a URL. Autofix moves to `<attr>:source:url`.
- **4325** (text in source:url) fires on `<attr>:source:url` containing non-URL text. Autofix walks the fallback chain `<attr>:source` → `<attr>:source:name` → `<attr>:source:note`.

The autofix enumeration scheme (4312, 4315) always lands at `<attr>:source:N+1` where N is the highest existing numeric index on the matching prefix. **Never overwrites** existing `<attr>:source:N[:*]` slots.

---

### Suspicious tag — historic

| Code | Title |
|------|-------|
| 4319 | `[ohm] Suspicious tag - historic; unfixable, please review` |

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

### Suspicious tags — node duplicates parent way

| Code | Title |
|------|-------|
| 4326 | `[ohm] Suspicious tags - node with no unique tags from parent way; autofix by removing all node tags` |
| 4327 | `[ohm] Name warning - leading or trailing whitespace; autofix by trimming` |
| 4327 | `[ohm] Name warning - whitespace-only name; unfixable, please review` |
| 4328 | `[ohm] Malformed tag - wikidata value is not a QID; unfixable, please review` |
| 4329 | `[ohm] Malformed tag - wikipedia value is not <lang>:<title>; unfixable, please review` |

**Trigger:** A node carries one or more tags, and every one of those tags is duplicated (same key, same value) on at least one of the node's parent ways. Typically arises when an editor tags both the way and one of its constituent nodes for the same feature — only the way needs the tags. Multiple parent ways are tolerated; the rule fires when *any* parent way fully covers the node's tag set.  
**Fix:** Removes every tag from the node.  
**Description:** _All {N} tag(s) on this node are duplicated on parent way w/{way_id}; remove the node tags?_

**Example:**  
Trigger: a building way (`building=yes`, `start_date=1924`) with corner nodes each carrying `start_date=1924`.  
After autofix: corner nodes have no tags. The building way is unchanged.

---

### Name whitespace or control characters (4327)

| Code | Title |
|------|-------|
| 4327 | `[ohm] Name warning - leading or trailing whitespace; autofix by trimming` |
| 4327 | `[ohm] Name warning - whitespace-only name; unfixable, please review` |
| 4327 | `[ohm] Name warning - embedded control character; unfixable, please review` |

Three sub-paths, checked in priority order:

1. **Embedded control character** (unfixable). Fires when the value contains an `Character.isISOControl(c)` character that is not also one of the standard whitespace controls (tab `U+0009`, LF `U+000A`, CR `U+000D` — those are handled by the whitespace path). Catches paste artifacts: NUL, vertical tab, form feed, DEL, etc. The autofix would have to decide whether each control char is a typo, a missing separator, or intentional — too risky to automate, so it stays unfixable. Description names the codepoint in `U+XXXX` form.
2. **All-whitespace name** (unfixable). The value is non-empty but stripping it would leave an empty string. The editor must decide whether to restore content or remove the tag entirely.
3. **Leading or trailing whitespace** (fixable). Autofix calls `String.strip()` on the value and writes the result back.

**Descriptions:**
- _{key}={value}: contains a non-printable control character (U+XXXX). Likely a paste artifact; remove it manually._
- _{key}="{value}": value is only whitespace. Restore content or remove the tag._
- _{key}="{value}" has leading or trailing whitespace. Trim to "{trimmed}"?_

**Example:** `name=" Old Town Hall "` → autofix to `name="Old Town Hall"`.

**Fixture-coverage limitation:** Control characters like NUL (`U+0000`) and vertical tab (`U+000B`) are invalid in XML 1.0 and can't be embedded in `test_data.osm`. The control-char detection has no regression fixture but is exercised whenever a user pastes a control character through JOSM's tag editor.

---

### Malformed external-reference tag values (4328 / 4329)

| Code | Title |
|------|-------|
| 4328 | `[ohm] Malformed tag - wikidata value is not a QID; unfixable, please review` |
| 4329 | `[ohm] Malformed tag - wikipedia value is not <lang>:<title>; unfixable, please review` |

**4328 trigger:** `wikidata=*` is set but the value doesn't match `^Q\d+$` (a capital Q followed by 1+ digits). Catches typos like `wikidata=notaqid` and pasted full URLs like `wikidata=https://www.wikidata.org/wiki/Q243`. Without this rule, the presence-only check in 4302 passes a malformed QID through silently.

**4328 description:** _wikidata={value} is not a valid Wikidata QID. Expected shape: 'Q' followed by digits, e.g. Q243._

**4329 trigger:** `wikipedia=*` is set but the value doesn't match `^(?!https?:)[a-z]{2,10}:.+` (a 2-10 character lowercase language code that is *not* `http`/`https`, then a colon, then a non-empty title). Catches typos and pasted URLs like `wikipedia=https://en.wikipedia.org/wiki/Eiffel_Tower`. Without this rule, a malformed value would silently fail the 4302 autofix (which queries the Wikidata API using the `<lang>:<title>` split). The negative-lookahead exclusion of `http`/`https` was added in v0.7.4 — without it, `https://...` URL values would match (since `https` is 5 lowercase chars followed by `:`) and slip past 4329 entirely.

**4329 description:** _wikipedia={value} is not in the expected '<lang>:<title>' format (e.g. 'en:Eiffel Tower'). Downstream lookups will fail._

Neither rule is autofixable — the validator can't guess the user's intent (typo? truncated URL? wrong tag?). Both fire WARNING severity.

---

### Boundary geometry hygiene (4330, 4331, 4332)

Three rules introduced in v0.8 that target ways and nodes participating in `type=boundary` relations. The common theme: boundary geometry should be a clean substrate; identity, names, and dual-purpose tagging belong on separate nodes/ways at the same location.

| Code | Title |
|------|-------|
| 4330 | `[ohm] Boundary geometry - node has non-date/non-source tags; autofix by moving tags to a new node` |
| 4331 | `[ohm] Boundary geometry - waterway way is a boundary member; autofix by creating a coincident boundary way` |
| 4332 | `[ohm] Boundary geometry - members not in topological order; autofix by sorting` |

**4330 trigger:** a node that participates in a boundary way (a way that's a member of any `type=boundary` relation) has tags other than `start_date` / `end_date` / `*_date:*` / `source` / `source:*` / `attribute:source` / `attribute:source:*`. Non-date/non-source tags (name, place, historic, wikidata, etc.) trigger the warning.  
**4330 fix:** clones the node into a new node at the same coordinates carrying ALL of the original's tags (full duplicate); strips the non-date/non-source tags from the original. The original keeps its relation memberships and roles, and stays in the boundary way. The new node has no relation memberships — it's a fresh standalone POI.

**4331 trigger:** a way that carries `waterway=*` is also a geometry member of a `type=boundary` relation. The waterway-as-boundary pattern conflates two distinct identities.  
**4331 fix:** creates a new way at the same coordinates (with its own cloned nodes) carrying only the original's source-family tags. At each endpoint, any other way sharing that endpoint AND a member of any boundary relation is rerouted onto the cloned endpoint, preserving boundary topology. The original way is replaced by the new way in every `type=boundary` relation it's a member of (preserving role). The original keeps its `waterway` and other tags, all its original nodes, and any non-boundary relation memberships.

**4332 trigger:** a `type=boundary` relation's way members form one or more closed rings (every endpoint Node appears exactly twice across each role-group) but are not listed in topological order — consecutive members don't share an endpoint, or a ring doesn't close. Direction-agnostic; each role is evaluated as its own group. Open chains and other geometry problems are left for the core JOSM validator.  
**4332 fix:** reorders the way members within each unsorted role-group so consecutive members share an endpoint and each ring closes. Non-way members keep their positions in the member list; way-members of already-sorted role-groups keep their positions too.

---

## Retired codes

| Code | Reason |
|------|--------|
| 4205 | Retired in v0.8 — assumed `:raw` was bot-written and auto-rewrote base/`:edtf` from it. `:raw` is by design human-authored; the validator no longer makes that assumption. |
| 4209 | Merged into `CODE_EDTF_INVALID_NO_BASE` (4208) — invalid `:edtf` with base now fires 4208 alone |
| 4219 | Retired — negative astronomical years are legitimate OHM notation; the rule's false-positive rate outweighed its signal value (forum feedback 2026-04-21) |
| 4227 | Rule D2 now fires the unified "Invalid *_date:edtf" fixable/unfixable messages (4228) |
| 4229 | Merged with `CODE_EDTF_INVALID_NO_BASE` (4208) under the unified unfixable message |
| 4230 | Retired as redundant — invalid `:edtf` with a base tag now fires only the unified `:edtf` message (4208/4228) |
| 4232 | Retired — a `*_date` more precise than its `*_date:edtf` (with base falling within `:edtf`'s bounds) is the expected OHM convention, not a defect. Only true mismatches (`*_date` outside `:edtf`'s bounds) fire now, via 4210. |
| 4306 | Retired in v0.5 — non-URL `source` is now valid (slot typing loosened) |
| 4310 | Retired in v0.5 — `source:name` without a companion URL is a valid state (textual citation only) |
| 4311 | Retired in v0.5 — duplicate values in `source` and `source:url` are harmless under the loosened contract |
| 4312 case 1 | Retired in v0.5 — bare `source:url` with empty `source` is valid (`source:url` is now a first-class URL slot) |
| 4313 | Retired in v0.5 — `source` text + `source:url` URL is a valid layout |
| 4321 | Retired in v0.5 — non-URL `source` with a populated `source:name` is valid |
| 4322 | Retired in v0.5 — multi-URL split now always appends past max-existing `source:N` (rule 4315 always fixable) |
| 4323 | Retired in v0.5 — multi-text split now always appends past max-existing `source:N` (rule 4316 always fixable) |
