package de.krasosu.fuzzydb;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Commands:
 * <pre>
 *   demo                                                  (default) generate data if empty, run example searches
 *   generate [--count=N] [--truncate]
 *   search   --pattern=P [--k=3] [--mode=whole|substring] [--trgm=0.3] [--limit=20]
 * </pre>
 */
@Component
public class CommandRunner implements ApplicationRunner {

    private final SampleRepository repository;
    private final TestDataGenerator generator;
    private final FuzzyProperties properties;

    public CommandRunner(SampleRepository repository, TestDataGenerator generator, FuzzyProperties properties) {
        this.repository = repository;
        this.generator = generator;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String command = args.getNonOptionArgs().isEmpty() ? "demo" : args.getNonOptionArgs().getFirst();
        switch (command) {
            case "demo" -> demo();
            case "generate" -> generate(args);
            case "search" -> search(args);
            default -> throw new IllegalArgumentException(
                    "Unknown command '" + command + "', expected demo | generate | search");
        }
    }

    private void demo() {
        if (repository.count() == 0) {
            generator.generate(properties.generate().count());
        }
        System.out.printf("%nTable sample contains %d rows%n", repository.count());

        FuzzyProperties.Demo demo = properties.demo();
        int k = demo.maxDistance();

        timed("Whole value: levenshtein(content, '%s') <= %d".formatted(demo.binaryPattern(), k),
                () -> repository.findWholeValue(demo.binaryPattern(), k, 50));

        int ks = demo.substringMaxDistance();
        timed("Substring, trigram prefilter (fast, lossy): content contains ~'%s', distance <= %d"
                        .formatted(demo.asciiPattern(), ks),
                () -> repository.findSubstring(demo.asciiPattern(), ks, 0.3, 50));

        timed("Substring, exact parallel full scan: content contains ~'%s', distance <= %d"
                        .formatted(demo.asciiPattern(), ks),
                () -> repository.findSubstring(demo.asciiPattern(), ks, null, 50));
    }

    private void generate(ApplicationArguments args) {
        if (args.containsOption("truncate")) {
            repository.truncate();
        }
        generator.generate(intOption(args, "count", properties.generate().count()));
    }

    private void search(ApplicationArguments args) {
        String pattern = option(args, "pattern", null);
        if (pattern == null) {
            throw new IllegalArgumentException("search needs --pattern=...");
        }
        int k = intOption(args, "k", properties.demo().maxDistance());
        int limit = intOption(args, "limit", 20);
        String mode = option(args, "mode", "whole");
        String trgm = option(args, "trgm", null);
        Double threshold = trgm == null ? null : Double.valueOf(trgm);

        switch (mode) {
            case "whole" -> timed("Whole value: levenshtein(content, '%s') <= %d".formatted(pattern, k),
                    () -> repository.findWholeValue(pattern, k, limit));
            case "substring" -> timed("Substring%s: content contains ~'%s', distance <= %d".formatted(
                            threshold == null ? "" : " (trigram threshold " + threshold + ")", pattern, k),
                    () -> repository.findSubstring(pattern, k, threshold, limit));
            default -> throw new IllegalArgumentException("--mode must be whole or substring");
        }
    }

    private static void timed(String title, Supplier<List<SearchHit>> query) {
        long start = System.nanoTime();
        List<SearchHit> hits = query.get();
        long ms = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("%n== %s%n   %d hit(s) in %d ms%n", title, hits.size(), ms);
        if (!hits.isEmpty()) {
            System.out.printf("   %6s  %-7s  %5s  %4s  %-27s  %s%n",
                    "id", "version", "len", "dist", "description", "content (first 60 chars)");
        }
        for (SearchHit h : hits) {
            System.out.printf("   %6d  %-7s  %5d  %4d  %-27s  %s%n",
                    h.id(), h.version(), h.length(), h.distance(), h.description(), h.preview());
        }
    }

    private static String option(ApplicationArguments args, String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : values.getFirst();
    }

    private static int intOption(ApplicationArguments args, String name, int fallback) {
        String value = option(args, name, null);
        return value == null ? fallback : Integer.parseInt(value);
    }
}
