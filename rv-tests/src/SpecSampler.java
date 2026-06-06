import java.util.*;
import java.io.*;

// Exercises a bunch of standard-library APIs that the property suite watches.
// Some calls are intentionally violating; others are well-behaved baseline
// traffic. The goal is to maximize variety of specs touched so we can count
// distinct violations.
public class SpecSampler {
    public static void main(String[] args) throws Exception {
        // --- Iterator misuse (Iterator_HasNext, Collection_UnsafeIterator) ---
        {
            List<String> list = Arrays.asList("a", "b", "c");
            Iterator<String> it = list.iterator();
            // next() without hasNext() — Iterator_HasNext violation
            it.next();
        }
        {
            List<String> list = new ArrayList<>(Arrays.asList("x", "y", "z"));
            Iterator<String> it = list.iterator();
            it.next();
            list.add("w");  // structural modification during iteration
            try { it.next(); } catch (ConcurrentModificationException expected) {}
        }
        // --- ArrayDeque NonNull ---
        {
            ArrayDeque<Object> d = new ArrayDeque<>();
            try { d.offer(null); } catch (NullPointerException expected) {}
        }
        // --- Synchronized collection accessed without lock ---
        {
            Collection<String> sync = Collections.synchronizedCollection(
                new ArrayList<>(Arrays.asList("p", "q")));
            Iterator<String> it = sync.iterator();
            if (it.hasNext()) it.next();
        }
        // --- Comparable_CompareToNull ---
        {
            try { Integer.valueOf(1).compareTo(null); } catch (NullPointerException expected) {}
        }
        // --- Arrays_Comparable / Collections_Comparable: sort on non-Comparable ---
        {
            try {
                List<Object> nonComp = new ArrayList<>();
                nonComp.add(new Object());
                nonComp.add(new Object());
                Collections.sort((List) nonComp);
            } catch (ClassCastException expected) {}
        }
        // --- HashMap iteration tests ---
        {
            Map<String, Integer> m = new HashMap<>();
            m.put("a", 1); m.put("b", 2);
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                e.getKey(); e.getValue();
            }
            // Iterator without hasNext
            m.values().iterator().next();
        }
        // --- TreeSet usage ---
        {
            TreeSet<String> ts = new TreeSet<>();
            ts.add("foo"); ts.add("bar");
            ts.iterator().next();
        }
        // --- Multiple iterators on the same collection ---
        {
            List<Integer> base = new ArrayList<>(Arrays.asList(1, 2, 3, 4, 5));
            Iterator<Integer> a = base.iterator();
            Iterator<Integer> b = base.iterator();
            a.next();
            b.next();
            base.remove(0);
            try { a.hasNext(); } catch (ConcurrentModificationException expected) {}
            try { b.hasNext(); } catch (ConcurrentModificationException expected) {}
        }
        // --- StringBuilder cross-thread (Appendable_ThreadSafe?) ---
        {
            StringBuilder sb = new StringBuilder();
            sb.append("x");
            sb.toString();
        }
        // --- ByteArrayOutputStream / flush before retrieve ---
        {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(new byte[]{1,2,3});
            baos.toByteArray();   // no flush — ByteArrayOutputStream_FlushBeforeRetrieve?
        }
        System.out.println("SpecSampler: done");
    }
}
