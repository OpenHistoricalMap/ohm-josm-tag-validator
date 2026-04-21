# OHM Tags Validator — Message Reference

All messages use `WARNING` severity. Titles follow the pattern:
`[ohm] <Category> - <what>; <fixable|unfixable>, please review [<action>]`

References to "rules" below are defined in the javadoc in DateTagTest.java.

---

## DateTagTest (codes 4200–4232)

### Suspicious date — missing start_date

| Code | Title |
|------|-------|
| 4200 | `[ohm] Suspicious date - man-made object without start_date; unfixable, please review` |

**Trigger:** Feature has a recognisable man-made tag (building, highway, amenity, etc.) but no `start_date`.  
**Fix:** None.  
**Description:** _Please make every effort to attempt a reasonable range for the `start_date:edtf` tag and provide an explanation in the `start_date:source` tag._

---

### Ambiguous date

| Code | Title |
|------|-------|
| 4219 | `[ohm] Ambiguous date - negative-year date; unfixable, please review` |
| 4220 | `[ohm] Ambiguous date - trailing hyphen in date; unfixable, please review` |

**4219 trigger:** `start_date` or `end_date` looks like a negative year but could be BCE, a typo, a range, or a range fragment.  
**4219 description:** _{key}={value}: could be BCE; a typo: {suggestion}; a range: {range}; or range fragment. Manual review needed._

**4220 trigger:** Value ends with a trailing hyphen (e.g., `2021-`), which is ambiguous between a typo and an open-ended range.  
**4220 description:** _{key}={value}: could be a typo: {suggestion}; an incomplete input; or an open-ended range {suggestion}/. Manual review needed._

---

### Ambiguous century/decade

| Code | Title |
|------|-------|
| 4203 | `[ohm] Ambiguous date - unclear century/decade date; autofix as decade` |
| 4204 | `[ohm] Ambiguous date - unclear century/decade date; autofix as century` |

**Trigger:** Values like `1800s` are ambiguous, either a decade (1800–1809) or a century (1800–1899). Two sibling warnings fire together — one offering each interpretation.  
**Fix:** Applies the chosen interpretation to `*_date` and `*_date:edtf`.  
**Description:** _{key}={value} as a decade/century: {key}={normalized}, :edtf={edtf}_

---

### Suspicious date — year-boundary

| Code | Title |
|------|-------|
| 4212 | `[ohm] Suspicious date - 01-01 start_date; autofix by removing -01-01` |
| 4213 | `[ohm] Suspicious date - 12-31 end_date; autofix by removing -12-31` |
| 4214 | `[ohm] Suspicious date - 12-31 start_date; autofix by removing -12-31` |
| 4214 | `[ohm] Suspicious date - 01-01 end_date; autofix by removing -01-01` |

**4212/4213 trigger:** `start_date=YYYY-01-01` or `end_date=YYYY-12-31` — likely an overly precise encoding of a bare year.  
**4214 trigger:** `start_date=YYYY-12-31` or `end_date=YYYY-01-01` — likely an off-by-one (next/previous year intended).  
**Fix:** Trims to bare year (4212/4213) or shifts year by ±1 (4214).  
**Description:** _{key}={value} → {key}={year}_

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

---

### Suspicious date — future date

| Code | Title |
|------|-------|
| 4216 | `[ohm] Suspicious date - >10 year into the future; autofix as removed` |

**Trigger:** A date tag value is more than 10 years beyond today.  
**Fix:** Deletes the offending key.  
**Description:** _{key}={value} is more than ten years in the future. Likely a typo; delete the key?_

---

### Invalid date — invalid components

| Code | Title |
|------|-------|
| 4217 | `[ohm] Invalid date - invalid month in start_date or end_date; autofix to YYYY` |
| 4218 | `[ohm] Invalid date - invalid day in start_date or end_date; autofix to YYYY-MM` |
| 4222 | `[ohm] Invalid date - month/day mismatch; too many days in the month in start_date or end_date; unfixable, please review` |

**4217 trigger:** Month component is out of range (< 1 or > 12). Offers trim-to-YYYY fix.  
**4217 description:** _{key}={value}: month {MM} is > 12. Trim to {YYYY}?_

**4218 trigger:** Day component is out of range (< 1 or > 31). Offers trim-to-YYYY-MM fix.  
**4218 description:** _{key}={value}: day {DD} is out of range. Trim to {YYYY-MM}?_

**4222 trigger:** Full ISO date that passes range checks but isn't a real calendar date (Feb 30, June 31, Feb 29 on non-leap year, etc.).  
**4222 fix:** None.  
**4222 description:** _{key}={value}: {YYYY}-{MM}-{DD} is not a valid date (e.g. 2/30, 6/31, or 2/29 in non-leap year)._

---

### Invalid date — present used incorrectly

