CREATE TABLE sample (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    datum       timestamptz  NOT NULL DEFAULT now(),
    version     varchar(32)  NOT NULL,
    description varchar(255),
    details     text,
    -- Bit patterns (0101...) and later also ASCII characters (ADCB...).
    -- text instead of bit varying, because non-binary characters must be possible.
    content     text         NOT NULL,
    CONSTRAINT sample_content_length CHECK (char_length(content) BETWEEN 10 AND 10000)
);

COMMENT ON TABLE  sample         IS 'Proof of concept for fuzzy search on bit/ASCII patterns';
COMMENT ON COLUMN sample.content IS '10..10000 chars, bit pattern and/or ASCII';

-- Levenshtein distance >= |len(a) - len(b)|, so the length is a cheap, indexable prefilter
-- for whole-value fuzzy matches: only rows with len BETWEEN len(pattern)-k AND len(pattern)+k qualify.
CREATE INDEX sample_content_length_idx ON sample (char_length(content));

-- Trigram index for substring / similarity prefiltering of ASCII content.
CREATE INDEX sample_content_trgm_idx ON sample USING gin (content gin_trgm_ops);

CREATE INDEX sample_datum_idx ON sample (datum);

-- content is TOASTed, so the heap stays tiny and the planner would never choose a parallel scan.
-- Fuzzy scans are CPU-bound per row, so force parallel workers (8x faster on a 16-core box).
ALTER TABLE sample SET (parallel_workers = 8);
