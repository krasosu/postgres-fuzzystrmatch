# fuzzy-db

Proof of Concept: unscharfe Suche (Levenshtein) mit PostgreSQL 18 auf Bitmustern (`0101…`) und
ASCII-Inhalten (`ADCB…`) mit 10 bis 10.000 Zeichen.

```
docker-compose.yml        Postgres 18 lokal
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

## Tabelle `sample`

| Spalte | Typ | |
|---|---|---|
| id | bigint identity | technischer Primärschlüssel (zusätzlich zu den 5 fachlichen Spalten) |
| datum | timestamptz | |
| version | varchar(32) | |
| description | varchar(255) | |
| details | text | |
| content | text | CHECK 10..10.000 Zeichen; `text` statt `bit varying`, weil auch ASCII vorkommen soll |

## Zwei Arten von „unscharf“

**1. Ganzer Wert** – `levenshtein(content, '10101010') <= 3` („content *ist* ungefähr X“)

```sql
SELECT id, fuzzy_distance(content, '10101010', 3) AS dist
FROM sample
WHERE char_length(content) BETWEEN 8 - 3 AND 8 + 3      -- verlustfreier Vorfilter, Index
  AND fuzzy_distance(content, '10101010', 3) <= 3;
```

Die Levenshtein-Distanz ist mindestens der Längenunterschied, daher kommen nur Zeilen mit
passender Länge in Frage. Über den Index auf `char_length(content)` dauert das ~4 ms.

**2. Teilstring** – `content` *enthält* einen Abschnitt, der ungefähr X ist

```sql
SELECT id, fuzzy_substring_distance(content, 'ADCBXYZW', 2) AS dist
FROM sample
WHERE fuzzy_substring_distance(content, 'ADCBXYZW', 2) <= 2;
```

## Wichtige Erkenntnisse

- **`fuzzystrmatch.levenshtein()` erlaubt max. 255 Zeichen pro Argument** (`ERROR: levenshtein
  argument exceeds maximum length of 255 characters`). Deshalb gibt es in `03-functions.sql`:
  - `fuzzy_distance(a, b, k)` – nimmt die C-Funktion `levenshtein_less_equal` wenn möglich, sonst
  - `levenshtein_banded(a, b, k)` – PL/pgSQL, nur das Band |i−j| ≤ k wird berechnet, O(n·k),
    ~25 ms für 10.000 Zeichen
  - `fuzzy_substring_distance(h, p, k)` – Sellers-Algorithmus, O(len(h)·len(p)), ~10–17 ms pro
    10.000-Zeichen-Zeile
  - Alle drei wurden gegen die C-Implementierung bzw. Brute Force geprüft (zehntausende Zufallsfälle, 0 Abweichungen).
- **Teilstringsuche auf reinen Bitmustern ist bei langen Werten fast immer ein Treffer**: ein
  zufälliger 10.000-Bit-String enthält `10101010` praktisch sicher exakt. Sinnvoll wird sie erst mit
  längeren Mustern (z. B. 32+ Bit) oder bei ASCII-Inhalten.
- **Ebenso trifft k=3 auf ein 8-Zeichen-Muster schon viel zufälligen ASCII-Text**, deshalb nutzt die Substring-Demo k=2.
- **pg_trgm als Vorfilter** (`'ADCBXYZW' <% content`, GIN-Index) ist schnell (~130 ms statt 8 s), aber
  **verlustbehaftet**: Edits zerstören Trigramme, in der Demo findet er ~16 von 26 Treffern. Für reine
  0/1-Strings nutzlos (es gibt nur 8 verschiedene Trigramme).
- **Parallelität**: `content` wird per TOAST ausgelagert, die Tabelle selbst ist winzig, daher würde
  Postgres nie parallel scannen. `parallel_workers = 8` an der Tabelle + `COST 100000` an den
  Funktionen bringt den exakten Full-Scan über 5.050 Zeilen von 45 s auf 8 s.
- **Postgres 18 Docker-Image**: das Volume gehört nach `/var/lib/postgresql` (nicht mehr `.../data`).

Messwerte: lokal, 16 Kerne, 5.050 Zeilen à Ø 5.000 Zeichen.

## Ausblick

- Ansible-Deployment als Docker-Swarm-Stack: `db/init/*.sql` als Swarm-`configs`, Passwort als
  Swarm-`secret` (`POSTGRES_PASSWORD_FILE`), Volume auf einen Node festpinnen (Placement Constraint).
- Wenn der exakte Teilstring-Scan bei großen Datenmengen zu langsam wird: Vorfilter nach dem
  Schubfachprinzip (Muster in k+1 Teile zerlegen, mindestens einer muss exakt vorkommen) oder eine
  C-Extension für Sellers/Myers-Bitparallel.
