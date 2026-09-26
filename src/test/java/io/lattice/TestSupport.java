package io.lattice;

import io.lattice.heddle.Compilation;
import io.lattice.heddle.PolicyCompiler;
import io.lattice.warp.DecisionEngine;
import io.lattice.warp.SubjectId;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

final class TestSupport {
    static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");
    static final SubjectId SUBJECT = SubjectId.java(UUID.fromString("11111111-1111-4111-8111-111111111111"));
    private TestSupport() { }

    static Compilation compile(String roles, String rules) {
        String source = "{\"schemaVersion\":1,\"meta\":{\"id\":\"test\",\"version\":\"1\",\"description\":\"fixture\"},"
                + "\"roles\":" + roles + ",\"rules\":[" + rules + "]}";
        return new PolicyCompiler().compile(source);
    }

    static String rule(String id, String action, String effect, String band, String authority, String more) {
        return "{\"id\":\"" + id + "\",\"action\":\"" + action + "\",\"effect\":\"" + effect
                + "\",\"band\":\"" + band + "\",\"authority\":\"" + authority + "\"" + more + "}";
    }

    static DecisionEngine engine(String roles, String rules) { return new DecisionEngine(compile(roles, rules).policy()); }

    static String resource(String name) throws Exception {
        try (var input = TestSupport.class.getResourceAsStream(name)) {
            if (input == null) throw new IllegalArgumentException("Missing test resource " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
