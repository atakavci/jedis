---
description: Adds support for a new Redis command from a given specification. Check command-specification-template.md.
argument-hint: [path-to-specification]
---

# Execute: Add new Redis command support (Jedis)

## Plan to Execute

Read specification file: `$ARGUMENTS`

## Execution Instructions

### 1. Preparations

- Skim [README.md](README.md), [CONTRIBUTING.md](CONTRIBUTING.md) (if present), and [pom.xml](pom.xml) for module / Java version / build conventions.
- Walk the Jedis command-implementation shape end-to-end on an existing command (e.g. `SET`) so the touchpoints below feel concrete:
  - [Protocol.java](src/main/java/redis/clients/jedis/Protocol.java) → `Command` and `Keyword` enums
  - [CommandObjects.java](src/main/java/redis/clients/jedis/CommandObjects.java) → factory methods that build `CommandObject<T>`
  - [commands/](src/main/java/redis/clients/jedis/commands/) → public API interfaces (split String / Binary / Pipeline / PipelineBinary)
  - [UnifiedJedis.java](src/main/java/redis/clients/jedis/UnifiedJedis.java) and [PipeliningBase.java](src/main/java/redis/clients/jedis/PipeliningBase.java) → dispatchers
  - [BuilderFactory.java](src/main/java/redis/clients/jedis/BuilderFactory.java) and [resps/](src/main/java/redis/clients/jedis/resps/) → response decoding

### 2. Read and Understand

- Read the ENTIRE specification carefully.
- From Command Description, identify the command group (string, list, set, hash, sorted set, stream, geo, hyperloglog, scripting, server, pub/sub, cluster, vector set, …) — this determines which `XxxCommands` interface(s) to extend.
- Determine whether this is a **core** Redis command or a **module** command (RedisJSON, RediSearch, RedisBloom, RedisTimeSeries, VectorSet). Module commands live under their own subpackage (e.g. [json/](src/main/java/redis/clients/jedis/json/), [search/](src/main/java/redis/clients/jedis/search/)) and have their own `*Protocol` enum (e.g. `JsonProtocol.JsonCommand`) rather than `Protocol.Command`.
- Go through the Command API:
  - Identify required and optional arguments.
  - Map each Redis argument type to a Java type: keys are usually `String` AND `byte[]` (binary), integers → `long`, floats → `double`, options → enums under [args/](src/main/java/redis/clients/jedis/args/), composite flag bags → a `Params` class under [params/](src/main/java/redis/clients/jedis/params/).
  - Identify the return value and pick a `BuilderFactory.*` constant. If the response is structured (a record-like reply), plan a new class under [resps/](src/main/java/redis/clients/jedis/resps/) plus a new `Builder<T>` (often added inside `BuilderFactory` or as a `public static final Builder<T> ...` next to the resp class).
  - Note whether response shape diverges between RESP2 and RESP3 — if so, the command object selects a builder via `protocol == RedisProtocol.RESP3 ? ... : ...` (see existing examples in `CommandObjects.java`).
- Check relevant Redis-Cli examples, if provided.
- Review the Test Plan.

### 3. Execute Tasks in Order

#### a. Navigate to the task
- Identify every file the change will touch (use the checklist in step **b**).
- Read existing related entries to copy the surrounding style (Javadoc format, method ordering, parameter naming).

#### b. Implement the command

Touch the layers below in this order — each one builds on the previous. Add overloads for **both** `String` and `byte[]` variants throughout (Jedis treats them as parallel APIs).

1. **Protocol token** — [Protocol.java](src/main/java/redis/clients/jedis/Protocol.java)
   - Add the command name to the appropriate `Protocol.Command` enum entry (kept in semantic groups separated by `// <-- group` comments).
   - Add any new sub-keywords (e.g. `NX`, `EX`, custom option words) to `Protocol.Keyword`.
   - For module commands, add the entry to the module's protocol enum instead (e.g. `JsonProtocol.JsonCommand`).
   - When the wire token differs from the Java identifier (contains a hyphen, etc.), use the explicit-name constructor pattern shown by `SentinelKeyword.GET_MASTER_ADDR_BY_NAME`.

