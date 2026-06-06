import java.util.*;

// Coenable-specific memory test.
//
// A single long-lived Collection `c` spawns many short-lived, exhausted
// iterators.  Under Collection_UnsafeIterator each iterator creates a monitor
// bound to (c, it); once `it` is unreachable the monitor can never reach the
// "unsafe use after modify" verdict (no further useiter(it) is possible), so
// coenable can terminate it.
//
// Because `c` stays alive, the native baseline's weak-KEY cleanup canNOT
// reclaim these monitors (c's key is live, so its projection set retains
// them).  Only coenable termination (-XX:+RVCoenableInGc) frees them.
//
// Expectation: monitor count / heap grows under -XX:-RVCoenableInGc and stays
// flat under -XX:+RVCoenableInGc.  No violation should ever print (we never
// modify c while iterating).
public class TestCoenable {
    public static void main(String[] args) {
        final int N = (args.length > 0) ? Integer.parseInt(args[0]) : 2_000_000;
        final int STEP = N / 20;
        List<Integer> c = new ArrayList<>();   // long-lived
        c.add(1);
        System.out.println("TestCoenable: " + N + " iterators on one long-lived collection");
        for (int i = 0; i < N; i++) {
            Iterator<Integer> it = c.iterator();     // create(c, it)
            while (it.hasNext()) it.next();           // useiter(it)*
            // `it` becomes unreachable here; `c` stays alive.
            if (i % STEP == 0) {
                System.gc();
                long used = Runtime.getRuntime().totalMemory()
                          - Runtime.getRuntime().freeMemory();
                System.out.println("  iter " + i + " mem=" + (used / 1024 / 1024) + "MB");
            }
        }
        System.out.println("TestCoenable: done");
    }
}
