package de.krasosu.fuzzydb;

public record SearchHit(long id, String version, String description, int length, int distance, String preview) {
}
