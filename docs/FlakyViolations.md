# Debugging Flaky Violations

TraceMOP allows comparing traces across multiple runs. This is especially helpful for debugging flaky violations.

Here is an example of how to compare traces using TraceMOP

```bash
# Inside Docker and inside the project directory, run the below commands (should take around 2 minutes)
~/project$ mvn edu.cornell:tracemop-maven-plugin:1.0:run -DoutputDirectory=output1
~/project$ mvn edu.cornell:tracemop-maven-plugin:1.0:run -DoutputDirectory=output2
# The above commands will monitor project's test twice, and save traces to directories output1 and output2

# Go to TraceMOP's scripts directory
~/project$ cd ~/tracemop/scripts

# Compare traces
~/tracemop/scripts$ python3 compare-traces.py ~/project/output1/all-traces ~/project/output2/all-traces true
```

If both runs produce the same set of traces, the compare traces command will not return any output. Otherwise, it will show something like this:
```
WARNING:		[createWithoutThrowable~79]'s (ID: 84) frequency is 127 in expected, but is 123 (ID: 84) in actual
		Test in expected that has this trace: {..., com.flowpowered.commons.store.block.impl.AtomicShortIntArrayTest.parallel(AtomicShortIntArrayTest.java:199)=79, ...}
		Test in actual that has this trace: {..., com.flowpowered.commons.store.block.impl.AtomicShortIntArrayTest.parallel(AtomicShortIntArrayTest.java:199)=75, ...}
WARNING:		[createWithoutThrowable~78]'s (ID: 85) frequency is 30 in expected, but is 29 (ID: 85) in actual
		Test in expected that has this trace: {..., com.flowpowered.commons.store.block.impl.AtomicShortIntArrayTest.parallel(AtomicShortIntArrayTest.java:199)=20, ...}
		Test in actual that has this trace: {..., com.flowpowered.commons.store.block.impl.AtomicShortIntArrayTest.parallel(AtomicShortIntArrayTest.java:199)=19, ...}
```

