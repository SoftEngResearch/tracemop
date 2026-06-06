# rv-tests

Ephemeral test directory for native IndexingTree agent validation.
Delete this whole directory when done: `rm -rf rv-tests/`

## Tests

| File | Expected (stock) | Expected (native) |
|------|------------------|-------------------|
| TestHasNextSafe | no violation | no violation |
| TestHasNext | Iterator_HasNext violation | Iterator_HasNext violation |
| TestUnsafeIterator | Collection_UnsafeIterator violation | Collection_UnsafeIterator violation |
| TestConcurrent | no violation (stress) | no violation (stress) |
| TestGC | no violation (memory) | no violation (memory, IndexingTree reclaims) |

## Usage

```bash
# 1. Build both stock + native agents (in Docker, due to logic repo JAXB)
bash build-agents.sh

# 2. Run all tests under all agents
bash run-tests.sh
```

Set `PATCHED_JDK` env var if your patched JDK is elsewhere:
```bash
PATCHED_JDK=/path/to/jdk bash run-tests.sh
```

## What to look for

- **Correctness**: Violation tests print the spec's violation message.
- **Parity**: Stock and native agents produce the same violation output.
- **GC**: On TestGC, memory should stay flat (IndexingTree reclaims dead entries).
- **Concurrency**: TestConcurrent should not deadlock or print spurious violations.