2. **Args enum / Params class** (only if the command takes structured options)
   - **Fixed mutually-exclusive choice** → add an `enum` under [args/](src/main/java/redis/clients/jedis/args/) implementing `Rawable` (model on `BitOP`, `ListPosition`, `FlushMode`).
   - **Open bag of optional flags** → add a `Params` class under [params/](src/main/java/redis/clients/jedis/params/) with fluent setters returning `this` and an `addParams(CommandArguments)` contribution (model on [SetParams.java](src/main/java/redis/clients/jedis/params/SetParams.java), [GetExParams.java](src/main/java/redis/clients/jedis/params/GetExParams.java)).

3. **Public command interface(s)** — [src/main/java/redis/clients/jedis/commands/](src/main/java/redis/clients/jedis/commands/)
   - Declare the method on the matching interface(s):
     - `XxxCommands` — sync, `String` keys/values
     - `XxxBinaryCommands` — sync, `byte[]` keys/values
     - `XxxPipelineCommands` — pipeline/transaction, returns `Response<T>`, `String` keys
     - `XxxPipelineBinaryCommands` — pipeline/transaction, returns `Response<T>`, `byte[]` keys
   - Add full Javadoc on the String overload at minimum: `<b><a href="https://redis.io/commands/<name>">CMD Command</a></b>`, short description, time complexity, `@param`/`@return`.
   - Keep arity overloading consistent with neighboring commands (no-params variant + variant with `Params`).

4. **Command object factories** — [CommandObjects.java](src/main/java/redis/clients/jedis/CommandObjects.java)
   - Add `public final CommandObject<T> xxx(...)` for each overload (String + byte[], with and without `Params`). Build with `commandArguments(Command.XXX).key(key).add(value).addParams(params)` and pass the chosen `BuilderFactory.*` (or a new builder) as the second argument.
   - For zero-arg commands you can cache a single `CommandObject` field (see `PING_COMMAND_OBJECT`).
   - For RESP2/RESP3 split responses, select the builder inline using `protocol == RedisProtocol.RESP3 ? RESP3_BUILDER : RESP2_BUILDER`.

5. **Response decoding** — [BuilderFactory.java](src/main/java/redis/clients/jedis/BuilderFactory.java) / [resps/](src/main/java/redis/clients/jedis/resps/)
   - Prefer reusing existing builders (`STRING`, `BINARY`, `LONG`, `DOUBLE`, `BOOLEAN`, `STRING_LIST`, `BINARY_LIST`, `BOOLEAN_LIST`, `KEYED_TUPLE_LIST`, etc.).
   - For structured replies, add a value class under `resps/` and an associated `Builder<T>` that reads from `Object` (RESP2 array) or from a map (RESP3 map) — follow `LCSMatchResult`, `StreamInfo`, `LibraryInfo` as references.

6. **Sync dispatcher** — [UnifiedJedis.java](src/main/java/redis/clients/jedis/UnifiedJedis.java)
   - Implement each interface method with `@Override` as `return executeCommand(commandObjects.xxx(...));`. Mirror the order from the interface so future readers can diff easily.

7. **Pipeline / transaction dispatcher** — [PipeliningBase.java](src/main/java/redis/clients/jedis/PipeliningBase.java)
   - Implement the `XxxPipelineCommands` / `XxxPipelineBinaryCommands` methods returning `Response<T>` via `return appendCommand(commandObjects.xxx(...));`.
   - This automatically covers both `Pipeline` and `Transaction` (they extend `PipeliningBase`). Do **not** add separate methods to `Pipeline.java` / `Transaction.java` unless the behaviour genuinely differs.

8. **Cluster-only or sharded variants** (only if applicable)
   - If the command must follow a non-standard slot/key extraction, update [ClusterCommandObjects.java](src/main/java/redis/clients/jedis/ClusterCommandObjects.java).

