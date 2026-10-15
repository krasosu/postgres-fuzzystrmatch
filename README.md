# fuzzy-db

Proof of Concept: unscharfe Suche mit PostgreSQL 18 auf Bitmustern (`0101…`) und ASCII-Inhalten
(`ADCB…`) mit 10 bis 10.000 Zeichen. Kernfrage: *„In welchen Datensätzen steht meine Sequenz irgendwo
im `content` – exakt oder mit ein paar falschen Zeichen?“*

```
docker-compose.yml        Postgres 18 + pgAdmin lokal
db/init/                  Init-Skripte (laufen nur bei leerem Volume)
  01-extensions.sql       fuzzystrmatch, pg_trgm, pg_stat_statements
  02-schema.sql           Tabelle sample + Indizes
  03-functions.sql        Fuzzy-Funktionen für lange Strings
app/                      Spring Boot 4 (Java 21): Testdaten + Such-Demo
```

## Starten

```bash
docker compose up -d
cd app && ./mvnw -q package -DskipTests
java -jar target/fuzzy-db-app-0.1.0-SNAPSHOT.jar            # = demo
```

`demo` füllt die Tabelle (falls leer) mit 5.000 Zufallszeilen plus 50 „eingepflanzten“ Treffern
und führt drei Beispielsuchen aus. Weitere Befehle:

```bash
java -jar target/fuzzy-db-app-0.1.0-SNAPSHOT.jar generate --count=20000 [--truncate]
java -jar target/fuzzy-db-app-0.1.0-SNAPSHOT.jar search --pattern=10101010 --k=3
java -jar target/fuzzy-db-app-0.1.0-SNAPSHOT.jar search --pattern=ADCBXYZW --k=2 --mode=substring [--trgm=0.3]
```

Schema neu aufsetzen (Init-Skripte laufen nur bei leerem Volume): `docker compose down -v && docker compose up -d`

Direkt in psql: `docker exec -it fuzzy-db-postgres psql -U fuzzy fuzzydb`

**pgAdmin**: http://127.0.0.1:5050 – ohne Login (Desktop-Modus), der Server „fuzzy-db“ ist vorkonfiguriert
und verbindet ohne Passwortabfrage. Daten ansehen: *fuzzy-db → Databases → fuzzydb → Schemas → public →
Tables → sample*, Rechtsklick → *View/Edit Data*, oder *Tools → Query Tool* für eigenes SQL (F5 = ausführen).

## Tabelle `sample`

| Spalte | Typ | |
|---|---|---|
| id | bigint identity | technischer Primärschlüssel (zusätzlich zu den 5 fachlichen Spalten) |
| datum | timestamptz | |
| version | varchar(32) | |
| description | varchar(255) | |
| details | text | |
| content | text | CHECK 10..10.000 Zeichen; `text` statt `bit varying`, weil auch ASCII vorkommen soll |

## Die Suchfunktionen

| Funktion | Frage | erlaubte Fehler |
|---|---|---|
| `strpos(content, seq) > 0` | steht `seq` exakt irgendwo drin? | keine |
| `fuzzy_substring_hamming(content, seq, k)` | steht irgendwo ein Abschnitt **gleicher Länge**, der sich an ≤ k Stellen unterscheidet? | nur **vertauschte** Zeichen |
| `fuzzy_substring_distance(content, seq, k)` | steht irgendwo ein Abschnitt, der mit ≤ k Änderungen zu `seq` wird? | vertauschte, **fehlende** und **zusätzliche** Zeichen (Levenshtein) |
| `fuzzy_distance(content, seq, k)` | ist der **ganze** `content` ungefähr `seq`? | wie Levenshtein |
| `fuzzy_substring_hamming_pct(content, seq, 0.10)` | wie `fuzzy_substring_hamming`, Fehlergrenze in **Prozent** der Sequenz | nur vertauschte Zeichen |
| `fuzzy_substring_hamming_multi(content, ARRAY[seq1, seq2, …], k)` | welche von **mehreren** Sequenzen stehen drin? (ein Durchlauf pro Datensatz) | nur vertauschte Zeichen |

