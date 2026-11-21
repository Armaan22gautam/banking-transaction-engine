# Banking Transaction Processing System

Java 17 + PostgreSQL transaction engine that stays correct under concurrent load, is idempotent for repeated
transaction IDs, and leaves a persisted audit trail for every success, failure and rejection.

**Stack:** Java 17, Maven, PostgreSQL 16, plain JDBC + HikariCP, SLF4J/Logback, JUnit 5, Testcontainers.

## Prerequisites
- JDK 17+, Maven 3.9+
- Docker (for the database and for the Testcontainers-based tests)

## 1. Start the database and load schema + seed data
```bash
docker compose up -d                       # PostgreSQL on localhost:5432 (db bankdb, user bank / password bank)
mvn -q compile exec:java -Dexec.mainClass=com.bank.engine.Main -Dexec.args=init
```
Equivalent with psql: run `src/main/resources/sql/schema.sql` then `seed.sql`.
Override connection settings with `DB_URL`, `DB_USER`, `DB_PASSWORD`.

## 2. Demo the core operations
```bash
mvn -q compile exec:java -Dexec.mainClass=com.bank.engine.Main -Dexec.args=demo
```
Resets data, then runs a deposit, withdrawal, transfer, a replayed (duplicate) ID, and three rejections.
Structured logs go to the console and to `logs/bank.log` (copy a sample into `docs/sample-logs.txt` for submission).

## 3. Run the automated tests
```bash
mvn test
```
| Test class | Proves |
|---|---|
| `ConcurrencyTest` | **Mandatory scenario** (10,000 balance, concurrent 7,000 + 5,000 withdrawals) repeated 100x: exactly one succeeds, balance >= 0. Also 50 parallel withdrawals never overdraw; parallel deposits are never lost. |
| `IdempotencyTest` | Replayed ID changes money once; 20 concurrent copies of one ID apply exactly once; replay of a rejected ID stays harmless. |
| `TransferTest` | Atomic transfer; insufficient funds / unknown destination leave both accounts untouched; 200 opposite-direction transfers neither deadlock nor lose money. |
| `RollbackTest` | Simulated crash after money moved but before commit: everything rolls back, outcome is recorded as FAILED with a reason and audit row. |
| `ValidationTest` | Every rejection reason has status + category and is persisted in `transactions`, `failed_transactions` and `transaction_audit`. |

## 4. Performance test (50,000 transactions, 1 / 5 / 10 / 20 workers)
```bash
APP_LOG_LEVEL=WARN CONSOLE_LOG_LEVEL=ERROR mvn -q compile exec:java \
  -Dexec.mainClass=com.bank.engine.simulator.PerformanceRunner -Dexec.args=50000
```
Each run re-seeds the database and replays the same deterministic workload. After each run an integrity check
proves `sum(balances) = opening + successful deposits - successful withdrawals`, no negative balances,
no PENDING rows, and an audit row for every transaction. Output: `results/performance.md` and `results/performance.csv`
(paste the table into `docs/DESIGN.md`).

## 5. SQL evidence
```bash
docker compose exec -T db psql -U bank -d bankdb < src/main/resources/sql/reports.sql   # 5 required reports
docker compose exec -T db psql -U bank -d bankdb < src/main/resources/sql/explain.sql > docs/explain-output.txt
```
Run these right after the performance run so the tables hold data.

## Project layout
```
src/main/resources/sql/   schema.sql, seed.sql, reports.sql, explain.sql
src/main/java/.../service/TransactionService.java   lifecycle, locking, idempotency, rollback
src/main/java/.../repository/                        JDBC data access
src/main/java/.../simulator/                         workload generator, performance runner, integrity check
src/test/java/                                       concurrency / idempotency / rollback / validation tests
docs/DESIGN.md                                       design note, ER + architecture diagrams, performance analysis
```

## Run the web frontend
```bash
docker compose up -d
mvn -q compile exec:java -Dexec.mainClass=com.bank.engine.Main
```
Open `http://localhost:8080`. The interface supports deposits, withdrawals, transfers,
idempotency replay, account balances, reset-and-seed, and transaction audit history.

## Demo script for the review
1. `docker compose up -d` + `init` (setup from README)  2. `demo` (core operations)
3. `mvn test -Dtest=ConcurrencyTest` (mandatory scenario)  4. `mvn test -Dtest=IdempotencyTest` (duplicates)
5. PerformanceRunner results + `explain-output.txt`
