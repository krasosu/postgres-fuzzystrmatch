-- fuzzystrmatch: levenshtein(), levenshtein_less_equal(), soundex(), metaphone(), daitch_mokotoff()
CREATE EXTENSION IF NOT EXISTS fuzzystrmatch;

-- pg_trgm: trigram similarity, word_similarity() and GIN/GiST indexes for LIKE / % / <% operators.
-- Useful as a prefilter for ASCII content; nearly useless for pure 0/1 strings (only 8 distinct trigrams).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- pg_stat_statements: query statistics, handy for comparing the fuzzy search variants.
-- Requires shared_preload_libraries=pg_stat_statements (see docker-compose.yml).
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