Die Funktionen liefern die **kleinste** gefundene Fehlerzahl (`abweichung`). Ist sie größer als `k`,
liefern sie nur `k + 1` („nicht gefunden“) – `k` ist also zugleich Suchgrenze und Abbruchkriterium:
je kleiner, desto schneller. Ausnahmen: `_pct` liefert bei „nicht gefunden“ `NULL`, `_multi` liefert
nur die gefundenen Sequenzen.

**Platzhalter `?`**: In der Sequenz steht `?` für „beliebiges Zeichen“, z. B. `0101????1100`. Gilt für
`fuzzy_substring_hamming`, `_pct`, `_multi` und `fuzzy_substring_distance` (nicht für `fuzzy_distance`).
Ein `?` zählt nie als Fehler. Ein echtes Fragezeichen im `content` lässt sich damit nicht gezielt suchen.

So arbeitet `fuzzy_substring_hamming`: ein Fenster in der Länge der Sequenz wird Zeichen für Zeichen
über `content` geschoben, an jeder Position werden die Unterschiede gezählt, die beste Position gewinnt.

```
content:   0 0 1 1 1 0 0 1 0 1
Sequenz:   1 0 1 0 1 0                 → Position 1: 2 falsch
             1 0 1 0 1 0               → Position 2: 3 falsch
               1 0 1 0 1 0             → Position 3: 2 falsch   …  Ergebnis: abweichung = 2
```

Abkürzungen: ein Fenster wird abgebrochen, sobald es nicht mehr besser werden kann; bei einem
exakten Treffer (0) endet die Suche sofort.

## Beispiele

Alle Beispiele verwenden die 32-Bit-Sequenz `00000101100111110100001100000011`. Sie stammt aus einem
zufällig erzeugten Datensatz (Position 1001 in `id = 1`). Die Ergebnisse unten sind echte Läufe auf
5.050 Testzeilen (Ø 5.000 Zeichen, 16 Kerne). Wer die Testdaten neu erzeugt, bekommt andere Zufallstreffer.

### Beispieldaten zum Nachvollziehen

Damit jedes Beispiel garantiert etwas findet, diese fünf Datensätze im Query Tool einfügen. Die Sequenz
steht jeweils ab Position 41, eingebettet in `1100 1100 …` (das Füllmuster selbst ist ≥ 13 Fehler von der
Sequenz entfernt, erzeugt also keine falschen Treffer):

```sql
INSERT INTO sample (version, description, details, content) VALUES
  ('demo', 'README: Sequenz exakt',    'Sequenz ab Position 41',                    repeat('1100', 10) || '00000101100111110100001100000011' || repeat('1100', 10)),
  ('demo', 'README: 1 Zeichen falsch', 'Sequenz ab Position 41, Bit 16 gekippt',    repeat('1100', 10) || '00000101100111100100001100000011' || repeat('1100', 10)),
  ('demo', 'README: 3 Zeichen falsch', 'Sequenz ab Position 41, Bits 4/16/28',      repeat('1100', 10) || '00010101100111100100001100010011' || repeat('1100', 10)),
  ('demo', 'README: 5 Zeichen falsch', 'Sequenz ab Position 41, Bits 2/9/16/23/30', repeat('1100', 10) || '01000101000111100100000100000111' || repeat('1100', 10)),
  ('demo', 'README: 1 Zeichen fehlt',  'Sequenz ab Position 41, Bit 16 entfernt',   repeat('1100', 10) || '0000010110011110100001100000011'  || repeat('1100', 10));

-- wieder entfernen:
-- DELETE FROM sample WHERE version = 'demo';
```

Die ids der Demo-Datensätze (unten 5051–5055) können bei dir abweichen.

### 1. Sequenz steht exakt irgendwo im `content`

```sql
SELECT id, description,
       char_length(content)                                  AS laenge,
       strpos(content, '00000101100111110100001100000011')   AS position
FROM sample
WHERE content LIKE '%00000101100111110100001100000011%'
ORDER BY id;
```

```
  id  |      description      | laenge | position
------+-----------------------+--------+----------
    1 | random binary         |   9238 |     1001
 5051 | README: Sequenz exakt |    112 |       41
```

