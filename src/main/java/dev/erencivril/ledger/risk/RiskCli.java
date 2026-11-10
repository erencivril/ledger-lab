package dev.erencivril.ledger.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.erencivril.ledger.domain.risk.RuleCatalog;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Run with Gradle riskBacktest or riskGenerate. No database or Redis is needed. */
public final class RiskCli {
    private RiskCli() {}
    static ObjectMapper jsonMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule())
                .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);
    }
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "backtest";
        boolean fromFile = mode.equals("backtest-file");
        int count = fromFile ? 0 : args.length > 1 ? Integer.parseInt(args[1]) : 10000;
        long seed = fromFile ? 0 : args.length > 2 ? Long.parseLong(args[2]) : 42;
        ObjectMapper json = jsonMapper();
        java.util.List<SyntheticData.Sample> samples;
        if (fromFile) {
            if (args.length != 3) throw new IllegalArgumentException("backtest-file INPUT.ndjson K");
            samples = new java.util.ArrayList<>();
            try (var input = Files.newBufferedReader(Path.of(args[1]), StandardCharsets.UTF_8)) {
                String line;
                while ((line = input.readLine()) != null) {
                    if (samples.size() >= 1_000_000) throw new IllegalArgumentException("Too many observations");
                    samples.add(json.readValue(line, SyntheticData.Sample.class));
                }
            }
        } else {
            samples = SyntheticData.generate(count, seed);
        }
        if (mode.equals("generate")) {
            if (args.length != 4) throw new IllegalArgumentException("generate COUNT SEED OUTPUT.ndjson");
            try (BufferedWriter out = Files.newBufferedWriter(Path.of(args[3]), StandardCharsets.UTF_8)) {
                for (var sample : samples) {
                    out.write(json.writeValueAsString(sample));
                    out.newLine();
                }
            }
            System.out.println("Wrote " + samples.size() + " synthetic observations, seed=" + seed);
            return;
        }
        if (!mode.equals("backtest") && !fromFile) throw new IllegalArgumentException("expected backtest or generate");
        int k = fromFile ? Integer.parseInt(args[2]) : args.length > 3 ? Integer.parseInt(args[3]) : 100;
        System.out.println("Synthetic heuristic evaluation only. count=" + samples.size()
                + (fromFile ? ", input=" + args[1] : ", seed=" + seed));
        System.out.println("version,tp,fp,tn,fn,precision,recall,k,precision_at_k");
        for (String resource : RuleSetLoader.BUNDLED) {
            String yaml;
            try (var input = RiskCli.class.getClassLoader().getResourceAsStream(resource)) {
                if (input == null) throw new IllegalStateException("Missing " + resource);
                yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            var engine = RuleCatalog.build(RuleSetYaml.parse(yaml).ruleSet());
            var m = Backtest.evaluate(engine, samples, k);
            System.out.printf(Locale.ROOT, "%d,%d,%d,%d,%d,%.4f,%.4f,%d,%.4f%n",
                    m.ruleSetVersion(), m.tp(), m.fp(), m.tn(), m.fn(), m.precision(), m.recall(), m.k(), m.precisionAtK());
        }
    }
}
