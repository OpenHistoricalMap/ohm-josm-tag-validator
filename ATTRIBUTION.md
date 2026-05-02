# Attribution

## Derivative work acknowledgment

The date-normalization logic in `DateNormalizer.java` is derived from
`utilEDTFFromOSMDateString()` in the OpenHistoricalMap fork of the iD
editor, authored by Minh Nguyễn. The original implementation (in
JavaScript) established the regex patterns, EDTF output conventions,
and handling of circa markers, decades, centuries, thirds, and
open-ended ranges.

This Java port reimplements those patterns and extends them with
additional cases (Julian-calendar conversion, natural-language season
expressions, additional shorthand forms). Bugs in the port are the
port author's responsibility, not the original author's.

- Source: https://github.com/OpenHistoricalMap/iD
- Originating commit: https://github.com/OpenHistoricalMap/iD/commit/4d5cb19
- Original license: ISC (see `LICENSES/iD-ISC.txt`)

## Bundled third-party library: edtf-java

The plugin's distributed JAR (`dist/OHM_Tag_Validator.jar`) shades in the
[`edtf-java`](https://github.com/OpenHistoricalMap/edtf-java) library
(`io.github.openhistoricalmap:edtf:0.2.0`), used as the source of
truth for EDTF parsing, validation, and bound extraction. The
library's BSD 2-Clause license requires that the copyright notice
accompany binary redistributions; the upstream `LICENSE`, `NOTICE`,
`ATTRIBUTION.md`, and `LICENSE-edtf.js.txt` files are preserved
inside the shaded JAR under `META-INF/edtf-java/`.

`edtf-java` itself derives from
[`edtf.js`](https://github.com/inukshuk/edtf.js) (also BSD 2-Clause).

- Library source: https://github.com/OpenHistoricalMap/edtf-java
- Maven Central: `io.github.openhistoricalmap:edtf:0.2.0`
- Library license: BSD 2-Clause (see `LICENSES/edtf-java-BSD-2-Clause.txt`)
- Upstream JS library license: BSD 2-Clause (preserved in the shaded
  JAR's `META-INF/edtf-java/LICENSE-edtf.js.txt`)

## AI-assisted development

This project was developed with substantial assistance from Claude
(Anthropic's AI assistant), via both the Claude web interface and
Claude Code.

### What Claude contributed

- Initial plugin skeleton, `build.xml`, and Ant targets
- Design and implementation of `DateNormalizer.java`, including EDTF
  parsing, Julian-calendar conversion, season-code handling, and the
  multi-rule parsing pipeline
- Implementation of both validator test classes (`DateTagTest` and
  `TagConsistencyTest`), including the tagcleanupbot rollback logic,
  the backslash-signature rules, the `:edtf` normalization flow, and
  the source-tag consolidation rules
- Drafting and iteration of all user-facing validator message titles
  and descriptions
- Generating inventories, summaries, and intermediate documentation
  during design discussions

### What the human author contributed

- Project scope, direction, and OHM-specific domain knowledge
- All decisions about validator triggers, fix behavior, message
  wording, and severity levels
- Review and testing against real OpenHistoricalMap data, including
  edge cases collected from production OHM editing
- Judgment calls on OHM tagging conventions where the wiki was
  ambiguous or silent
- Final approval on all code, messages, and behavior

### Reviewing AI contributions

All code in this repository has been reviewed and tested by the
author. Bugs, design flaws, and behavior issues are the author's
responsibility; the presence of AI assistance in development does
not transfer authorship or liability.

Commits made directly by Claude Code carry a `Co-Authored-By:` trailer
naming Claude as a co-author (with the model identifier where
available, e.g. `Co-Authored-By: Claude Opus 4.7 (1M context)
<noreply@anthropic.com>`), making AI involvement traceable at the
commit level.

### Why this notice exists

There is no established convention yet for disclosing AI assistance
in open-source projects. This notice exists because:

1. This plugin encodes opinions about OpenHistoricalMap tagging
   conventions that affect other mappers' work. Readers deserve to
   know that those opinions were developed in conversation with an
   AI, even though the author made the final calls.
2. Transparency about development process is consistent with the
   OSM/OHM community's values around data provenance and open
   contribution.
3. If you are considering using this code in a context where AI
   provenance matters to you, you should know.

### Questions or concerns

If you have concerns about AI-assisted code in this project, please
open an issue on the project's repository. Contributions, bug
reports, and pull requests are welcome regardless of whether they
involve AI tooling; please disclose AI involvement in your own
contributions if it was substantial.
