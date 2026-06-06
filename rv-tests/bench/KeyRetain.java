import java.util.*;

/**
 * Exposes the liveness-conditioned coenable regime (Collection_UnsafeIterator,
 * params {collection c, iterator i}):
 *   - each collection is PARKED in a long-lived list (c stays reachable),
 *   - its iterator is used once and dropped (i becomes garbage).
 * After GC: monitor for {c,i} has a dead i but a live c.
 *   - stock: coenable terminates it on i's death (verdict needs i) -> reclaimed.
 *   - native: no coenable -> monitor lingers in the {c} projection set (c alive).
 * So native's retained heap should exceed stock's by ~one monitor per parked c
 * IF coenable's saving is real and not already recovered by native's GC.
 *
 * Args: <N parked collections>
 */
public class KeyRetain {
    static List<Collection<Integer>> parked = new ArrayList<>();
    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 500_000;
        for (int k = 0; k < n; k++) {
            Collection<Integer> c = new ArrayList<>(Arrays.asList(1, 2, 3));
            parked.add(c);                       // c stays alive forever
            Iterator<Integer> it = c.iterator(); // monitor {c, it}
            while (it.hasNext()) it.next();       // use it; it is now garbage
            it = null;
        }
        // Force reclamation so only truly-live objects remain.
        for (int g = 0; g < 4; g++) { System.gc(); Thread.sleep(150); }
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        System.out.printf("parked=%d  retained-heap=%d MB%n", n, used);
        if (parked.size() < 0) System.out.println(parked); // keep alive
    }
}
