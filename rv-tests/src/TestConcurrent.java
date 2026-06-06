import java.util.*;
import java.util.concurrent.*;

// Stress test: many threads doing monitored operations concurrently
// Exercises lock correctness and monitor lookup under contention
public class TestConcurrent {
    public static void main(String[] args) throws Exception {
        System.out.println("TestConcurrent: 100 threads x 100 iterators...");
        ExecutorService ex = Executors.newFixedThreadPool(8);
        int N = 100;
        CountDownLatch done = new CountDownLatch(N);
        for (int t = 0; t < N; t++) {
            ex.submit(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        List<Integer> list = new ArrayList<>();
                        list.add(i);
                        Iterator<Integer> it = list.iterator();
                        while (it.hasNext()) it.next();  // safe path
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        ex.shutdown();
        System.out.println("TestConcurrent: done");
    }
}
