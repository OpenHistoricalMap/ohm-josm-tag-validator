#  ohm-josm-tag-validator — JOSM validator plugin for OpenHistoricalMap

Validates and normalizes OHM-style date tags and source/name consistency for [OpenHistoricalMap](https://www.openhistoricalmap.org/).

## What it does

<img width="632" height="218" alt="Monosnap Java OpenStreetMap Editor 2026-04-20 20-23-34" src="https://github.com/user-attachments/assets/4ea05143-d9e8-482f-89ef-22d779a508e5" />

**Date validation (`DateTagTest`)** checks `start_date`, `end_date`, and their `:edtf` and `:raw` siblings. It normalizes values to EDTF (ISO 8601-2), detects ambiguous inputs (decades vs. centuries, negative years, trailing hyphens), flags suspicious dates (year-boundary padding, far-future values, inverted start/end), handles Julian-calendar conversion, and reconciles mismatches between base tags and their `:edtf` counterparts. Most checks offer an autofix; a few require manual review.

**Tag consistency (`TagConsistencyTest`)** checks names, source tags, and external-identifier references. It warns on named features missing a plain `name` or `wikidata` tag, flags `source` values that are misformatted URLs or should be split into `source` / `source:name` / `source:N` keys, consolidates redundant `source:url` tags, and checks that `wikipedia` and `wikidata` tags are present when referenced by attribute-source keys.

See [`docs/MESSAGES.md`](docs/MESSAGES.md) for the full list of validator messages, triggers, and autofixes.

## Installation

### From a release (recommended)

1. Download `ohm-tags.jar` from the
   [latest release](https://github.com/OpenHistoricalMap/ohm-josm-tag-validator/releases/latest)
2. Copy the jar into JOSM's plugins directory:
   - **macOS:** `~/Library/JOSM/plugins/`
   - **Linux:** `~/.local/share/JOSM/plugins/`
   - **Windows:** `%APPDATA%\JOSM\plugins\`
3. Restart JOSM
4. Go to `Preferences → Plugins`, tick **ohm-tags**, click OK

### From source

Java 17+ is required (a constraint of the `edtf-java` dependency;
recent JOSM versions already require Java 17). The plugin does **not**
vendor JOSM core or `edtf-java`; both are fetched on first build.

```bash
git clone https://github.com/OpenHistoricalMap/ohm-josm-tag-validator.git
cd ohm-josm-tag-validator
ant dist
# jar is produced in dist/ohm-tags.jar (with edtf-java shaded in)
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

Output: `dist/ohm-tags.jar`.

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

Both tests can be individually enabled or disabled in Preferences → Validator Tests.
