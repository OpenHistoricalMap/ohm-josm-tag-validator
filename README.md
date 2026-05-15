#  OHM_Tag_Validator — an OpenHistoricalMap tag validator plugin for JOSM

Validates and normalizes OHM-style date tags and source/name consistency for [OpenHistoricalMap](https://www.openhistoricalmap.org/).

## What it does

<img width="778" height="320" alt="Monosnap Java OpenStreetMap Editor 2026-04-25 19-37-13" src="https://github.com/user-attachments/assets/3b5029f9-8325-4c71-a705-45af67e1976d" />


The plugin exposes four independently-toggleable tests in JOSM → Preferences → Data Validator → Tests:

**Date validation (`DateTagTest`)** checks `start_date`, `end_date`, and their `:edtf` and `:raw` siblings. It normalizes values to EDTF (ISO 8601-2), detects ambiguous inputs (decades vs. centuries, negative years, trailing hyphens), flags suspicious dates (year-boundary padding, far-future values, inverted start/end), handles Julian-calendar conversion, and reconciles mismatches between base tags and their `:edtf` counterparts (including an off-by-one flag for the common transcription typo). It catches calendar-invalid dates (Feb 30, June 31), packed-date typos (`YYYY-MMDD` missing the hyphen), and a wide vocabulary of casual shorthand — `mid-1930s`, `early C19`, `between 1880 and 1922`, `circa 1900`, `cYYYY`, dash- and dot-separated MDY/DMY date forms, etc. Most checks offer an autofix; a few require manual review.

**Chronology validation (`ChronologyTest`)** validates the structural consistency of `type=chronology` relations: members must lie within the parent's date range, must have the date tags they need (`start_date` always, `end_date` for every member except the youngest), must not have overlapping or gappy successor ranges, and must not duplicate their predecessor in both tags and geometry. Parent-vs-member range mismatches and gaps offer a recompute-from-envelope autofix (oldest member's start, latest member's end). The check also enforces that `type=boundary` chronology relations carry only relation members (not nodes or ways directly).

**Tag consistency (`TagConsistencyTest`)** checks names, source tags, and external-identifier references. It warns on named features missing a plain `name` or `wikidata` tag, enforces the OHM source-slot contract (`source` is URL or text, `source:name` is text only, `source:url` is URL only), consolidates conflicting URL/text values across those slots, splits multi-URL `source` values into enumerated `source:N` slots, and checks that `wikipedia` and `wikidata` tags are present when referenced by attribute-source keys. It also flags relations carrying a `role=label` member, since OHM renderers generate label points server-side and editor-supplied labels are usually unnecessary. The dates-in-names rule (`Wild West (1880-1922)`) cross-checks the name's date(s) against `start_date` / `end_date`, populating tags when they're absent and the name has a clean range. The plugin recognizes a Mapwarper source URL and splits it into `source:url` + `source:tiles` + an API-fetched `source:name`.

**Boundary geometry (`BoundaryTest`)** checks the structural cleanliness of features participating in `type=boundary` relations:
- Nodes carrying POI-style tags (`name`, `place`, `historic`, `wikidata`, etc.) on a boundary way → autofix moves the tags onto a new co-located standalone POI node.
- A `waterway=*` way that is also a boundary member → autofix creates a coincident clone with its own nodes to carry the boundary role, leaving the waterway untouched.
- Boundary relation members not in topological order → autofix sorts each role-group into ring order.
- A node shared between a boundary way and a non-boundary way → autofix clones the node onto the boundary side, detaching the boundary ways from the original.
- Same-class polygons (two `building=*` features, or two `boundary=administrative` features at the same `admin_level`) overlapping in BOTH time and space → unfixable warning; user reconciles.

**Autofix safety.** When the plugin offers an autofix, it never silently overwrites a populated user-authored tag. If a fix would clobber a populated companion (an existing `source:name`, an enumerated `source:N` / `source:N:name` slot, an existing `*_date:note`, etc.), the validator emits an unfixable warning instead, naming the conflicting tag so the editor can decide whether to merge, replace, or shift the new value to a different slot.

See [`docs/MESSAGES.md`](docs/MESSAGES.md) for the full list of validator messages, triggers, and autofixes.

## Installation

### From JOSM settings/preferences Plugin panel (recommended)

<img width="955" height="603" alt="Monosnap Preferences 2026-04-28 18-56-27" src="https://github.com/user-attachments/assets/4d7b34da-2c90-4465-8dd6-888f13a864a1" />

### From a release

This is the same place JOSM gets the plugin automatically.

1. Download `OHM_Tag_Validator.jar` from the
   [latest release](https://github.com/OpenHistoricalMap/ohm-josm-tag-validator/releases/latest/download/OHM_Tag_Validator.jar)
2. Copy the jar into JOSM's plugins directory:
   - **macOS:** `~/Library/JOSM/plugins/`
   - **Linux:** `~/.local/share/JOSM/plugins/`
   - **Windows:** `%APPDATA%\JOSM\plugins\`
3. Restart JOSM
4. Go to `Preferences → Plugins`, tick **OHM_Tag_Validator**, click OK

### From source

Java 17+ is required (a constraint of the `edtf-java` dependency;
recent JOSM versions already require Java 17). The plugin does **not**
vendor JOSM core or `edtf-java`; both are fetched on first build.

```bash
git clone https://github.com/OpenHistoricalMap/ohm-josm-tag-validator.git
cd ohm-josm-tag-validator
ant dist
# jar is produced in dist/OHM_Tag_Validator.jar (with edtf-java shaded in)
```

The first build downloads two JARs into `lib/`:

- `edtf-0.2.0.jar` from Maven Central — the
  [`edtf-java`](https://github.com/OpenHistoricalMap/edtf-java) library
  (`io.github.openhistoricalmap:edtf:0.2.0`, BSD 2-Clause), used for
  canonical EDTF parsing. It is shaded into the plugin JAR at build
  time, so JOSM users don't need to install anything extra. The shaded
  JAR preserves the upstream BSD notice under `META-INF/edtf-java/`;
  see `ATTRIBUTION.md` and `LICENSES/edtf-java-BSD-2-Clause.txt` for
  the full attribution.
- `josm-19555.jar` from josm.openstreetmap.de — the JOSM core JAR
  the plugin compiles against. Pinned to a specific revision so
  builds are reproducible. Bumping the pinned revision is a one-line
  change to `josm.version` in `build.xml`.

Subsequent builds reuse the cached JARs. The `lib/` directory is
gitignored — JARs are not checked in.

Output: `dist/OHM_Tag_Validator.jar`.

To run the regression test harness against `test/test_data.osm`:

```
ant test
```

#### Using a local JOSM source checkout

If you already have JOSM built from source (or want to compile against
a different revision), override the auto-fetch with `-Djosm=...`:

```
ant -Djosm=/absolute/path/to/josm-custom.jar dist
```

When `-Djosm=...` is set, the build skips the JOSM download entirely
and uses your file. If the path you supply doesn't exist, the build
fails fast with an explanatory message rather than silently fetching
the snapshot to your custom path.

#### Installing

```
ant install
```

This copies the jar into the JOSM plugins directory (`~/.josm/plugins/` on Linux, `~/Library/JOSM/plugins/` on macOS, `%APPDATA%/JOSM/plugins/` on Windows). Restart JOSM and enable the plugin in Preferences → Plugins.

## Using

After install: Validation → Validate (shortcut **V**) runs all enabled tests. Findings appear in the Validation Results panel. Select one or more and click "Fix" (or "Fix selected errors" for batch apply) to apply any available autofix.

All four tests (OHM date tags, OHM chronologies, OHM tag consistency, OHM boundaries) can be individually enabled or disabled in Preferences → Validator Tests.

### Companion paint style (optional)

The plugin ships a MapCSS paint style at `paint-styles/missing-start-date.mapcss` that visually highlights features which rule 4200 would flag — features that should carry a `start_date` but don't. Install it via Preferences → Map Settings → Map Paint Styles → "+", pointing at the file (locally, or by raw GitHub URL). It runs alongside whatever base style you have selected.

The plugin itself does not depend on this style. Validator findings still appear in the validator panel regardless of whether this style is installed.
