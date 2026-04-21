# ohm-tags — JOSM validator plugin for OpenHistoricalMap

Validates and normalizes OHM-style date tags and source/name consistency for [OpenHistoricalMap](https://www.openhistoricalmap.org/).

## What it does

**Date validation (`DateTagTest`)** checks `start_date`, `end_date`, and their `:edtf` and `:raw` siblings. It normalizes values to EDTF (ISO 8601-2), detects ambiguous inputs (decades vs. centuries, negative years, trailing hyphens), flags suspicious dates (year-boundary padding, far-future values, inverted start/end), handles Julian-calendar conversion, and reconciles mismatches between base tags and their `:edtf` counterparts. Most checks offer an autofix; a few require manual review.

**Tag consistency (`TagConsistencyTest`)** checks names, source tags, and external-identifier references. It warns on named features missing a plain `name` or `wikidata` tag, flags `source` values that are misformatted URLs or should be split into `source` / `source:name` / `source:N` keys, consolidates redundant `source:url` tags, and checks that `wikipedia` and `wikidata` tags are present when referenced by attribute-source keys.

See [`docs/MESSAGES.md`](docs/MESSAGES.md) for the full list of validator messages, triggers, and autofixes.

## Building

Assumes the JOSM source tree layout with the plugin directory at `josm/plugins/ohm-tags/` alongside a built `josm/core/dist/josm-custom.jar`. If your layout differs, override the `josm` property:

```
ant -Djosm=/path/to/josm-custom.jar dist
```

Output: `dist/ohm-tags.jar`.

To run the regression test harness against `test/test_data.osm`:

```
ant test
```

## Installing

```
ant install
```

This copies the jar into the JOSM plugins directory (`~/.josm/plugins/` on Linux, `~/Library/JOSM/plugins/` on macOS, `%APPDATA%/JOSM/plugins/` on Windows). Restart JOSM and enable the plugin in Preferences → Plugins.

## Using

After install: Validation → Validate (shortcut **V**) runs all enabled tests. Findings appear in the Validation Results panel. Select one or more and click "Fix" (or "Fix selected errors" for batch apply) to apply any available autofix.

Both tests can be individually enabled or disabled in Preferences → Validator Tests.