| Code | Title |
|------|-------|
| 4221 | `[ohm] Invalid date - end_date=present; autofix to no end_date` |
| 4231 | `[ohm] Invalid date - start_date=present; autofix to no start_date` |

**4221 trigger:** `end_date` (or similar) holds the literal value `present` (case-insensitive), which OHM encodes as an absent end_date rather than as a tag value.  
**4221 fix:** Clears `end_date` and `end_date:edtf`; sets `end_date:raw=present`.  
**4221 description:** _{key}={value} means an ongoing feature. Clear base and :edtf, mark with :raw={value}?_

**4231 trigger:** `start_date=present`, which is semantically nonsensical.  
**4231 fix:** Deletes `start_date` and `start_date:edtf`.

---

### Invalid date — EDTF in base tag

| Code | Title |
|------|-------|
| 4202 | `[ohm] Invalid date - *_date contains a readable EDTF date; fixable, please review suggestion` |

**Trigger:** `start_date` or `end_date` contains a value that looks like EDTF (slashes, ranges, uncertainty markers) rather than a plain ISO date.  
**Fix:** Moves value to `*_date:edtf`, derives plain ISO base, stores original in `*_date:raw`.  
**Description:** _{key}={value} → {key}={normalized}, :edtf={edtf}, :raw={value}_

---

### Invalid date — *_date unparseable or unnormalizable

| Code | Title |
|------|-------|
| 4201 | `[ohm] Invalid date - *_date cannot be read; unfixable, please review` |
| 4202 | `[ohm] Invalid date - *_date; fixable, please review suggestion` |

**4201 trigger:** `start_date` or `end_date` cannot be parsed or normalized to EDTF.  
**4201 fix:** None.  
**4201 description:** _{key}={value} cannot be normalized._

**4202 trigger:** Value can be normalized; see also "EDTF in base tag" above.  
**4202 description:** _{key}={value} → {key}={normalized}, :edtf={edtf}, :raw={value}_

---

### Invalid date — *_date:edtf invalid

| Code | Title |
|------|-------|
| 4208 | `[ohm] Invalid date - *_date:edtf; unfixable, please review` |
| 4226 | `[ohm] Invalid date - *_date:edtf; fixable, please review suggestion` _(backslash truncated — Rule D1)_ |
| 4228 | `[ohm] Invalid date - *_date:edtf; fixable, please review suggestion` |
| 4228 | `[ohm] Invalid date - *_date:edtf; unfixable, please review` |

