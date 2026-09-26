package io.lattice.cli;

import io.lattice.heddle.Compilation;
import io.lattice.heddle.Diagnostic;
import io.lattice.heddle.PolicyCompileException;
import io.lattice.heddle.PolicyCompiler;
import io.lattice.heddle.PolicyParser;
import io.lattice.selvedge.Selvedge;
import io.lattice.thread.Disclosure;
import io.lattice.thread.ExplanationRenderer;
import io.lattice.warp.Action;
import io.lattice.warp.Decision;
import io.lattice.warp.DecisionEngine;
import io.lattice.warp.DecisionRequest;
import io.lattice.warp.EvaluationContext;
import io.lattice.warp.SubjectId;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Ten-second local command surface for first-run, lint, explanation, and kernel demonstration. */
public final class LatticeCli {
    private static final String VERSION = "0.1.0-SNAPSHOT";
    private final PolicyCompiler compiler = new PolicyCompiler();

    public static void main(String[] args) {
        int code = new LatticeCli().run(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }

    public int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help") || args[0].equals("-h")) {
            usage(out);
            return 0;
        }
        try {
            return switch (args[0]) {
                case "version" -> { out.println("LATTICE " + VERSION); yield 0; }
                case "init" -> init(Arrays.copyOfRange(args, 1, args.length), out);
                case "lint" -> lint(Arrays.copyOfRange(args, 1, args.length), out);
                case "explain" -> explain(Arrays.copyOfRange(args, 1, args.length), out);
                case "demo" -> demo(out);
                case "bench" -> benchmark(Arrays.copyOfRange(args, 1, args.length), out);
                default -> { err.println("Unknown command: " + args[0]); usage(err); yield 2; }
            };
        } catch (PolicyCompileException invalid) {
            for (Diagnostic diagnostic : invalid.diagnostics()) {
                err.printf(Locale.ROOT, "%s %s %s — %s%n", diagnostic.severity(), diagnostic.code(), diagnostic.pointer(), diagnostic.message());
            }
            return 1;
        } catch (IOException | IllegalArgumentException | SecurityException failure) {
            err.println("LATTICE ERROR — " + failure.getMessage());
            return 1;
        }
    }

    private int init(String[] args, PrintStream out) throws IOException {
        String pack = option(args, "--pack");
        if (!"survival".equals(pack)) throw new IllegalArgumentException("Phase 0 ships the 'survival' pack only; requested: " + pack);
        String directory = option(args, "--directory");
        Path targetDirectory = directory == null ? Path.of(".") : Path.of(directory);
        boolean force = Arrays.asList(args).contains("--force");
        Files.createDirectories(targetDirectory);
        Path target = targetDirectory.resolve("lattice.policy.json");
        if (Files.exists(target) && !force) throw new IllegalArgumentException(target + " already exists; pass --force only after backing it up.");
        try (InputStream resource = LatticeCli.class.getResourceAsStream("/policies/survival.json")) {
            if (resource == null) throw new IOException("Starter policy resource is missing from this build");
            if (force) Files.copy(resource, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            else Files.copy(resource, target);
        }
        out.println("LATTICE initialized");
        out.println("  pack: survival");
        out.println("  policy: " + target.toAbsolutePath());
        out.println("  next: lat lint --file " + target);
        out.println("  note: review the pack and its lint findings before applying it to a live server.");
        return 0;
    }

    private int lint(String[] args, PrintStream out) throws IOException {
        Path file = policyFile(args, "lattice.policy.json");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Compilation compilation = compiler.compile(text);
        var parsed = new PolicyParser().parse(text);
        out.println("Heddle policy check");
        out.println("  file: " + file.toAbsolutePath());
        out.println("  policy: " + parsed.id() + " v" + parsed.version());
        out.println("  compiled rules: " + compilation.policy().rules().size());
        out.println("  index rules: " + compilation.policy().indexes().values().stream().mapToInt(index -> index.indexedRuleCount()).sum());
        if (compilation.diagnostics().isEmpty()) out.println("  findings: none");
        for (Diagnostic finding : compilation.diagnostics()) {
            out.printf(Locale.ROOT, "  %s %-24s %s — %s%n", finding.severity(), finding.code(), finding.pointer(), finding.message());
        }
        out.println(compilation.warningCount() == 0 ? "RESULT: READY FOR REVIEW" : "RESULT: REVIEW FINDINGS BEFORE APPLY");
        return 0;
    }

    private int explain(String[] args, PrintStream out) throws IOException {
        if (args.length < 3) throw new IllegalArgumentException("Usage: lat explain <policy.json> <subject-uuid> <action> [--role name] [--world id] [--region id]");
        Path file = Path.of(args[0]);
        SubjectId subject = SubjectId.java(UUID.fromString(args[1]));
        Action action = Action.of(args[2]);
        Set<String> directRoles = commaOption(args, "--role");
        String world = option(args, "--world");
        String region = option(args, "--region");
        var policy = compiler.compile(Files.readString(file, StandardCharsets.UTF_8)).policy();
        DecisionEngine engine = new DecisionEngine(policy, new Selvedge(), new io.lattice.quanta.Quanta(new io.lattice.quanta.BudgetService()));
        Set<String> effectiveRoles = engine.expandRoles(directRoles);
        DecisionRequest request = new DecisionRequest(subject, null, null, action, effectiveRoles,
                new EvaluationContext(world, region, null, null, null, java.util.Map.of()), Instant.now());
        Decision decision = engine.evaluate(request);
        out.println(new ExplanationRenderer().render(decision, Disclosure.STAFF, null, Locale.ENGLISH).plainText());
        return decision.allowed() ? 0 : 3;
    }

    private int benchmark(String[] args, PrintStream out) throws IOException {
        String outputPath = option(args, "--output");
        int operations = integerOption(args, "--operations", 10_000);
        int samples = integerOption(args, "--samples", 12);
        var report = new io.lattice.beacon.BenchmarkHarness(operations, samples).run();
        String json = report.toJson();
        if (outputPath != null) {
            Path output = Path.of(outputPath);
            if (output.getParent() != null) Files.createDirectories(output.getParent());
            Files.writeString(output, json, StandardCharsets.UTF_8);
            out.println("Benchmark evidence written to " + output.toAbsolutePath());
        }
        out.print(json);
        return 0;
    }

    private int demo(PrintStream out) throws IOException {
        try (InputStream input = LatticeCli.class.getResourceAsStream("/policies/survival.json")) {
            if (input == null) throw new IOException("Starter policy resource is missing from this build");
            var policy = compiler.compile(new String(input.readAllBytes(), StandardCharsets.UTF_8)).policy();
            DecisionEngine engine = new DecisionEngine(policy);
            SubjectId marcus = SubjectId.java(UUID.fromString("8fd9625c-d4c1-4d64-8cd7-412293117a91"));
            Set<String> roles = engine.expandRoles(Set.of("builder"));
            DecisionRequest request = new DecisionRequest(marcus, null, null, Action.of("minecraft.build.place"), roles,
                    new EvaluationContext("survival", "spawn", "healthy", "survival", "smp", java.util.Map.of()), Instant.now());
            Decision decision = engine.evaluate(request);
            out.println("LATTICE vertical-slice demonstration — sample identity and context");
            out.println(new ExplanationRenderer().render(decision, Disclosure.PUBLIC, "/request-access", Locale.ENGLISH).plainText());
            out.println("Evidence digest: " + decision.record().digest());
            out.println("Limit: this demonstration uses an in-memory budget service and a bundled sample policy.");
            return 0;
        }
    }

    private static Path policyFile(String[] args, String defaultName) {
        String value = option(args, "--file");
        if (value != null) return Path.of(value);
        for (int i = 0; i < args.length; i++) if (!args[i].startsWith("--")) return Path.of(args[i]);
        return Path.of(defaultName);
    }

    private static String option(String[] args, String option) {
        for (int i = 0; i < args.length; i++) if (args[i].equals(option)) {
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) throw new IllegalArgumentException(option + " requires a value");
            return args[i + 1];
        }
        return null;
    }

    private static int integerOption(String[] args, String option, int fallback) {
        String value = option(args, option);
        if (value == null) return fallback;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(option + " must be an integer"); }
    }

    private static Set<String> commaOption(String[] args, String option) {
        String value = option(args, option);
        if (value == null || value.isBlank()) return Set.of();
        Set<String> roles = new HashSet<>();
        for (String role : value.split(",")) roles.add(role.trim().toLowerCase(Locale.ROOT));
        return Set.copyOf(roles);
    }

    private static void usage(PrintStream out) {
        out.println("LATTICE — authority control for Minecraft networks");
        out.println("  lat init --pack survival [--directory path] [--force]");
        out.println("  lat lint [policy.json | --file policy.json]");
        out.println("  lat explain <policy.json> <subject-uuid> <action> [--role name] [--world id] [--region id]");
        out.println("  lat demo");
        out.println("  lat bench [--output report.json] [--operations 10000] [--samples 12]");
        out.println("  lat version");
    }
}
