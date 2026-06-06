import java.util.*;

// Deterministic single-threaded stress for Collection_UnsafeIterator.
// Each loop creates a fresh collection, takes N iterators on it, modifies,
// then uses one of the iterators — should fire one violation per iterator
// that was alive at modify+useiter time.
public class UnsafeIterStress {
    public static void main(String[] args) {
        int rounds = Integer.parseInt(args.length > 0 ? args[0] : "10000");
        int iters_per = Integer.parseInt(args.length > 1 ? args[1] : "3");
        long total = 0;
        for (int r = 0; r < rounds; r++) {
            List<String> c = new ArrayList<>(Arrays.asList("a", "b", "c"));
            // Hold strong refs to N iterators so they don't get GC'd before we use them.
            Iterator<?>[] iters = new Iterator<?>[iters_per];
            for (int j = 0; j < iters_per; j++) iters[j] = c.iterator();
            c.add("d");  // modify
            // useiter on each — every iterator should @match
            for (int j = 0; j < iters_per; j++) {
                try { iters[j].hasNext(); } catch (Exception ignore) {}
                total++;
            }
        }
        System.out.println("UnsafeIterStress: " + rounds + "x" + iters_per + " iterators, " + total + " useiter calls");
    }
}