**4208 trigger:** `*_date:edtf` is invalid EDTF and there is no corresponding base tag to fall back on.  
**4226 trigger (Rule D1):** `*_date:edtf` starts with `\` and the remainder, after stripping the backslash, can be normalised.  
**4228 fixable trigger:** `*_date:edtf` is invalid EDTF but can be auto-corrected.  
**4228 unfixable trigger:** `*_date:edtf` is invalid EDTF and cannot be corrected automatically.

---

### Date mismatch — base vs. :edtf disagreement

| Code | Title |
|------|-------|
| 4210 | `[ohm] Date mismatch - *_date does not match *_date:edtf; unfixable, please review` |
| 4211 | `[ohm] Date mismatch - *_date:edtf & no *_date tag; autofix *_date based on *_date:edtf` |
| 4230 | `[ohm] Date mismatch - *_date:edtf does not match *_date; unfixable, please review` |
| 4232 | `[ohm] Date mismatch - *_date more precise than *_date:edtf; autofix *_date:edtf=*_date` |

**4210 trigger:** `*_date` is present and valid, but disagrees with the bound implied by `*_date:edtf`.  
**4210 description:** _{key}={value} but {key}:edtf={edtf} implies {key}={expected}. Manual review needed._

**4211 trigger:** `*_date:edtf` is valid but no `*_date` base tag exists.  
**4211 fix:** Derives and sets `*_date` from `*_date:edtf`.  
**4211 description:** _{key}:edtf={edtf} implies {key}={derived}._

**4230 trigger:** Companion to a fixable `:edtf` error when a base tag also exists — the two cannot be reconciled without manual review.  
**4230 description:** _{key}={value} is invalid, so it cannot be compared to {key}:edtf._

**4232 trigger:** `*_date` is more specific (e.g. full ISO date) than `*_date:edtf` (e.g. year only).  
**4232 fix:** Replaces `*_date:edtf` with the value from `*_date`.

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

---

### Backslash patterns (Rules A and C)

| Code | Title |
|------|-------|
| 4223 | `[ohm] Suspicious date - start_date:edtf=\[end_date]; autofix to delete tags` _(Rule A, bot rollback)_ |
| 4225 | `[ohm] Suspicious date - start_date:edtf range extends after end_date; unfixable, please review` _(Rule C)_ |

**4223 trigger (Rule A):** `start_date:edtf` matches the pattern written by `tagcleanupbot`. Full rollback offered.

**4225 trigger (Rule C):** `start_date:edtf` is a slash-range that extends past `end_date`.

---

### Julian calendar conversion

| Code | Title |
|------|-------|
| 4233 | `[ohm] Invalid date - Julian date; fixable, please review Gregorian conversion` |

**Trigger:** `start_date` or `end_date` uses `j:YYYY-MM-DD` (Julian) or `jd:NNNNNNN` (Julian Day Number) notation.  
**Fix:** Converts to Gregorian, stores converted value in base tag, preserves original in `*_date:note`.  
**Description:** _{key}={julian} → {key}={gregorian} (Gregorian), {key}:note added_

---

## TagConsistencyTest (codes 4300–4317)

### Name consistency

| Code | Title |
|------|-------|
| 4300 | `[ohm] Missing tag - name=*; unfixable, please review` |
| 4301 | `[ohm] Name warning - parentheses in name; unfixable, please review` |

**4300 trigger:** Feature has language-variant name keys (e.g. `name:en`) but no plain `name` key.  
**4300 description:** _Feature has name-family keys ({key}, etc.) but no plain 'name' key. Please add a canonical name._

**4301 trigger:** A `name` key contains parentheses, typically encoding dates — discouraged in OHM names.  
**4301 description:** _{key}={value}: '(dates)' are discouraged in names; please review and remove any dates in name keys._

---

### Missing attribution

| Code | Title |
|------|-------|
| 4302 | `[ohm] Missing tag - wikidata; unfixable, please review and add a Wikidata QID` |
| 4303 | `[ohm] Missing tag - source; named feature without source; unfixable, please review` |

**4302 trigger:** Named feature has no `wikidata` tag.  
**4302 description:** _This named feature has no 'wikidata' tag. Wikidata is the preferred identifier for cross-referencing._

**4303 trigger:** Named feature has no `source*` tag of any kind.  
**4303 description:** _This named feature has no 'source' tag. Please document the provenance of this feature._

---

### Suspicious source values

| Code | Title |
|------|-------|
| 4304 | `[ohm] Suspicious source - source=wikipedia; unfixable, please review` |
| 4305 | `[ohm] Suspicious source - source=wikidata; unfixable, please review` |

**4304/4305 trigger:** `source` (or numbered variant) is set to `wikipedia` or `wikidata` — not valid sources for geometry.  
**Description:** _{key}={value}: Wikipedia/Wikidata is not a reasonable source for geometry claims. Please link to an actual map, image, or survey._

---

### Source URL format

| Code | Title |
|------|-------|
| 4306 | `[ohm] Source optimization - move non-URL source tags to source:name` |
| 4307 | `[ohm] Source optimization - repair URL missing 'http[s]://'` |
| 4310 | `[ohm] Source optimization - source[:#]:name is present, but source[:#] is not; please review & add a source=URL, if possible` |

**4306 trigger:** `source` (or numbered variant) contains a non-URL text string.  
**4306 fix:** Moves value to `source:name`, leaves `source` blank for a URL.  
**4306 description:** _{key}={value} is not a URL. Move to {source:name} and leave {key} blank for a URL?_

**4307 trigger:** `source` value looks like a URL but is missing `http://` or `https://`.  
**4307 fix:** Prepends `https://`.  
**4307 description:** _{key}={value} looks like a URL missing the scheme. Prepend 'https://'?_

**4310 trigger:** `source:name` (or `source:N:name`) is present but the corresponding `source` (or `source:N`) URL is absent.  
**4310 fix:** None — prompts user to add the URL.  
**4310 description:** _{key}={value} is set, but {source_key} is empty. Would you like to add a URL for the source?_

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

---

### Semicolon-separated source values

| Code | Title |
|------|-------|
| 4314 | `[ohm] Source optimization - source contains one URL and one text string; autofix by splitting into source and source:name` |
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

---

### Attribute-source references

| Code | Title |
|------|-------|
| 4308 | `[ohm] Missing tag - wikipedia, referenced in source keys; unfixable, please review and add an appropriate Wikipedia article` |
| 4309 | `[ohm] Missing tag - wikidata, referenced in source keys; unfixable, please add appropriate Wikidata QID` |

**4308 trigger:** A `*:source` tag references Wikipedia but no `wikipedia` tag exists on the feature.  
**4308 description:** _{key}={value}: please add an appropriate 'wikipedia' tag._

**4309 trigger:** A `*:source` tag references Wikidata but no `wikidata` tag exists on the feature.  
**4309 description:** _{key}={value}: please add an appropriate 'wikidata' tag._

---

## Retired codes

| Code | Reason |
|------|--------|
| 4209 | Merged into `CODE_EDTF_INVALID_NO_BASE` (4208) — invalid `:edtf` with base now fires 4208 + 4230 as companions |
| 4227 | Rule D2 now fires the unified "Invalid *_date:edtf" fixable/unfixable messages (4228) |
| 4229 | Merged with `CODE_EDTF_INVALID_NO_BASE` (4208) under the unified unfixable message |
