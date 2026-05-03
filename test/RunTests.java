import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.data.validation.Test;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.io.OsmReader;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;
import org.openstreetmap.josm.tools.I18n;

import org.openstreetmap.josm.plugins.ohmtags.validation.DateTagTest;
import org.openstreetmap.josm.plugins.ohmtags.validation.TagConsistencyTest;

/**
 * Standalone regression harness. Loads one or more OSM files, runs both
 * validator tests against each, and prints one line per finding:
 *
 *   SEVERITY  TYPE/ID  TITLE  DESCRIPTION
 *
 * If no files are given on the command line, defaults to
 * {@code test/test_data.osm} and {@code test/crasher_braces.osm}. Findings
 * from all fixtures are merged and sorted before printing, so the output
 * is deterministic and suitable for golden-file diffing.
 *
 * Exit 0 on success (findings in the data are expected). Non-zero means the
 * harness itself crashed.
 */
public class RunTests {

    public static void main(String[] args) throws Exception {
        // Minimal JOSM bootstrap — enough for headless validation.
        Config.setPreferencesInstance(new MemoryPreferences());
        I18n.set("en");
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:4326"));

        String[] osmFiles = args.length > 0 ? args : new String[] {
            "test/test_data.osm",
            "test/crasher_braces.osm"
        };

        List<String> lines = new ArrayList<>();

        for (String osmFile : osmFiles) {
            DataSet ds;
            try (InputStream in = new FileInputStream(osmFile)) {
                ds = OsmReader.parseDataSet(in, NullProgressMonitor.INSTANCE);
            }

            // Fresh test instances per fixture so error state doesn't leak
            // across files.
            List<Test> tests = Arrays.asList(new DateTagTest(), new TagConsistencyTest());

            for (Test test : tests) {
                test.startTest(NullProgressMonitor.INSTANCE);
                for (Node n : ds.getNodes()) test.visit(n);
                for (Way w : ds.getWays()) test.visit(w);
                for (Relation r : ds.getRelations()) test.visit(r);
                test.endTest();

                for (TestError e : test.getErrors()) {
                    Collection<? extends OsmPrimitive> prims = e.getPrimitives();
                    Stream<String> rows;
                    if (prims.isEmpty()) {
                        rows = Stream.of(formatLine(e, null));
                    } else {
                        rows = prims.stream().map(p -> formatLine(e, p));
                    }
                    rows.forEach(lines::add);
                }
            }
        }

        lines.stream().sorted().forEach(System.out::println);
        System.exit(0);
    }

    private static String formatLine(TestError e, OsmPrimitive p) {
        String sev = e.getSeverity().name().substring(0, 1);
        String primField = (p == null) ? "-/-" : primAbbrev(p) + "/" + p.getId();
        String desc = (e.getDescription() != null) ? e.getDescription() : "";
        return String.join("   ", sev, primField, e.getMessage(), desc);
    }

    private static String primAbbrev(OsmPrimitive p) {
        switch (p.getType()) {
            case NODE:     return "n";
            case WAY:      return "w";
            case RELATION: return "r";
            default:       return p.getType().getAPIName();
        }
    }
}