## Inspect flaky violations
We used TraceMOP's trace comparsion feature to debug four flaky violations.
### Flaky violation 1
When running project [(contentful/contentful.java, SHA: 280e33)](https://github.com/contentful/contentful.java/tree/280e3326ca30746a4db66ccde2a7073da3142a07).

For some runs, there is a violation of [Collection_UnsafeIterator](https://github.com/SoftEngResearch/tracemop/blob/master/scripts/props/Collection_UnsafeIterator.mop) on [line 332 of `MockWebServer.java`](https://github.com/square/okhttp/blob/ea582e6c1bd81c6d10c1ae89a644b94313f0de18/mockwebserver/src/main/java/okhttp3/mockwebserver/MockWebServer.java#L332) (in `square/okhttp` library).

By comparing traces, we found that flaky runs with violation have the trace `[create~766, useiter~766, modify~822, useiter~823, useiter~766]` while runs without violation have the trace `[create~766, useiter~766, useiter~823, useiter~766]`.

Location 766 is at [line 331 of `MockWebServer.java](https://github.com/square/okhttp/blob/ea582e6c1bd81c6d10c1ae89a644b94313f0de18/mockwebserver/src/main/java/okhttp3/mockwebserver/MockWebServer.java#L331), location 822 is at [line 470](https://github.com/square/okhttp/blob/ea582e6c1bd81c6d10c1ae89a644b94313f0de18/mockwebserver/src/main/java/okhttp3/mockwebserver/MockWebServer.java#L470), and location 823 is at [line 332](https://github.com/square/okhttp/blob/ea582e6c1bd81c6d10c1ae89a644b94313f0de18/mockwebserver/src/main/java/okhttp3/mockwebserver/MockWebServer.java#L332).

In this trace, location 766 creates an iterator from a collection and also uses that iterator at the same location. Then, at location 822, the collection is modified. Next, the iterator is used at location 823 then at location 766.

By comparing this trace with a non-violating trace, we find the reason for the flaky violation. Location 822 is called from a different thread that uses `ExecutorService`, a standard JDK API library for handling asynchronous tasks.

### Flaky violation 2
When running project [eclipse/jetty.project (module: jetty-util, SHA: df1f709)](https://github.com/jetty/jetty.project/tree/df1f709ea2f883ffb7f0a87d63aac506ae3fedd5).

For some runs, there is a violation of [Collection_UnsafeIterator](https://github.com/SoftEngResearch/tracemop/blob/master/scripts/props/Collection_UnsafeIterator.mop) on [line 128 of `QueuedThreadPool.java`](https://github.com/jetty/jetty.project/blob/df1f709ea2f883ffb7f0a87d63aac506ae3fedd5/jetty-util/src/main/java/org/eclipse/jetty/util/thread/QueuedThreadPool.java#L128).

By comparing traces, we found that flaky runs with violation have the trace `[create~4, modify~5x2, useiter~4, modify~5x4, useiter~4x2]` while runs without violation have the trace `[create~4, useiter~4x3]`

Location 4 is at [line 128 of `QueuedThreadPool.java`](https://github.com/jetty/jetty.project/blob/df1f709ea2f883ffb7f0a87d63aac506ae3fedd5/jetty-util/src/main/java/org/eclipse/jetty/util/thread/QueuedThreadPool.java#L128) and location 5 is at [line 589 of `QueuedThreadPool.java`](https://github.com/jetty/jetty.project/blob/df1f709ea2f883ffb7f0a87d63aac506ae3fedd5/jetty-util/src/main/java/org/eclipse/jetty/util/thread/QueuedThreadPool.java#589).

For the violating trace, location 4 creates an iterator from a collection, and at location 5, the collection is modified twice. Next, at location 4, the iterator is used, at location 5, the collection is modified 4 times, and then at location 4, the iterator is used twice again. For the non-violating trace, location 4 creates tan iterator from a collection, then at the same location the iterator is used 3 times.

This violation is flaky because `modify` at location 5 is called from a different thread.

### Flaky violation 3
When running project [mitre/HTTP-Proxy-Servlet, SHA: 8a41cf67](https://github.com/mitre/HTTP-Proxy-Servlet/tree/8a41cf6785d7efc11cf6014ae7b05970f2a94d20).

For some runs, there is a [Closeable_MultipleClose](https://github.com/SoftEngResearch/tracemop/blob/master/scripts/props/Closeable_MultipleClose.mop) on [line 237 of `SocketHttpServerConnection.java`](https://github.com/apache/httpcomponents-core/blob/ed3508cc7f101b21979442aeef9010d561af7424/httpcore/src/main/java/org/apache/http/impl/SocketHttpServerConnection.java#L237) (in `apache/httpcomponents-core` library).

By comparing traces, we found that runs with violation have the trace `[close~204x2]` while runs without violation have the trace `[close~204]`

Location 204 is in the method `shutdown`, and this method closes a socket if the socket is not null. By comparing the traces from flaky run and non-flaky run, we now know that this violation is flaky becasue `shutdown` is expected to be called just once, but sometime `shutdown` is called twice.

### Flaky violation 4
When running project [davidmoten/rxjava2-file, SHA: e26b35b](https://github.com/davidmoten/rxjava2-file/tree/e26b35b55a963d8379e5c2cf1104bd5a86afeca3).

For some runs, there is a [Closeable_MultipleClose](https://github.com/SoftEngResearch/tracemop/blob/master/scripts/props/Closeable_MultipleClose.mop) on [line 111 of `OnSubscribeWatchServiceEvents.java`](https://github.com/davidmoten/rxjava2-file/blob/e26b35b55a963d8379e5c2cf1104bd5a86afeca3/src/main/java/com/github/davidmoten/rx/internal/operators/OnSubscribeWatchServiceEvents.java#L111).

By comparing traces, we found that flaky runs with violation have the trace `[close~55, close~65]` while non-flaky runs that do not have violation have the trace `[close~55]`

Location 55 is at [line 126 of `OnSubscribeWatchServiceEvents.java`](https://github.com/davidmoten/rxjava2-file/blob/e26b35b55a963d8379e5c2cf1104bd5a86afeca3/src/main/java/com/github/davidmoten/rx/internal/operators/OnSubscribeWatchServiceEvents.java#L126) and location 65 is at [line 111 of `OnSubscribeWatchServiceEvents.java`](https://github.com/davidmoten/rxjava2-file/blob/e26b35b55a963d8379e5c2cf1104bd5a86afeca3/src/main/java/com/github/davidmoten/rx/internal/operators/OnSubscribeWatchServiceEvents.java#L111).

This violation is flaky because location 65 is executed in the flaky run, and this occurs because location 65 is called when there is an `InterruptedException`.