~130 ms. `LIKE '%…%'` darf den Trigramm-Index nutzen (der filtert grob vor, dann wird exakt
nachgeprüft); `strpos()` liefert die Startposition (1 = erstes Zeichen, 0 = nicht enthalten).

### 2. Sequenz mit höchstens 1 falschen Zeichen

```sql
SELECT id, description,
       fuzzy_substring_hamming(content, '00000101100111110100001100000011', 1) AS abweichung
FROM sample
WHERE fuzzy_substring_hamming(content, '00000101100111110100001100000011', 1) <= 1
ORDER BY abweichung, id;
```

```
  id  |       description        | abweichung
------+--------------------------+------------
    1 | random binary            |          0
 5051 | README: Sequenz exakt    |          0
 5052 | README: 1 Zeichen falsch |          1
```

~2 s. Exakte Treffer sind immer dabei (0 ≤ 1).

### 3. Sequenz mit höchstens 5 falschen Zeichen

```sql
SELECT id, description, abweichung
FROM (
    SELECT id, description,
           fuzzy_substring_hamming(content, '00000101100111110100001100000011', 5) AS abweichung
    FROM sample
) t
WHERE abweichung <= 5
ORDER BY abweichung, id;
```

Ergebnis: **832 Datensätze** in ~6 s, nach Abweichung:

| abweichung | Anzahl | davon |
|---|---|---|
| 0 | 2 | id 1, README exakt |
| 1 | 1 | README 1 falsch |
| 2 | 5 | Zufall |
| 3 | 17 | 16 Zufall, README 3 falsch |
| 4 | 131 | Zufall |
| 5 | 676 | 674 Zufall, README 5 falsch, README 1 fehlt (siehe Beispiel 5) |

Bei 5 von 32 Zeichen trifft fast jeder vierte zufällige Bit-Datensatz – siehe
[Wie viele Fehler sind sinnvoll?](#wie-viele-fehler-sind-sinnvoll). (Die Unterabfrage berechnet die
Funktion nur einmal pro Zeile statt zweimal.)

### 4. Sequenz mit höchstens 10 % Abweichung

`fuzzy_substring_hamming_pct` rechnet die erlaubte Fehlerzahl selbst aus: 10 % von 32 = 3,2 → abgerundet
**3** (es zählen nur ganze Zeichen; `?`-Platzhalter zählen nicht mit). Bei „nicht gefunden“ liefert sie
`NULL`, daher reicht `IS NOT NULL`:

```sql
SELECT id, description, abweichung
FROM (
    SELECT id, description,
           fuzzy_substring_hamming_pct(content, '00000101100111110100001100000011', 0.10) AS abweichung
    FROM sample
) t
WHERE abweichung IS NOT NULL
ORDER BY abweichung, id;
```

```
  id  |       description        | abweichung
------+--------------------------+------------
    1 | random binary            |          0
 5051 | README: Sequenz exakt    |          0
 5052 | README: 1 Zeichen falsch |          1
 1042 | random binary            |          2
  …   (4 weitere mit 2)
  172 | random binary            |          3
  …   (16 weitere mit 3, darunter README: 3 Zeichen falsch)
(25 rows)
```

~3,5 s. Mit `0.05` (= 1 Fehler bei 32 Zeichen) bleiben nur die 3 Treffer aus Beispiel 2 übrig.

### 5. Variante: auch fehlende oder zusätzliche Zeichen erlauben

`fuzzy_substring_hamming` zählt nur **vertauschte** Zeichen. Fehlt ein Zeichen, verschiebt sich der Rest
der Sequenz und fast alles danach gilt als falsch. `fuzzy_substring_distance` (Levenshtein) erkennt das
als einen einzigen Fehler:

```sql
SELECT id, description,
       fuzzy_substring_hamming (content, '00000101100111110100001100000011', 10) AS hamming,
       fuzzy_substring_distance(content, '00000101100111110100001100000011', 10) AS levenshtein
FROM sample
WHERE version = 'demo'
ORDER BY id;
```

```
  id  |       description        | hamming | levenshtein
------+--------------------------+---------+-------------
 5051 | README: Sequenz exakt    |       0 |           0
 5052 | README: 1 Zeichen falsch |       1 |           1
 5053 | README: 3 Zeichen falsch |       3 |           3
 5054 | README: 5 Zeichen falsch |       5 |           5
 5055 | README: 1 Zeichen fehlt  |       5 |           1
```

