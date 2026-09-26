package io.lattice.heddle;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lattice.quanta.BudgetSpec;
import io.lattice.warp.ActionPattern;
import io.lattice.warp.Authority;
import io.lattice.warp.PolicyRule;
import io.lattice.warp.RoleDefinition;
import io.lattice.warp.RuleBand;
import io.lattice.warp.RuleEffect;
import io.lattice.warp.RuleGuard;
import io.lattice.warp.SubjectId;
import io.lattice.warp.WeeklySchedule;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Strict Heddle v1 JSON parser. Unknown and duplicate fields are errors, not ignored input. */
public final class PolicyParser {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    public PolicySource parse(String source) {
        JsonNode root;
        try {
            root = JSON.readTree(source);
        } catch (IOException exception) {
            throw error("JSON_SYNTAX", "/", exception.getMessage() == null ? "Malformed JSON input." : exception.getMessage());
        }
        object(root, "/");
        fields(root, Set.of("schemaVersion", "meta", "roles", "rules"), "/");
        int schema = integer(required(root, "schemaVersion", "/"), "/schemaVersion");
        JsonNode metaNode = required(root, "meta", "/");
        object(metaNode, "/meta");
        fields(metaNode, Set.of("id", "version", "description", "labels"), "/meta");
        String id = text(required(metaNode, "id", "/meta"), "/meta/id");
        String version = text(required(metaNode, "version", "/meta"), "/meta/version");
        String description = text(required(metaNode, "description", "/meta"), "/meta/description");
        Map<String, String> labels = parseLabels(metaNode.path("labels"));
        Map<String, RoleDefinition> roles = parseRoles(required(root, "roles", "/"));
        List<PolicyRule> rules = parseRules(required(root, "rules", "/"));
        return new PolicySource(schema, id, version, description, labels, roles, rules);
    }

    private Map<String, String> parseLabels(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return Map.of();
        object(node, "/meta/labels");
        Map<String, String> labels = new TreeMap<>();
        node.fields().forEachRemaining(entry -> labels.put(entry.getKey(), text(entry.getValue(), "/meta/labels/" + entry.getKey())));
        return labels;
    }

    private Map<String, RoleDefinition> parseRoles(JsonNode node) {
        object(node, "/roles");
        Map<String, RoleDefinition> roles = new TreeMap<>();
        node.fields().forEachRemaining(entry -> {
            String roleId = entry.getKey();
            String pointer = "/roles/" + escapePointer(roleId);
            JsonNode definition = entry.getValue();
            object(definition, pointer);
            fields(definition, Set.of("inherits", "ceiling", "overrides"), pointer);
            Set<String> parents = new HashSet<>(stringArray(required(definition, "inherits", pointer), pointer + "/inherits"));
            JsonNode ceilingsNode = required(definition, "ceiling", pointer);
            List<ActionPattern> ceilings = new ArrayList<>();
            List<String> ceilingSources = stringArray(ceilingsNode, pointer + "/ceiling");
            for (int i = 0; i < ceilingSources.size(); i++) {
                try {
                    ceilings.add(ActionPattern.parse(ceilingSources.get(i)));
                } catch (IllegalArgumentException invalid) {
                    throw error("INVALID_PATTERN", pointer + "/ceiling/" + i, invalid.getMessage());
                }
            }
            Set<String> overrides = new HashSet<>();
            if (definition.has("overrides")) overrides.addAll(stringArray(definition.get("overrides"), pointer + "/overrides"));
            try {
                RoleDefinition role = new RoleDefinition(roleId, parents, ceilings, overrides);
                if (roles.putIfAbsent(role.id(), role) != null) throw error("DUPLICATE_ROLE", pointer, "Role IDs are unique after canonicalization.");
            } catch (IllegalArgumentException invalid) {
                throw error("INVALID_ROLE", pointer, invalid.getMessage());
            }
        });
        return roles;
    }

