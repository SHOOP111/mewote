package io.lattice.weft;

import java.util.Map;
import java.util.TreeMap;

public record WeftSnapshot(Map<String, Mutation> effective, Map<String, Mutation> events) {
    public WeftSnapshot {
        effective = java.util.Collections.unmodifiableMap(new TreeMap<>(effective));
        events = java.util.Collections.unmodifiableMap(new TreeMap<>(events));
    }
    public boolean allows(String subjectAndCapability) {
        Mutation mutation = effective.get(subjectAndCapability);
        return mutation != null && mutation.kind() == Mutation.Kind.GRANT;
    }
}
