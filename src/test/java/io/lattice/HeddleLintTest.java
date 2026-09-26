package io.lattice;

import io.lattice.heddle.Diagnostic;
import org.junit.jupiter.api.Test;

import static io.lattice.TestSupport.compile;
import static io.lattice.TestSupport.rule;
import static org.junit.jupiter.api.Assertions.*;

class HeddleLintTest {
    @Test void linterFlagsWideWildcardsShadowingAndOpposingRegionRules() {
        String rules = String.join(",",
                rule("broad", "**", "ALLOW", "EXPLICIT", "NETWORK", ""),
                rule("duplicate-later", "**", "ALLOW", "EXPLICIT", "NETWORK", ""),
                rule("region-allow", "game.build.place", "ALLOW", "EXPLICIT", "SPATIAL", ",\"guard\":{\"region\":[\"spawn\"]}"),
                rule("region-deny", "game.build.place", "DENY", "EXPLICIT", "SPATIAL", ",\"guard\":{\"region\":[\"market\"]}"));
        var diagnostics = compile("{}", rules).diagnostics();
        assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals("BROAD_WILDCARD") && d.severity() == Diagnostic.Severity.WARNING));
        assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals("SHADOWED_RULE")));
        assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals("REGION_UNION_REVIEW")));
    }
}
