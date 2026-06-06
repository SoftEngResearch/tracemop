import java.util.*;

// Should trigger Iterator_HasNext violation: next() without hasNext()
public class TestHasNext {
    public static void main(String[] args) {
        System.out.println("TestHasNext: calling next() without hasNext()...");
        List<String> list = Arrays.asList("a", "b");
        Iterator<String> it = list.iterator();
        String x = it.next();  // VIOLATION
        System.out.println("Got: " + x);
        System.out.println("TestHasNext: done");
    }
}