    private List<PolicyRule> parseRules(JsonNode node) {
        if (!node.isArray()) throw error("TYPE_MISMATCH", "/rules", "Expected an array of rules.");
        List<PolicyRule> rules = new ArrayList<>();
        for (int index = 0; index < node.size(); index++) {
            String pointer = "/rules/" + index;
            JsonNode ruleNode = node.get(index);
            object(ruleNode, pointer);
            fields(ruleNode, Set.of("id", "action", "effect", "band", "authority", "role", "subject", "priority", "guard", "budget"), pointer);
            String id = text(required(ruleNode, "id", pointer), pointer + "/id");
            ActionPattern action;
            try {
                action = ActionPattern.parse(text(required(ruleNode, "action", pointer), pointer + "/action"));
            } catch (IllegalArgumentException invalid) {
                throw error("INVALID_PATTERN", pointer + "/action", invalid.getMessage());
            }
            RuleEffect effect = enumValue(RuleEffect.class, required(ruleNode, "effect", pointer), pointer + "/effect");
            RuleBand band = enumValue(RuleBand.class, required(ruleNode, "band", pointer), pointer + "/band");
            Authority authority = enumValue(Authority.class, required(ruleNode, "authority", pointer), pointer + "/authority");
            String role = ruleNode.has("role") ? text(ruleNode.get("role"), pointer + "/role").toLowerCase(java.util.Locale.ROOT) : null;
            SubjectId subject = null;
            if (ruleNode.has("subject")) {
                try {
                    subject = new SubjectId(SubjectId.Kind.JAVA_UUID, text(ruleNode.get("subject"), pointer + "/subject"));
                } catch (IllegalArgumentException invalid) {
                    throw error("INVALID_SUBJECT_ID", pointer + "/subject", "Policy subjects must use a canonical Java UUID; display names are not identities.");
                }
            }
            if (role != null && subject != null) throw error("AMBIGUOUS_SUBJECT", pointer, "A rule may target one role or one stable subject, not both.");
            int priority = ruleNode.has("priority") ? integer(ruleNode.get("priority"), pointer + "/priority") : 0;
            RuleGuard guard = ruleNode.has("guard") ? parseGuard(ruleNode.get("guard"), pointer + "/guard") : RuleGuard.any();
            BudgetSpec budget = ruleNode.has("budget") ? parseBudget(ruleNode.get("budget"), pointer + "/budget") : null;
            try {
                rules.add(new PolicyRule(id, action, effect, band, authority, role, subject, priority, index, guard, budget));
            } catch (IllegalArgumentException invalid) {
                throw error("INVALID_RULE", pointer, invalid.getMessage());
            }
        }
        return rules;
    }

    private RuleGuard parseGuard(JsonNode node, String pointer) {
        object(node, pointer);
        Set<String> allowed = Set.of("world", "region", "healthState", "gameMode", "networkPersona", "plugin", "notBefore", "expiresAt", "schedule");
        fields(node, allowed, pointer);
        Map<String, Set<String>> expected = new TreeMap<>();
        for (String key : List.of("world", "region", "healthState", "gameMode", "networkPersona")) {
            if (node.has(key)) expected.put(key, new HashSet<>(stringArray(node.get(key), pointer + "/" + key)));
        }
        if (node.has("plugin")) {
            JsonNode plugin = node.get("plugin");
            object(plugin, pointer + "/plugin");
            plugin.fields().forEachRemaining(entry -> expected.put("plugin." + entry.getKey().toLowerCase(java.util.Locale.ROOT),
                    new HashSet<>(stringArray(entry.getValue(), pointer + "/plugin/" + entry.getKey()))));
        }
        Instant notBefore = node.has("notBefore") ? instant(node.get("notBefore"), pointer + "/notBefore") : null;
        Instant expiresAt = node.has("expiresAt") ? instant(node.get("expiresAt"), pointer + "/expiresAt") : null;
        WeeklySchedule schedule = node.has("schedule") ? parseSchedule(node.get("schedule"), pointer + "/schedule") : null;
        try {
            return new RuleGuard(expected, notBefore, expiresAt, schedule);
        } catch (IllegalArgumentException invalid) {
            throw error("INVALID_GUARD", pointer, invalid.getMessage());
        }
    }

