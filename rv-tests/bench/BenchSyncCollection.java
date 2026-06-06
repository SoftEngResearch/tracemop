import java.util.*;

/**
 * Contention micro-benchmark for the general-spec dispatch path
 * (Collections_SynchronizedCollection: creation `sync(col)` + `defineTo` clone
 * to {col,iter} on iterator creation, all under the per-spec RVMLock).
 *
 * Each iteration creates a synchronized collection and iterates it *while
 * holding its monitor* — the SAFE pattern, so no violation fires and no I/O
 * pollutes the timing. Every iteration still drives: monitor creation (sync),
 * full-key non-creation dispatch + defineTo clone (syncCreateIter), and
 * partial-key broadcast (accessIter) — all serialized on the single per-spec
 * lock, so throughput-vs-threads exposes lock-scope contention.
 *
 * Args: <threads> <itersPerThread> [listSize]
 */
public class BenchSyncCollection {
    static volatile long blackhole;

    public static void main(String[] args) throws Exception {
        int threads  = args.length > 0 ? Integer.parseInt(args[0]) : 4;
        int iters    = args.length > 1 ? Integer.parseInt(args[1]) : 200_000;
        int listSize = args.length > 2 ? Integer.parseInt(args[2]) : 8;

        // Warmup (JIT + monitor structures) — light, this path is slow when monitored
        run(2, 8_000, listSize);

        long t0 = System.nanoTime();
        run(threads, iters, listSize);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        long total = (long) threads * iters;
        System.out.printf("threads=%-2d iters/thread=%-8d total=%-10d time=%6dms  throughput=%,.0f ops/s%n",
                threads, iters, total, ms, total * 1000.0 / Math.max(1, ms));
    }

    static void run(int threads, int iters, int listSize) throws Exception {
        final List<Integer> base = new ArrayList<>();
        for (int i = 0; i < listSize; i++) base.add(i);

        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            ts[t] = new Thread(() -> {
                long sink = 0;
                for (int i = 0; i < iters; i++) {
                    Collection<Integer> sync = Collections.synchronizedCollection(new ArrayList<>(base));
                    synchronized (sync) {                  // SAFE: hold monitor while iterating
                        for (Integer x : sync) sink += x;  // syncCreateIter (defineTo) + accessIter
                    }
                }
                blackhole += sink;
            });
        }
        for (Thread th : ts) th.start();
        for (Thread th : ts) th.join();
    }
}