Welche Variante richtig ist, hängt von den Daten ab: können Zeichen nur *kippen* (Hamming) oder auch
*verloren gehen / dazukommen* (Levenshtein)? Levenshtein findet mehr, ist aber etwas langsamer.

### 6. Platzhalter: bestimmte Stellen sind egal

Die Bits 9–16 der Sequenz sollen keine Rolle spielen, dort steht `?`:

```sql
SELECT id, description,
       fuzzy_substring_hamming(content, '00000101????????0100001100000011', 0) AS abweichung
FROM sample
WHERE fuzzy_substring_hamming(content, '00000101????????0100001100000011', 0) <= 0
ORDER BY id;
```

```
  id  |       description        | abweichung
------+--------------------------+------------
    1 | random binary            |          0
 2965 | random binary            |          0
 4506 | random binary            |          0
 5051 | README: Sequenz exakt    |          0
 5052 | README: 1 Zeichen falsch |          0
```

~2,7 s. „1 Zeichen falsch“ ist jetzt ein exakter Treffer, weil das gekippte Bit 16 unter einem `?` liegt.
Zwei zufällige Datensätze kommen dazu: mit 8 Platzhaltern bleiben nur 24 aussagekräftige Bits, und
24 Bit treffen in 5.000 Zeichen schon in ~0,03 % der Fälle zufällig (siehe
[Wie viele Fehler sind sinnvoll?](#wie-viele-fehler-sind-sinnvoll) – Platzhalter verkürzen die Sequenz
effektiv). Platzhalter und Fehler lassen sich kombinieren, z. B. `…, 1)` für zusätzlich 1 falsches Zeichen.

### 7. Mehrere Sequenzen auf einmal

Eine Suche, drei Sequenzen (32 Bit, 64 Bit, ASCII), je höchstens 1 falsches Zeichen. Pro Datensatz
kommt eine Zeile je gefundener Sequenz heraus:

```sql
SELECT id, description, (m).sequenz, (m).abweichung
FROM (
    SELECT id, description,
           fuzzy_substring_hamming_multi(content,
               ARRAY['00000101100111110100001100000011',
                     '1110010100100001111001010100100011011110110101111101001111100111',
                     'ADCBXYZW'], 1) AS m
    FROM sample
) t
ORDER BY id, (m).sequenz;
```

```
  id  |       description       |                             sequenz                              | abweichung
------+-------------------------+------------------------------------------------------------------+------------
    1 | random binary           | 00000101100111110100001100000011                                 |          0
    1 | random binary           | 1110010100100001111001010100100011011110110101111101001111100111 |          0
 5026 | planted substring match | ADCBXYZW                                                         |          0
 5033 | planted substring match | ADCBXYZW                                                         |          1
  …   (7 weitere ADCBXYZW-Treffer, + 2 README-Datensätze falls eingefügt)
```

~5–7 s statt ~7,6 s für drei Einzelsuchen. Der Gewinn ist klein (der Datensatz wird nur einmal
geladen und zerlegt, die Vergleiche bleiben gleich viele), aber eine Abfrage ist bequemer als drei.

**Wichtig:** die Funktion wie oben in der `SELECT`-Liste aufrufen. Die naheliegende Form
`FROM sample s, LATERAL fuzzy_substring_hamming_multi(s.content, …)` liefert dasselbe Ergebnis, läuft
aber nicht parallel (~40 s statt ~7 s) – der Planer schätzt eine Funktion in `FROM` so ein, als liefe
sie nur einmal, und hält Parallelität deshalb für überflüssig.

## Wie viele Fehler sind sinnvoll?

Bei Bitmustern ist jedes Zeichen mit 50 % Wahrscheinlichkeit „richtig“. Erlaubt man zu viele Fehler,
passt die Sequenz rein zufällig irgendwo in einen langen `content`. Erwarteter Anteil zufälliger
Bit-Datensätze (5.000 Zeichen), die einen Treffer liefern:

| Sequenz | 0 Fehler | 1 Fehler | 3 Fehler | 5 Fehler |
|---|---|---|---|---|
| 6 Bit (`101010`) | ~100 % | ~100 % | ~100 % | ~100 % |
| 16 Bit | 7 % | 73 % | ~100 % | ~100 % |
| 32 Bit | 0,0001 % | 0,004 % | 0,6 % | 24 % (gemessen: 23 %) |
| 64 Bit | ≈ 0 | ≈ 0 | ≈ 0 | ≈ 0 (gemessen: nur das Original) |

Faustregel: die Sequenz sollte deutlich länger sein als `Fehler × 6` Bit, sonst wird das Ergebnis vom
Zufall dominiert. Bei ASCII (26 Buchstaben) ist ein Zeichen viel aussagekräftiger als ein Bit, dort
reichen kürzere Sequenzen.

## Wichtige Erkenntnisse

- **`fuzzystrmatch.levenshtein()` erlaubt max. 255 Zeichen pro Argument** (`ERROR: levenshtein
  argument exceeds maximum length of 255 characters`). Deshalb gibt es in `03-functions.sql`:
  - `fuzzy_distance(a, b, k)` – nimmt die C-Funktion `levenshtein_less_equal` wenn möglich, sonst
  - `levenshtein_banded(a, b, k)` – PL/pgSQL, nur das Band |i−j| ≤ k wird berechnet, O(n·k),
    ~25 ms für 10.000 Zeichen
  - `fuzzy_substring_distance(h, p, k)` – Sellers-Algorithmus, O(len(h)·len(p)), ~10–17 ms pro
    10.000-Zeichen-Zeile
  - `fuzzy_substring_hamming(h, p, k)` – Schiebefenster mit Abbruch, nur Ersetzungen, `?` als
    Platzhalter; dazu `_pct` (Prozent) und `_multi` (mehrere Sequenzen). Gemeinsamer Kern ist
    `fuzzy_hamming_arrays`, der auf bereits zerlegten Zeichen-Arrays arbeitet.
  - Alle wurden gegen die C-Implementierung bzw. Brute Force geprüft (zehntausende Zufallsfälle, 0 Abweichungen).
- **Jede unscharfe Suche prüft jeden Datensatz** (kein Index kann das vorab eingrenzen). Die Laufzeit
  wächst linear mit der Datenmenge: 5.000 Zeilen ≈ 2–6 s, 100.000 Zeilen grob 1–2 min.
- **pg_trgm als Vorfilter** (`'ADCBXYZW' <% content`, GIN-Index) ist schnell (~130 ms statt 8 s), aber
  **verlustbehaftet**: Edits zerstören Trigramme, in der Demo findet er ~16 von 26 Treffern. Für reine
  0/1-Strings nutzlos (es gibt nur 8 verschiedene Trigramme). Für die *exakte* Suche (Beispiel 1) ist
  er dagegen korrekt, weil Postgres jeden Kandidaten nachprüft.
- **Parallelität**: `content` wird per TOAST ausgelagert, die Tabelle selbst ist winzig, daher würde
  Postgres nie parallel scannen. Drei Einstellungen sorgen für bis zu 8 parallele Helfer:
  `max_parallel_workers_per_gather=8` (docker-compose.yml), `ALTER TABLE sample SET (parallel_workers = 8)`
  (02-schema.sql) und `PARALLEL SAFE` + `COST 100000` an den Funktionen (03-functions.sql). Ergebnis:
  45 s → 8 s für den teuersten Full Scan. Prüfen mit `EXPLAIN ANALYZE` → `Workers Launched: 8`.
- **Postgres 18 Docker-Image**: das Volume gehört nach `/var/lib/postgresql` (nicht mehr `.../data`).

## Ausblick

- Ansible-Deployment als Docker-Swarm-Stack: `db/init/*.sql` als Swarm-`configs`, Passwort als
  Swarm-`secret` (`POSTGRES_PASSWORD_FILE`), Volume auf einen Node festpinnen (Placement Constraint).
- Wenn der Full Scan bei großen Datenmengen zu langsam wird: Vorfilter nach dem Schubfachprinzip
  (Sequenz in k+1 Teile zerlegen, mindestens einer muss exakt vorkommen) oder eine C-Extension, die
  64 Bit-Stellen pro Rechenschritt vergleicht.
