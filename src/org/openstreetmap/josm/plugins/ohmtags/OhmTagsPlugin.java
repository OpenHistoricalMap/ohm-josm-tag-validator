// License: GPL v2 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.ohmtags;

import org.openstreetmap.josm.data.validation.OsmValidator;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.ohmtags.validation.BoundaryTest;
import org.openstreetmap.josm.plugins.ohmtags.validation.ChronologyTest;
import org.openstreetmap.josm.plugins.ohmtags.validation.DateTagTest;
import org.openstreetmap.josm.plugins.ohmtags.validation.TagConsistencyTest;

/**
 * Plugin entry point. Registers the OHM validator tests:
 * <ul>
 *   <li>{@link DateTagTest} — date tag normalization and triple-consistency</li>
 *   <li>{@link ChronologyTest} — structural consistency of type=chronology relations</li>
 *   <li>{@link TagConsistencyTest} — name / wikidata / source checks</li>
 *   <li>{@link BoundaryTest} — boundary geometry hygiene + same-class polygon overlap</li>
 * </ul>
 */
public class OhmTagsPlugin extends Plugin {

    public OhmTagsPlugin(PluginInformation info) {
        super(info);
        OsmValidator.addTest(DateTagTest.class);
        OsmValidator.addTest(ChronologyTest.class);
        OsmValidator.addTest(TagConsistencyTest.class);
        OsmValidator.addTest(BoundaryTest.class);
    }
}
