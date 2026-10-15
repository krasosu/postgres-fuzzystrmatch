-- fuzzystrmatch.levenshtein*() rejects arguments longer than 255 characters.
-- content may hold up to 10000 characters, so these helpers cover the longer cases.

-- Banded Levenshtein (Ukkonen): only cells with |i - j| <= max_d are computed, O(n * max_d).
-- Returns the exact distance if it is <= max_d, otherwise max_d + 1.
CREATE OR REPLACE FUNCTION levenshtein_banded(a text, b text, max_d int)
RETURNS int
LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE
COST 100000                                   -- expensive: lets the planner go parallel
AS $$
DECLARE
    n      int := char_length(a);
    m      int := char_length(b);
    big    int := max_d + 1;
    ac     text[];
    bc     text[];
    d      int[];   -- one DP row, indexes 0..m, updated in place
    i      int;
    j      int;
    lo     int;
    hi     int;
    diag   int;
    up     int;
    v      int;
    rowmin int;
BEGIN
    IF abs(n - m) > max_d THEN
        RETURN big;
    END IF;
    IF n = 0 OR m = 0 THEN
        RETURN least(greatest(n, m), big);
    END IF;

    ac := regexp_split_to_array(a, '');
    bc := regexp_split_to_array(b, '');
    d  := array_fill(big, ARRAY[m + 1], ARRAY[0]);
    FOR j IN 0..least(m, max_d) LOOP
        d[j] := j;
    END LOOP;

    FOR i IN 1..n LOOP
        lo := greatest(1, i - max_d);
        hi := least(m, i + max_d);

        diag := d[lo - 1];                    -- previous row, column lo-1
        IF lo = 1 THEN
            d[0] := least(i, big);
        ELSE
            d[lo - 1] := big;                 -- outside the band
        END IF;
        rowmin := d[lo - 1];

        FOR j IN lo..hi LOOP
            up := d[j];                       -- previous row, column j
            v := least(diag + CASE WHEN ac[i] = bc[j] THEN 0 ELSE 1 END,
                       up + 1,
                       d[j - 1] + 1);
            IF v > big THEN
                v := big;
            END IF;
            diag := up;
            d[j] := v;
            IF v < rowmin THEN
                rowmin := v;
            END IF;
        END LOOP;

        IF hi < m THEN
            d[hi + 1] := big;                 -- next row's band reaches one column further
        END IF;
        IF rowmin > max_d THEN
            RETURN big;                       -- no path can come back under max_d
        END IF;
    END LOOP;

    RETURN least(d[m], big);
END;
$$;

-- Whole-value distance: uses the fast C implementation when possible, the banded one otherwise.
-- Returns the exact distance if it is <= max_d, otherwise something > max_d.
CREATE OR REPLACE FUNCTION fuzzy_distance(a text, b text, max_d int)
RETURNS int
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
AS $$
    SELECT CASE
        WHEN abs(char_length(a) - char_length(b)) > max_d THEN max_d + 1
        WHEN char_length(a) <= 255 AND char_length(b) <= 255 THEN levenshtein_less_equal(a, b, max_d)
        ELSE levenshtein_banded(a, b, max_d)
    END
$$;

-- Approximate substring search (Sellers algorithm): smallest edit distance between needle and
-- any substring of haystack, i.e. "content contains roughly <needle>". O(len(haystack) * len(needle)),
-- no length limit. Returns the exact distance if it is <= max_d, otherwise max_d + 1.
CREATE OR REPLACE FUNCTION fuzzy_substring_distance(haystack text, needle text, max_d int)
RETURNS int
LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE
COST 100000                                   -- expensive: lets the planner go parallel
AS $$
DECLARE
    m    int := char_length(needle);
    hc   text[];
    nc   text[];
    d    int[];   -- one DP column over the needle, indexes 0..m
    best int;
    t    text;
    i    int;
    diag int;
    up   int;
    v    int;
BEGIN
    IF m = 0 THEN
        RETURN 0;
    END IF;

    hc := regexp_split_to_array(haystack, '');
    nc := regexp_split_to_array(needle, '');
    d  := array_fill(0, ARRAY[m + 1], ARRAY[0]);
    FOR i IN 0..m LOOP
        d[i] := i;
    END LOOP;
    best := m;

    FOREACH t IN ARRAY hc LOOP
        diag := d[0];
        d[0] := 0;                            -- a match may start at any position
        FOR i IN 1..m LOOP
            up := d[i];
            v := least(diag + CASE WHEN nc[i] = t THEN 0 ELSE 1 END,
                       up + 1,
                       d[i - 1] + 1);
            diag := up;
            d[i] := v;
        END LOOP;
        IF d[m] < best THEN
            best := d[m];
            EXIT WHEN best = 0;
        END IF;
    END LOOP;

    RETURN least(best, max_d + 1);
END;
$$;