    private WeeklySchedule parseSchedule(JsonNode node, String pointer) {
        object(node, pointer);
        fields(node, Set.of("zone", "days", "start", "end"), pointer);
        ZoneId zone;
        try { zone = ZoneId.of(text(required(node, "zone", pointer), pointer + "/zone")); }
        catch (RuntimeException invalid) { throw error("INVALID_TIME_ZONE", pointer + "/zone", invalid.getMessage()); }
        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String day : stringArray(required(node, "days", pointer), pointer + "/days")) {
            try { days.add(DayOfWeek.valueOf(day)); }
            catch (IllegalArgumentException invalid) { throw error("INVALID_WEEKDAY", pointer + "/days", "Use an uppercase ISO weekday such as MONDAY."); }
        }
        LocalTime start = localTime(required(node, "start", pointer), pointer + "/start");
        LocalTime end = localTime(required(node, "end", pointer), pointer + "/end");
        try { return new WeeklySchedule(zone, days, start, end); }
        catch (IllegalArgumentException invalid) { throw error("INVALID_SCHEDULE", pointer, invalid.getMessage()); }
    }

    private BudgetSpec parseBudget(JsonNode node, String pointer) {
        object(node, pointer);
        fields(node, Set.of("capacity", "refillTokens", "refillPeriodSeconds"), pointer);
        int capacity = integer(required(node, "capacity", pointer), pointer + "/capacity");
        int refill = integer(required(node, "refillTokens", pointer), pointer + "/refillTokens");
        int seconds = integer(required(node, "refillPeriodSeconds", pointer), pointer + "/refillPeriodSeconds");
        try { return new BudgetSpec(capacity, refill, Duration.ofSeconds(seconds)); }
        catch (RuntimeException invalid) { throw error("INVALID_BUDGET", pointer, invalid.getMessage()); }
    }

    private static JsonNode required(JsonNode node, String field, String pointer) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) throw error("MISSING_FIELD", pointer + "/" + field, "Required field is missing.");
        return value;
    }

    private static void object(JsonNode node, String pointer) {
        if (node == null || !node.isObject()) throw error("TYPE_MISMATCH", pointer, "Expected an object.");
    }

    private static void fields(JsonNode node, Set<String> allowed, String pointer) {
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw error("UNKNOWN_FIELD", pointer + "/" + escapePointer(name), "Unknown field. Remove it or upgrade the schema explicitly.");
        });
    }

    private static String text(JsonNode node, String pointer) {
        if (node == null || !node.isTextual() || node.textValue().isBlank()) throw error("TYPE_MISMATCH", pointer, "Expected a non-empty string.");
        return node.textValue();
    }

    private static int integer(JsonNode node, String pointer) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) throw error("TYPE_MISMATCH", pointer, "Expected a 32-bit integer.");
        return node.intValue();
    }

    private static List<String> stringArray(JsonNode node, String pointer) {
        if (node == null || !node.isArray()) throw error("TYPE_MISMATCH", pointer, "Expected an array of strings.");
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            String value = text(node.get(i), pointer + "/" + i);
            if (!seen.add(value)) throw error("DUPLICATE_VALUE", pointer + "/" + i, "Duplicate values are not meaningful here.");
            result.add(value);
        }
        return result;
    }

    private static Instant instant(JsonNode node, String pointer) {
        try { return Instant.parse(text(node, pointer)); }
        catch (RuntimeException invalid) { throw error("INVALID_INSTANT", pointer, "Use an ISO-8601 UTC instant, for example 2026-01-31T12:00:00Z."); }
    }

    private static LocalTime localTime(JsonNode node, String pointer) {
        try { return LocalTime.parse(text(node, pointer)); }
        catch (RuntimeException invalid) { throw error("INVALID_LOCAL_TIME", pointer, "Use 24-hour HH:mm local time."); }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, JsonNode node, String pointer) {
        try { return Enum.valueOf(type, text(node, pointer)); }
        catch (RuntimeException invalid) { throw error("INVALID_ENUM", pointer, "Expected one of: " + java.util.Arrays.toString(type.getEnumConstants())); }
    }

    private static String escapePointer(String value) { return value.replace("~", "~0").replace("/", "~1"); }

    private static PolicyCompileException error(String code, String pointer, String message) {
        return new PolicyCompileException(List.of(new Diagnostic(Diagnostic.Severity.ERROR, code, pointer, message)));
    }
}
