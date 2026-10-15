package de.krasosu.fuzzydb;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Produces random rows (binary, ASCII and mixed content) plus "planted" rows that are known to
 * match the demo patterns, so the fuzzy searches have guaranteed hits.
 */
@Component
public class TestDataGenerator {

    private static final Logger log = LoggerFactory.getLogger(TestDataGenerator.class);

    static final String BINARY = "01";
    static final String ASCII = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    static final String MIXED = "01ABCD";

    private final SampleRepository repository;
    private final FuzzyProperties properties;
    private final RandomGenerator random = RandomGenerator.of("L64X128MixRandom");

    public TestDataGenerator(SampleRepository repository, FuzzyProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public void generate(int count) {
        FuzzyProperties.Generate cfg = properties.generate();
        FuzzyProperties.Demo demo = properties.demo();
        long start = System.nanoTime();

        List<Sample> batch = new ArrayList<>(cfg.batchSize());
        int written = 0;
        for (int i = 0; i < count; i++) {
            batch.add(randomSample());
            written += flushIfFull(batch);
        }
        for (int i = 0; i < cfg.plantedWhole(); i++) {
            batch.add(plantedWholeValue(demo.binaryPattern(), demo.maxDistance()));
            written += flushIfFull(batch);
        }
        for (int i = 0; i < cfg.plantedSubstring(); i++) {
            batch.add(plantedSubstring(demo.asciiPattern(), demo.substringMaxDistance()));
            written += flushIfFull(batch);
        }
        written += flush(batch);
        repository.analyze();

        log.info("Inserted {} rows ({} random, {} planted whole-value, {} planted substring) in {} ms",
                written, count, cfg.plantedWhole(), cfg.plantedSubstring(),
                (System.nanoTime() - start) / 1_000_000);
    }

    private int flushIfFull(List<Sample> batch) {
        return batch.size() < properties.generate().batchSize() ? 0 : flush(batch);
    }

    private int flush(List<Sample> batch) {
        int n = batch.size();
        if (n > 0) {
            repository.insertBatch(batch, properties.generate().batchSize());
            batch.clear();
        }
        return n;
    }

    private Sample randomSample() {
        FuzzyProperties.Generate cfg = properties.generate();
        int length = random.nextInt(cfg.minLength(), cfg.maxLength() + 1);
        double kind = random.nextDouble();
        String type;
        String alphabet;
        if (kind < 0.7) {
            type = "binary";
            alphabet = BINARY;
        } else if (kind < 0.9) {
            type = "ascii";
            alphabet = ASCII;
        } else {
            type = "mixed";
            alphabet = MIXED;
        }
        return new Sample(randomDatum(), randomVersion(), "random " + type,
                "type=" + type + ";length=" + length, randomString(alphabet, length));
    }

    /** Short content equal to the pattern after a few edits: matches the whole-value search. */
    private Sample plantedWholeValue(String pattern, int maxDistance) {
        int minLength = properties.generate().minLength();
        // the CHECK constraint requires >= minLength chars, so short patterns need insertions
        int requiredInserts = Math.max(0, minLength - pattern.length());
        int edits = Math.max(requiredInserts, random.nextInt(0, maxDistance + 1));
        String content = mutate(pattern, edits, requiredInserts, minLength, BINARY);
        return new Sample(randomDatum(), randomVersion(), "planted whole-value match",
                "pattern=" + pattern + ";edits<=" + edits, content);
    }

    /** Long random ASCII content with a mutated copy of the pattern embedded somewhere. */
    private Sample plantedSubstring(String pattern, int maxDistance) {
        FuzzyProperties.Generate cfg = properties.generate();
        int edits = random.nextInt(0, maxDistance + 1);
        String needle = mutate(pattern, edits, 0, 1, ASCII);
        int length = random.nextInt(Math.max(cfg.minLength(), needle.length()), cfg.maxLength() + 1);
        String filler = randomString(ASCII, length - needle.length());
        int pos = random.nextInt(0, filler.length() + 1);
        String content = filler.substring(0, pos) + needle + filler.substring(pos);
        return new Sample(randomDatum(), randomVersion(), "planted substring match",
                "pattern=" + pattern + ";edits<=" + edits + ";position=" + (pos + 1), content);
    }

    /**
     * Applies {@code edits} random edit operations (substitute / insert / delete); the first
     * {@code requiredInserts} are insertions. The resulting edit distance is at most {@code edits}.
     */
    String mutate(String pattern, int edits, int requiredInserts, int minLength, String alphabet) {
        StringBuilder sb = new StringBuilder(pattern);
        for (int e = 0; e < edits; e++) {
            int op = e < requiredInserts ? 1 : random.nextInt(3);
            if (op == 2 && sb.length() <= minLength) {
                op = 0;
            }
            switch (op) {
                case 0 -> {
                    int pos = random.nextInt(sb.length());
                    sb.setCharAt(pos, otherChar(alphabet, sb.charAt(pos)));
                }
                case 1 -> sb.insert(random.nextInt(sb.length() + 1), randomChar(alphabet));
                default -> sb.deleteCharAt(random.nextInt(sb.length()));
            }
        }
        return sb.toString();
    }

    private String randomString(String alphabet, int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = randomChar(alphabet);
        }
        return new String(chars);
    }

    private char randomChar(String alphabet) {
        return alphabet.charAt(random.nextInt(alphabet.length()));
    }

    private char otherChar(String alphabet, char current) {
        char c;
        do {
            c = randomChar(alphabet);
        } while (c == current);
        return c;
    }

    private OffsetDateTime randomDatum() {
        return OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(random.nextLong(365L * 24 * 3600));
    }

    private String randomVersion() {
        return "1." + random.nextInt(10) + "." + random.nextInt(20);
    }
}
