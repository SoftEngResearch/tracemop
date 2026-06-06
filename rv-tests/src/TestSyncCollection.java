import java.util.*;

// Should trigger Collections_SynchronizedCollection @match: create a
// synchronized collection, then access its iterator without holding the
// collection's lock. Stresses the general-spec dispatch path (creation
// event with proper-subset params + defineTo cloning + DisableHolder).
public class TestSyncCollection {
    public static void main(String[] args) throws Exception {
        System.out.println("TestSyncCollection: accessing synchronized iterator without lock...");
        Collection<String> raw = new ArrayList<>(Arrays.asList("a", "b", "c"));
        Collection<String> sync = Collections.synchronizedCollection(raw);
        // Take the iterator without holding the lock — match case 1
        // (sync asyncCreateIter).
        Iterator<String> it = sync.iterator();   // asyncCreateIter
        // Access it without holding the lock too.
        if (it.hasNext()) {                      // accessIter (unsynchronized)
            System.out.println("Got: " + it.next());
        }
        System.out.println("TestSyncCollection: done");
    }
}
