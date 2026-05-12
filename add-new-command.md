---
description: Adds support for a new Redis command from a given specification. Check command-specification-template.md.
argument-hint: [path-to-specification]
---

# Execute: Add new Redis command support (Jedis)

Read specification file: `$ARGUMENTS`

## 1. Preparations

Skim [README.md](README.md), [pom.xml](pom.xml). Walk an existing command (e.g. `SET`) across the Jedis touchpoints:
- [Protocol.java](src/main/java/redis/clients/jedis/Protocol.java) — `Command` / `Keyword` enums
- [CommandObjects.java](src/main/java/redis/clients/jedis/CommandObjects.java) — `CommandObject<T>` factories
- [commands/](src/main/java/redis/clients/jedis/commands/) — public interfaces (split String / Binary / Pipeline / PipelineBinary)
- [UnifiedJedis.java](src/main/java/redis/clients/jedis/UnifiedJedis.java), [PipeliningBase.java](src/main/java/redis/clients/jedis/PipeliningBase.java) — dispatchers
- [BuilderFactory.java](src/main/java/redis/clients/jedis/BuilderFactory.java), [resps/](src/main/java/redis/clients/jedis/resps/) — response decoding

## 2. Read and Understand

- Read the full spec. Identify the command group (string/list/set/hash/zset/stream/geo/hll/script/server/pubsub/cluster/vset/…) → selects the `XxxCommands` interface family.
- Core vs module command? Modules (JSON/Search/Bloom/TimeSeries/VectorSet) live under their own subpackage with their own `*Protocol` enum (e.g. `JsonProtocol.JsonCommand`).
- Map args to Java types: keys → both `String` and `byte[]`; ints → `long`; floats → `double`; fixed choice → enum in [args/](src/main/java/redis/clients/jedis/args/); option bag → class in [params/](src/main/java/redis/clients/jedis/params/).
- Pick a return `BuilderFactory.*`. For structured replies, plan a class in [resps/](src/main/java/redis/clients/jedis/resps/) + a `Builder<T>`.
- If the reply shape differs RESP2 vs RESP3, pick the builder inline via `protocol == RedisProtocol.RESP3 ? ... : ...` (pattern used in `CommandObjects.java`).
- Review redis-cli examples and the Test Plan.

## 3. Implement

Always add **both** `String` and `byte[]` overloads. **Javadoc every new public class and method** — class-level on Params / Args / resp types; per-method on fluent setters, enum constants, interface methods, and resp getters. Binary and pipeline overloads `{@link}` back to the canonical method rather than duplicate, but are never left bare. Inline comments only where the *why* is non-obvious (RESP2/3 quirks, version workarounds, subtle invariants).

Layers in order:

1. **Protocol token** — [Protocol.java](src/main/java/redis/clients/jedis/Protocol.java): add to `Protocol.Command` (in the right `// <-- group` block) and any new sub-tokens to `Protocol.Keyword`. Module commands go into the module's `*Protocol` enum. When the wire token != Java identifier (hyphens, etc.), use the explicit-name constructor (see `SentinelKeyword.GET_MASTER_ADDR_BY_NAME`).
2. **Args / Params** (only if structured options): mutually-exclusive choice → enum in [args/](src/main/java/redis/clients/jedis/args/) implementing `Rawable` (model: `BitOP`, `ListPosition`); open flag bag → class in [params/](src/main/java/redis/clients/jedis/params/) with fluent setters + `addParams(CommandArguments)` (model: [SetParams.java](src/main/java/redis/clients/jedis/params/SetParams.java), [GetExParams.java](src/main/java/redis/clients/jedis/params/GetExParams.java)).
3. **Public interfaces** — [commands/](src/main/java/redis/clients/jedis/commands/): declare on `XxxCommands` (sync, String), `XxxBinaryCommands` (sync, byte[]), `XxxPipelineCommands` (`Response<T>`, String), `XxxPipelineBinaryCommands` (`Response<T>`, byte[]). The canonical (String) overload gets the full Javadoc: `<b><a href="https://redis.io/commands/<name>">CMD Command</a></b>` link, short description, time complexity, `@param`/`@return`. Mirror arity (no-params + with-`Params`) of neighbors.
4. **CommandObjects factories** — [CommandObjects.java](src/main/java/redis/clients/jedis/CommandObjects.java): `public final CommandObject<T> xxx(...)` for every overload (String+byte[], with/without `Params`). Build with `commandArguments(Command.XXX).key(key).add(value).addParams(params)` + `BuilderFactory.*`. Cache zero-arg variants (see `PING_COMMAND_OBJECT`). For RESP2/3 split: select builder inline.
5. **Response decoding** — [BuilderFactory.java](src/main/java/redis/clients/jedis/BuilderFactory.java) / [resps/](src/main/java/redis/clients/jedis/resps/): reuse existing builders (`STRING`, `BINARY`, `LONG`, `DOUBLE`, `BOOLEAN`, `STRING_LIST`, `BINARY_LIST`, `KEYED_TUPLE_LIST`, …). For structured replies add resp class + `Builder<T>` handling RESP2 array AND RESP3 map (models: `LCSMatchResult`, `StreamInfo`, `LibraryInfo`). Javadoc the `Builder<T>` only when its parsing rule is non-obvious.
6. **Sync dispatcher** — [UnifiedJedis.java](src/main/java/redis/clients/jedis/UnifiedJedis.java): `@Override` each interface method as `return executeCommand(commandObjects.xxx(...));` in interface order.
7. **Pipeline dispatcher** — [PipeliningBase.java](src/main/java/redis/clients/jedis/PipeliningBase.java): `return appendCommand(commandObjects.xxx(...));`. Covers both `Pipeline` and `Transaction`; don't duplicate in those classes unless behavior diverges.
8. **Cluster override** (only if key/slot extraction is non-standard) — [ClusterCommandObjects.java](src/main/java/redis/clients/jedis/ClusterCommandObjects.java).

