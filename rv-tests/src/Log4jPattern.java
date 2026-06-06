import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.ref.WeakReference;

// Mimic the exact log4j ClassLoaderContextSelector.removeContext pattern:
// iterate CONTEXT_MAP.entrySet(), do CONTEXT_MAP.remove(...) inside the loop.
// Should fire Map_UnsafeIterator @match once.
public class Log4jPattern {
    static final ConcurrentHashMap<String, AtomicReference<WeakReference<String>>> CONTEXT_MAP =
        new ConcurrentHashMap<>();

    public static void main(String[] args) {
        // populate
        for (int i = 0; i < 5; i++) {
            CONTEXT_MAP.put("k" + i, new AtomicReference<>(new WeakReference<>("v" + i)));
        }
        // mimic removeContext: iterate entrySet, call CONTEXT_MAP.remove(key) inside
        for (Map.Entry<String, AtomicReference<WeakReference<String>>> entry : CONTEXT_MAP.entrySet()) {
            if (entry.getKey().equals("k2")) {
                CONTEXT_MAP.remove(entry.getKey());
            }
        }
        System.out.println("Log4jPattern: done, size=" + CONTEXT_MAP.size());
    }
}
