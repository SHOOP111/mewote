package io.lattice;

import io.lattice.cli.LatticeCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliTest {
    @TempDir Path temporary;

    @Test void initLintAndDemoAreAUsefulVerticalSlice() throws Exception {
        LatticeCli cli = new LatticeCli();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            assertEquals(0, cli.run(new String[]{"init", "--pack", "survival", "--directory", temporary.toString()}, out, err));
            Path policy = temporary.resolve("lattice.policy.json");
            assertTrue(Files.isRegularFile(policy));
            assertEquals(1, cli.run(new String[]{"init", "--pack", "survival", "--directory", temporary.toString()}, out, err));
            assertEquals(0, cli.run(new String[]{"lint", "--file", policy.toString()}, out, err));
            assertEquals(0, cli.run(new String[]{"demo"}, out, err));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("Heddle policy check"));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("Request access"));
            assertTrue(errors.toString(StandardCharsets.UTF_8).contains("already exists"));
        }
    }

    @Test void explainUsesStableSubjectAndPrintsEvidence() throws Exception {
        Path policy = temporary.resolve("policy.json");
        Files.writeString(policy, resource("/policies/survival.json"), StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
            int code = new LatticeCli().run(new String[]{"explain", policy.toString(),
                    "11111111-1111-4111-8111-111111111111", "minecraft.build.place", "--role", "builder",
                    "--world", "survival", "--region", "spawn"}, out, err);
            assertEquals(3, code);
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("spawn.build.barrier"));
        }
    }
}
