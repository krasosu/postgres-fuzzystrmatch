package de.krasosu.fuzzydb;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class SampleRepository {

    private static final RowMapper<SearchHit> HIT_MAPPER = (rs, i) -> new SearchHit(
            rs.getLong("id"), rs.getString("version"), rs.getString("description"),
            rs.getInt("len"), rs.getInt("dist"), rs.getString("preview"));

    /**
     * Whole-value match: levenshtein(content, pattern) <= k.
     * The length condition is a lossless prefilter (distance >= length difference) and uses
     * sample_content_length_idx, so only a handful of rows are compared at all.
     */
    private static final String WHOLE_VALUE_SQL = """
            SELECT id, version, description, len, dist, preview
            FROM (
                SELECT id, version, description,
                       char_length(content)                     AS len,
                       fuzzy_distance(content, :pattern, :k)    AS dist,
                       left(content, 60)                        AS preview
                FROM sample
                WHERE char_length(content) BETWEEN :minLen AND :maxLen
            ) s
            WHERE dist <= :k
            ORDER BY dist, id
            LIMIT :limit
            """;

    /** Substring match: content contains a substring with levenshtein(substring, pattern) <= k. */
    private static final String SUBSTRING_SQL = """
            SELECT id, version, description, len, dist, preview
            FROM (
                SELECT id, version, description,
                       char_length(content)                              AS len,
                       fuzzy_substring_distance(content, :pattern, :k)   AS dist,
                       left(content, 60)                                 AS preview
                FROM sample
                %s
            ) s
            WHERE dist <= :k
            ORDER BY dist, id
            LIMIT :limit
            """;

    /**
     * Trigram prefilter via sample_content_trgm_idx: pattern <% content means
     * word_similarity(pattern, content) >= pg_trgm.word_similarity_threshold.
     * Fast, but lossy: edits destroy trigrams, so true matches can be dropped.
     */
    private static final String TRGM_PREFILTER = "WHERE :pattern <% content";

    private final JdbcTemplate jdbcTemplate;
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    public SampleRepository(JdbcTemplate jdbcTemplate, JdbcClient jdbcClient,
                            TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
    }

    public long count() {
        return jdbcClient.sql("SELECT count(*) FROM sample").query(Long.class).single();
    }

    public void truncate() {
        jdbcTemplate.execute("TRUNCATE sample RESTART IDENTITY");
    }

    public void insertBatch(List<Sample> samples, int batchSize) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO sample (datum, version, description, details, content)
                VALUES (?, ?, ?, ?, ?)
                """, samples, batchSize, (ps, s) -> {
            ps.setTimestamp(1, Timestamp.from(s.datum().toInstant()));
            ps.setString(2, s.version());
            ps.setString(3, s.description());
            ps.setString(4, s.details());
            ps.setString(5, s.content());
        });
    }

    public void analyze() {
        jdbcTemplate.execute("ANALYZE sample");
    }

    public List<SearchHit> findWholeValue(String pattern, int k, int limit) {
        return jdbcClient.sql(WHOLE_VALUE_SQL)
                .param("pattern", pattern)
                .param("k", k)
                .param("minLen", pattern.length() - k)
                .param("maxLen", pattern.length() + k)
                .param("limit", limit)
                .query(HIT_MAPPER)
                .list();
    }

    /**
     * @param trigramThreshold null for an exact full scan, otherwise the
     *                         pg_trgm.word_similarity_threshold used for the index prefilter
     */
    public List<SearchHit> findSubstring(String pattern, int k, Double trigramThreshold, int limit) {
        if (trigramThreshold == null) {
            return querySubstring(SUBSTRING_SQL.formatted(""), pattern, k, limit);
        }
        return transactionTemplate.execute(tx -> {
            jdbcClient.sql("SELECT set_config('pg_trgm.word_similarity_threshold', :t, true)")
                    .param("t", trigramThreshold.toString())
                    .query().singleValue();
            return querySubstring(SUBSTRING_SQL.formatted(TRGM_PREFILTER), pattern, k, limit);
        });
    }

    private List<SearchHit> querySubstring(String sql, String pattern, int k, int limit) {
        return jdbcClient.sql(sql)
                .param("pattern", pattern)
                .param("k", k)
                .param("limit", limit)
                .query(HIT_MAPPER)
                .list();
    }
}