**Verify as you go:** `mvn -q -DskipTests compile` after each layer. Manually check symmetry — the compiler enforces interface contracts but not Pipeline parity. Run `make format` before commit.

## 4. Tests (three layers, all JUnit 5)

1. **CommandObjects integration** — [commands/commandobjects/](src/test/java/redis/clients/jedis/commands/commandobjects/): add to `CommandObjects<Group>CommandsTest extends CommandObjectsStandaloneTestBase`. Calls `exec(commandObjects.xxx(...))` against real Redis. Cover String AND byte[] (binary tests suffixed `Binary`). Parameterized over `RedisProtocol` → RESP2 + RESP3 in one run.
2. **Public-API integration** — [commands/unified/](src/test/java/redis/clients/jedis/commands/unified/) + [commands/jedis/](src/test/java/redis/clients/jedis/commands/jedis/): extend `<Group>CommandsTestBase` calling `jedis.xxx(...)` directly. The `jedis/`, cluster, pipeline subclasses inherit automatically.
3. **Mocked dispatch** — [mocked/unified/](src/test/java/redis/clients/jedis/mocked/unified/), [mocked/pipeline/](src/test/java/redis/clients/jedis/mocked/pipeline/): Mockito-stub `commandObjects.xxx(...)`, invoke public method, `verify` executor receives the exact `CommandObject`. Locks down dispatcher wiring without a server.

**Annotations:** `@SinceRedisVersion("X.Y.Z")` for min-version gates, `@EnabledOnCommand("CMDNAME")` for capability gates.

**Edge cases:** missing keys, empty/max-length args, NX/XX conditions, error replies (`assertThrows(JedisDataException.class, ...)`), large binary payloads.

## 5. Run

- Plain Maven: `mvn -Dwith-param-names=true -Dtest=<TestClassName> clean verify`.
- Confirm both RESP2 and RESP3 parameterized rows pass. On failure, suspect RESP2/3 builder mismatch first; loop back to step 3.

## 6. Final Verification

- ✅ Protocol token + any new keywords
- ✅ String + byte[] overloads on every layer
- ✅ Sync + Pipeline interfaces, `CommandObjects`, `UnifiedJedis`, `PipeliningBase` all in sync
- ✅ Javadoc on every new public class + method (redis.io link on canonical interface method); inline comments only where the *why* is non-obvious
- ✅ Tests at all three layers, green on RESP2 + RESP3
- ✅ `mvn -q -DskipTests compile` + `make format` clean
- ✅ Method ordering / naming / imports match project conventions

## Output Report

### Completed Tasks
- Tasks completed
- Files created (paths)
- Files modified (paths)

### Tests Added
- Test files created / modified
- Test cases implemented
- Test results (RESP2 + RESP3)
