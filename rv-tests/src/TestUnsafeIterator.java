import java.util.*;

// Should trigger Collection_UnsafeIterator violation:
// modify collection while iterating
public class TestUnsafeIterator {
    public static void main(String[] args) {
        System.out.println("TestUnsafeIterator: modifying collection during iteration...");
        List<String> list = new ArrayList<>(Arrays.asList("a", "b", "c"));
        Iterator<String> it = list.iterator();
        if (it.hasNext()) {
            String x = it.next();
            System.out.println("Got: " + x);
        }
        list.add("d");  // modify
        try {
            if (it.hasNext()) {
                String x = it.next();  // VIOLATION
                System.out.println("Got: " + x);
            }
        } catch (ConcurrentModificationException e) {
            System.out.println("Java threw CME: " + e.getMessage());
        }
        System.out.println("TestUnsafeIterator: done");
    }
}
