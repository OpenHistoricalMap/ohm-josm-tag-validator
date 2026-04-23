#  ohm-josm-tag-validator — JOSM validator plugin for OpenHistoricalMap

Validates and normalizes OHM-style date tags and source/name consistency for [OpenHistoricalMap](https://www.openhistoricalmap.org/).

## What it does

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

This plugin compiles against JOSM core. You need a built
`josm-custom.jar` somewhere on disk; the easiest source is a checkout of
[JOSM](https://josm.openstreetmap.de/wiki/Source) built with `ant dist`
in its `core/` subdirectory. The plugin does **not** vendor JOSM core
— keep your JOSM checkout outside this repository so it stays
independently updatable.

```bash
git clone https://github.com/OpenHistoricalMap/ohm-josm-tag-validator.git
cd ohm-josm-tag-validator
ant -Djosm=/path/to/josm-custom.jar dist
# jar is produced in dist/ohm-tags.jar
```

#### Building

The default value of the `josm` property in `build.xml` is
`../../josm/core/dist/josm-custom.jar`, which assumes your plugin
checkout lives at `josm-dev/plugins/ohm-josm-tag-validator/` next to a
sibling `josm-dev/josm/` JOSM checkout. If your layout differs, override
on the command line:

```
ant -Djosm=/path/to/josm-custom.jar dist
```

Output: `dist/ohm-tags.jar`.

To run the regression test harness against `test/test_data.osm`:

```
ant -Djosm=/path/to/josm-custom.jar test
```

If you'd rather drop a copy of `josm-custom.jar` directly inside this
repo (e.g. for sandboxed development), put it under `core/dist/` —
that path is gitignored, so it won't accidentally end up in commits.

#### Installing

```
ant install
```

This copies the jar into the JOSM plugins directory (`~/.josm/plugins/` on Linux, `~/Library/JOSM/plugins/` on macOS, `%APPDATA%/JOSM/plugins/` on Windows). Restart JOSM and enable the plugin in Preferences → Plugins.

## Using

After install: Validation → Validate (shortcut **V**) runs all enabled tests. Findings appear in the Validation Results panel. Select one or more and click "Fix" (or "Fix selected errors" for batch apply) to apply any available autofix.

Both tests can be individually enabled or disabled in Preferences → Validator Tests.