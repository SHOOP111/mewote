package io.lattice.warp;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable, normalized snapshot of values used by policy guards. */
public record EvaluationContext(
        String world,
        String region,
        String healthState,
        String gameMode,
        String networkPersona,
        Map<String, String> pluginValues) {

    public EvaluationContext {
        world = normalize(world);
        region = normalize(region);
        healthState = normalize(healthState);
        gameMode = normalize(gameMode);
        networkPersona = normalize(networkPersona);
        TreeMap<String, String> copy = new TreeMap<>();
        if (pluginValues != null) {
            pluginValues.forEach((key, value) -> {
                Objects.requireNonNull(key, "plugin context key");
                Objects.requireNonNull(value, "plugin context value");
                String normalizedKey = key.trim().toLowerCase(java.util.Locale.ROOT);
                if (!normalizedKey.matches("[a-z0-9_.-]{1,128}")) {
                    throw new IllegalArgumentException("Invalid plugin context key: " + key);
                }
                copy.put(normalizedKey, value);
            });
        }
        pluginValues = Map.copyOf(copy);
    }

    public static EvaluationContext empty() {
        return new EvaluationContext(null, null, null, null, null, Map.of());
    }

    public String value(String key) {
        return switch (key) {
            case "world" -> world;
            case "region" -> region;
            case "healthState" -> healthState;
            case "gameMode" -> gameMode;
            case "networkPersona" -> networkPersona;
            default -> key.startsWith("plugin.") ? pluginValues.get(key.substring("plugin.".length())) : null;
        };
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
