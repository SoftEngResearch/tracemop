# Output Structure

## locations.txt
This file maps location IDs to the actual lines of code.

The format is `<location-id> <line>`, where `<line>` is `<package>.<class-name>.<method-name>(<file-name>:<line-number>)<method-line-number>`

For example:
```
1 org.apache.commons.io.IOUtils.toByteArray(IOUtils.java:2712)2706
```
means that location ID `1` corresponds to line `2712` in the file `IOUtils.java`. The line is inside the method `toByteArray`, which belongs to the package `org.apache.commons.io`. The number `2706` indicates that the method starts at line `2706`.

## specs-frequency.csv
This file maps trace IDs to specs and frequencies.

The format is `<trace-id> {<spec-name-1>=<frequency>, <spec-name-2>=<frequency>, ...}`

For example:
```
1 {InputStream_MarkAfterCloseMonitor=6}
```
means that trace ID `1` was observed by the [`InputStream_MarkAfterClose`](https://github.com/SoftEngResearch/tracemop/blob/master/scripts/props/InputStream_MarkAfterClose.mop) monitors `6` times.

## specs-test.csv
This file maps trace IDs to the tests that generated them.

The format is: `<trace-id> {<test-1>=<frequency>, <test-2>=<frequency>, ...}`

For example:
```
1 {org.apache.commons.compress.compressors.FramedSnappyTest.testRoundtrip(FramedSnappyTest.java:55)=65}
```
means that trace ID `1` was generated `65` times by the test `org.apache.commons.compress.compressors.FramedSnappyTest.testRoundtrip(FramedSnappyTest.java:55)`.

## unique-traces.txt
This file lists all the unique traces.

The format is:
`<trace-id> <frequency> <trace>`, where `<trace>` is a sequence of events in the form `e<event-id>~<location-id>` or `e<event-id>~<location-id>x<frequency>`.

For example:
```
1 20 [e102~1, e103~2x5]
```
means that trace ID `1` represents the trace `[e102~1, e103~2, e103~2, e103~2, e103~2, e103~2]` (and there were `20` of them).

[This file](https://github.com/SoftEngResearch/tracemop/blob/master/scripts/events_encoding_id.txt) maps event IDs to their corresponding specs and event names.
`e102` is a `close` event from the `InputStream_MarkAfterClose` spec, and `e103` is a `mark` event from the same spec.

So, the trace `[e102~1, e103~2, e103~2, e103~2, e103~2, e103~2]` shows that a `close` event at location `1` is followed by five `mark` events at location `2`.
