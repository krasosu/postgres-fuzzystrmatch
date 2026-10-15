package de.krasosu.fuzzydb;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fuzzy")
public record FuzzyProperties(Generate generate, Demo demo) {

    public record Generate(int count, int batchSize, int minLength, int maxLength,
                           int plantedWhole, int plantedSubstring) {
    }

    public record Demo(String binaryPattern, String asciiPattern, int maxDistance, int substringMaxDistance) {
    }
}
