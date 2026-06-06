import java.util.*;

// Should NOT trigger any violation: hasNext() called before next()
public class TestHasNextSafe {
    public static void main(String[] args) {
        System.out.println("TestHasNextSafe: calling hasNext() before next()...");
        List<String> list = Arrays.asList("a", "b");
        Iterator<String> it = list.iterator();
        while (it.hasNext()) {
            String x = it.next();
            System.out.println("Got: " + x);
        }
        System.out.println("TestHasNextSafe: done");
    }
}
