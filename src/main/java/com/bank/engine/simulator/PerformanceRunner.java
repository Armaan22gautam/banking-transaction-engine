package com.bank.engine.simulator;

import com.bank.engine.config.Database;
import com.bank.engine.config.DbSetup;
import com.bank.engine.model.TransactionRequest;
import com.bank.engine.model.TransactionResult;
import com.bank.engine.model.TransactionStatus;
import com.bank.engine.service.TransactionService;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the SAME workload with 1, 5, 10 and 20 workers against a freshly seeded database.
 * Usage: PerformanceRunner [totalTransactions=50000]
 * Pool size equals the worker count, so connection-pool limits are part of what is measured.
 */
public final class PerformanceRunner {
    private static final int ACCOUNTS = 1000;
    private static final long SEED = 42L;
    private static final int[] WORKERS = {1, 5, 10, 20};

    record Row(int workers, long totalMs, long success, long rejected, long failed, long duplicates,
               double tps, String integrity, String waits) {}

    public static void main(String[] args) throws Exception {
        int total = args.length > 0 ? Integer.parseInt(args[0]) : 50_000;
        List<TransactionRequest> workload = WorkloadGenerator.generate(total, ACCOUNTS, SEED);
        List<Row> rows = new ArrayList<>();

        for (int workers : WORKERS) {
            System.out.printf("Running %d transactions with %d worker(s)...%n", total, workers);
            rows.add(runOnce(workers, workload));
        }
        printAndSave(total, rows);
    }

    private static Row runOnce(int workers, List<TransactionRequest> workload) throws Exception {
        try (HikariDataSource ds = Database.fromEnv(workers); WaitSampler sampler = new WaitSampler()) {
            DbSetup.resetAndSeed(ds);
            BigDecimal opening = IntegrityVerifier.totalBalance(ds);
            TransactionService service = new TransactionService(ds);

            AtomicInteger n = new AtomicInteger();
            ThreadFactory tf = r -> new Thread(r, "worker-" + n.incrementAndGet());
            // Bounded queue + blocking hand-off: the producer waits instead of piling up 50k tasks.
            ThreadPoolExecutor pool = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1000), tf, (task, ex) -> {
                try {
                    ex.getQueue().put(task);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RejectedExecutionException(ie);
                }
            });

            List<Future<TransactionResult>> futures = new ArrayList<>(workload.size());
            long start = System.nanoTime();
            for (TransactionRequest req : workload) futures.add(pool.submit(() -> service.process(req)));
            pool.shutdown();
            long success = 0, rejected = 0, failed = 0, duplicates = 0;
            for (Future<TransactionResult> f : futures) {
                TransactionResult r = get(f);
                if (r.duplicate()) duplicates++;
                else if (r.status() == TransactionStatus.SUCCESS) success++;
                else if (r.status() == TransactionStatus.REJECTED) rejected++;
                else failed++;
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            pool.awaitTermination(1, TimeUnit.MINUTES);

            IntegrityVerifier.Report report = IntegrityVerifier.verify(ds, opening);
            System.out.println("  integrity: " + (report.ok() ? "OK" : "VIOLATION") + " | " + report.detail());
            double tps = workload.size() / (ms / 1000.0);
            return new Row(workers, ms, success, rejected, failed, duplicates, tps, report.ok() ? "OK" : "VIOLATION", sampler.top(3));
        }
    }

    private static TransactionResult get(Future<TransactionResult> f) throws InterruptedException {
        try {
            return f.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Worker crashed", e.getCause());
        }
    }

    private static void printAndSave(int total, List<Row> rows) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("| Workers | Total time (ms) | Successful | Rejected | Failed (system) | Duplicates ignored | TPS | Integrity | Top DB wait events (samples) |\n");
        md.append("|---:|---:|---:|---:|---:|---:|---:|:--|:--|\n");
        StringBuilder csv = new StringBuilder("workers,total_ms,success,rejected,failed,duplicates,tps,integrity\n");
        for (Row r : rows) {
            md.append(String.format(Locale.ROOT, "| %d | %d | %d | %d | %d | %d | %.1f | %s | %s |%n",
                    r.workers(), r.totalMs(), r.success(), r.rejected(), r.failed(), r.duplicates(), r.tps(), r.integrity(), r.waits()));
            csv.append(String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%d,%.1f,%s%n",
                    r.workers(), r.totalMs(), r.success(), r.rejected(), r.failed(), r.duplicates(), r.tps(), r.integrity()));
        }
        System.out.println();
        System.out.println("Results for " + total + " transactions:");
        System.out.println(md);
        Files.createDirectories(Path.of("results"));
        Files.writeString(Path.of("results", "performance.md"), md.toString());
        Files.writeString(Path.of("results", "performance.csv"), csv.toString());
        System.out.println("Saved results/performance.md and results/performance.csv");
    }
}
