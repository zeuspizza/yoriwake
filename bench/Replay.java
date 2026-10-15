import io.github.zeuspizza.yoriwake.agent.contract.AgentContract;
import io.github.zeuspizza.yoriwake.agent.select.FilterRule;
import io.github.zeuspizza.yoriwake.agent.select.Selector;
import io.github.zeuspizza.yoriwake.agent.select.Verdict;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-decides recorded tests with the CURRENT selector, offline: no Gradle, no guest build.
 *
 * <p>Run by bench/replay_decisions.py as a single-file source program against the agent jar,
 * so one JVM answers every row. It holds no selection logic of its own: the decision and each
 * test's verdict come from the same {@link FilterRule} methods the test JVM calls, which is the
 * only way a replay stays a replay of the shipped rule rather than a second opinion that drifts.
 *
 * <p>Reads a manifest of tab-separated jobs -- map directory, inputs file, ids file, output file --
 * and writes one {@code id <TAB> verdict <TAB> reason} line per id to each output file. An inputs
 * file holds the filter's system properties as {@code key <TAB> value} lines, exactly as the agent
 * recorded them. An ids file holds one {@code id <TAB> kind} per line, kind {@code test} or
 * {@code container}.
 *
 * <p>A job the current selector cannot faithfully re-decide writes {@code <output>.refused}
 * instead, one line saying why: a record missing an input the rule now reads (defaulting it would
 * answer for inputs the arm never had), or a map of another schema version (which the rule reads
 * as no map at all, so the answer would describe a map the arm did not select against).
 */
public final class Replay {

    public static void main(String[] args) throws IOException {
        for (String job : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            if (job.isBlank()) {
                continue;
            }
            String[] f = job.split("\t", -1);
            Map<String, String> inputs = new HashMap<>();
            for (String line : Files.readAllLines(Path.of(f[1]), StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    inputs.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
            List<String> missing = new ArrayList<>();
            for (String input : FilterRule.INPUTS) {
                if (!inputs.containsKey(input)) {
                    missing.add(input);
                }
            }
            if (!missing.isEmpty()) {
                refuse(f[3], "the record carries no input for " + String.join(", ", missing)
                        + ": recorded by an agent that did not read it");
                continue;
            }
            Path schema = Path.of(f[0], AgentContract.MAP_SCHEMA_VERSION_FILE);
            String found = Files.isRegularFile(schema)
                    ? Files.readString(schema, StandardCharsets.UTF_8).trim() : "none";
            if (!String.valueOf(AgentContract.MAP_SCHEMA_VERSION).equals(found)) {
                refuse(f[3], "the kept map is schema version " + found + ", the current selector reads "
                        + AgentContract.MAP_SCHEMA_VERSION);
                continue;
            }
            Selector.Decision decision =
                    FilterRule.decisionFrom(key -> inputs.getOrDefault(key, ""), new File(f[0]));
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(Path.of(f[2]), StandardCharsets.UTF_8)) {
                if (line.isEmpty()) {
                    continue;
                }
                int tab = line.lastIndexOf('\t');
                String id = line.substring(0, tab);
                boolean isTest = !"container".equals(line.substring(tab + 1));
                Verdict verdict = FilterRule.verdictFor(
                        key -> inputs.getOrDefault(key, ""), decision, id, isTest);
                out.add(id + "\t" + verdict.inclusionToken() + "\t" + verdict.reasonToken());
            }
            Files.write(Path.of(f[3]), out, StandardCharsets.UTF_8);
        }
    }

    private static void refuse(String output, String why) throws IOException {
        Files.writeString(Path.of(output + ".refused"), why + "\n", StandardCharsets.UTF_8);
    }
}
