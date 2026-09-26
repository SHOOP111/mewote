package io.lattice.skein;

import java.util.Map;
import java.util.TreeMap;

public record StateSnapshot(long sequence, Map<String, RecoveredValue> values) {
    public StateSnapshot { values = java.util.Collections.unmodifiableMap(new TreeMap<>(values)); }
    public record RecoveredValue(String value, boolean erased) { }
}