#### c. Verify as you go
- Run `mvn -q -DskipTests compile` after each meaningful set of edits to catch missing imports / type mismatches early.
- Re-check that **every** interface method has a matching `CommandObjects` factory **and** dispatcher implementation. The compiler will surface gaps for the interface implementations, but it cannot tell you that a Pipeline overload is missing if no interface requires it — keep the symmetry manually.
- Run `make format` (delegates to `mvn java-formatter:format`) before committing.

### 4. Implement Testing Plan

After completing implementation, add tests across **three** layers — each one defends against a different class of regression. All test classes are JUnit 5.

1. **CommandObjects integration tests** — [src/test/java/redis/clients/jedis/commands/commandobjects/](src/test/java/redis/clients/jedis/commands/commandobjects/)
   - Add tests to (or create) `CommandObjects<Group>CommandsTest` extending `CommandObjectsStandaloneTestBase`.
   - Each test exercises `exec(commandObjects.xxx(...))` against a real Redis. Cover both the `String` and `byte[]` overloads (suffix the binary tests with `Binary`).
   - This class is parameterized over `RedisProtocol`, so RESP2 and RESP3 are exercised in the same run — no extra wiring needed.

2. **Public-API integration tests** — [src/test/java/redis/clients/jedis/commands/unified/](src/test/java/redis/clients/jedis/commands/unified/) + [commands/jedis/](src/test/java/redis/clients/jedis/commands/jedis/)
   - Extend the matching `<Group>CommandsTestBase` (under `unified/`) with cases that call the public method directly (e.g. `jedis.xxx(...)`).
   - The `commands/jedis/<Group>CommandsTest` subclass picks up the new cases automatically; cluster and pipeline subclasses likewise.

3. **Mocked dispatch unit tests** — [src/test/java/redis/clients/jedis/mocked/unified/](src/test/java/redis/clients/jedis/mocked/unified/) and [mocked/pipeline/](src/test/java/redis/clients/jedis/mocked/pipeline/)
   - Add a test in `UnifiedJedis<Group>CommandsTest` that stubs `commandObjects.xxx(...)` with Mockito, invokes the public method, and verifies that the executor receives the exact `CommandObject`. Same pattern for pipeline tests.
   - This locks down the dispatcher → command-object wiring without needing a server.

4. **Version / capability constraints**
   - If the command requires a minimum server version, annotate the test with `@SinceRedisVersion("X.Y.Z")`.
   - If the command may be disabled on the server (modules, ACL gates), annotate with `@EnabledOnCommand("CMDNAME")`.

5. **Edge-case coverage**
   - Missing keys, empty arguments, max-length arguments, NX/XX-style conditions, error replies (use `assertThrows(JedisDataException.class, ...)`), large binary payloads.

### 5. Run tests

- `make start` (boots the docker-compose test env) then `make mvn-test TEST=<TestClassName>` — or `make test TEST=<TestClassName>` to do both with cleanup.
- For quick iteration without docker: `make start-local && make mvn-test-local TEST=<TestClassName> && make stop-local`.
- Plain Maven: `mvn -Dwith-param-names=true -Dtest=<TestClassName> clean verify`.
- Tests parameterized over `RedisProtocol` cover RESP2 **and** RESP3 in the same run — confirm both protocol rows are green.
- Return to step **3** if any test fails. Treat builder/response shape mismatches as RESP2-vs-RESP3 problems first.

### 6. Final Verification

Before completing:

- ✅ Protocol token added (and any new keywords)
- ✅ `String` and `byte[]` overloads present on every layer
- ✅ Sync + Pipeline interfaces, `CommandObjects` factory, `UnifiedJedis` and `PipeliningBase` dispatchers all in sync
- ✅ Javadoc present on the public methods (with `redis.io` link)
- ✅ Tests added at all three layers (commandobjects, unified/jedis, mocked) and passing under RESP2 + RESP3
- ✅ `mvn -q -DskipTests compile` and `make format` clean
- ✅ Code follows project conventions (method ordering, naming, no rogue imports)

## Output Report

Provide summary:

### Completed Tasks
- List of all tasks completed
- Files created (with paths)
- Files modified (with paths)

### Tests Added
- Test files created / modified
- Test cases implemented
- Test results (RESP2 + RESP3)
