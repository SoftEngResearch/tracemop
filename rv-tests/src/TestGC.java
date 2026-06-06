import java.util.*;

// GC test: create many short-lived iterators and force GC to verify
// that IndexingTree entries are reclaimed (native mode) and that
// co-enable termination works (both modes).
// Run with: -Xmx128m -verbose:gc
public class TestGC {
    public static void main(String[] args) {
        System.out.println("TestGC: creating 1M short-lived iterators...");
        long start = System.currentTimeMillis();
        for (int i = 0; i < 1_000_000; i++) {
            List<Integer> list = new ArrayList<>();
            list.add(i);
            Iterator<Integer> it = list.iterator();
            while (it.hasNext()) it.next();
            // list and it go out of scope
            if (i % 100_000 == 0) {
                System.gc();
                long used = Runtime.getRuntime().totalMemory()
                          - Runtime.getRuntime().freeMemory();
                System.out.println("  iter " + i + " mem=" + (used / 1024 / 1024) + "MB");
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("TestGC: done in " + elapsed + "ms");
    }
}
