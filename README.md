# Java DAP Adapter (Standalone)

Standalone Debug Adapter Protocol (DAP) server for Java, wrapping [Microsoft's java-debug-core](https://github.com/microsoft/java-debug).

Designed for **AI-agent integration** — enables the Oh My Pi harness `debug` tool to debug Java applications via JDWP.

## Architecture

```
AI Agent (Oh My Pi)
  └── debug(action="attach", adapter="java -jar java-dap-adapter.jar ...", port=5005)
        └── java-dap-adapter (stdin/stdout DAP)
              └── JDI → JDWP → Target JVM (:5005)
```

## Requirements

- **JDK 21+** to build and run (pom.xml compiles to Java 21 bytecode).
- Target JVM must be started with JDWP enabled, e.g. `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005`.

## Build

```cmd
mvn package -DskipTests
```

Produces: `target/java-dap-adapter-1.0.1.jar` (fat JAR, all dependencies included)

## Usage

### With Oh My Pi harness (no script needed)

OMP's built-in `debug` tool speaks DAP natively. Just point it at the adapter JAR:

```python
debug(action="attach",
      adapter="java -jar /path/to/java-dap-adapter-1.0.1.jar --source-roots /path/to/your/project/src/main/java",
      port=5005)
debug(action="set_breakpoint", file="MyClass.java", line=42)
debug(action="continue")
# stopped → inspect
debug(action="stack_trace")
debug(action="variables", scope_id=1)
```

No Python wrapper, no threading, no DAP protocol parsing — OMP handles it all.

### Standalone (stdin/stdout DAP, for non-OMP clients)
```cmd
java -jar target/java-dap-adapter-1.0.1.jar --source-roots "/path/to/your/project/src/main/java"
```

Multiple roots may be separated with `;`.

## Capabilities

| Feature | Status |
|---------|--------|
| Attach to remote JVM | ✅ |
| Line breakpoints | ✅ |
| Method breakpoints | ✅ |
| Conditional breakpoints | ✅ (JDI evaluator; no arithmetic — see [Expression Grammar](#expression-grammar)) |
| Stack frames | ✅ |
| Variables / scopes | ✅ |
| Threads | ✅ |
| Step in/out/over | ✅ |
| Expression evaluation | ✅ (JDI-based; no arithmetic, no arrays, no constructors — see [Expression Grammar](#expression-grammar)) |
| Hot code replace | ❌ (use IDE or `jdb redefine`) |
| Completions | ❌ |

## Limitations & Known Issues

- **Expression evaluation is JDI-based, not compiler-backed.** Basic reads, comparisons, method calls, and boolean logic work; arithmetic (`+`, `-`, `*`, `/`), array indexing (`a[i]`), constructor calls (`new Foo()`), casts, and lambdas do not. Full grammar below.
- **Source lookup is filesystem-based** — pass `--source-roots` pointing to `src/main/java` directories.
- **No project model** — breakpoint class resolution uses path-to-package mapping, not a build system.
- **Deadlock risk on hot paths** — a breakpoint on code that holds a lock or blocks a response another client is waiting on will hang the client while the JVM is paused. Prefer log-based profiling for hot paths.

## Recommended Profiling Approach

For performance measurement (counting method calls, timing), log-based profiling
in the target application is almost always superior to breakpoints:

| Approach | Pros | Cons |
|----------|------|------|
| DAP breakpoints | Exact hit counts | Pauses JVM, deadlocks with concurrent clients, slow |
| In-process logging | Zero overhead, no deadlocks, concurrent-safe | Requires instrumentation in source |

## Expression Grammar

The bundled `JdiEvaluationProvider` interprets expressions directly against JDI —
no compilation step, no JDT. Everything below works for both the DAP `evaluate`
request and conditional breakpoints:

| Form | Example |
|------|---------|
| Local / parameter read | `count` |
| Field access chain | `user.address.city` |
| Method call (no-arg) | `list.size()` |
| Method call (single or multi arg) | `map.get("key")`, `str.substring(1, 3)` |
| Static field | `Integer.MAX_VALUE`, `com.example.MyClass.FIELD` |
| Static method | `Math.abs(x)` (no arithmetic on the arg — pass a variable) |
| Literals | `42`, `3.14`, `1000L`, `1.5f`, `"hello"`, `true`, `false`, `null` |
| Comparisons | `==`, `!=`, `>`, `<`, `>=`, `<=` |
| Null / string equality | `x == null`, `s1 == s2` (uses `.equals()` for strings) |
| Boolean logic | `&&`, `\|\|`, `!` (short-circuiting) |
| `instanceof` | `obj instanceof String` (short or FQN) |
| Parenthesization | `(a == 1) \|\| (b == 2)` |
| `this` | `this`, `this.field` |

**Not supported:** arithmetic (`+ - * /`), array indexing (`a[i]`), constructors (`new Foo()`), explicit casts (`(String) x`), lambdas, assignment.

On a conditional-breakpoint evaluation error, the breakpoint stops the JVM anyway and the error is logged to stderr — so a bad condition surfaces instead of being silently swallowed.

## Future Work

- Integrate [Eclipse ECJ](https://wiki.eclipse.org/JDT_Core_Programmer_Guide/ECJ) for full-Java expression evaluation (arithmetic, casts, lambdas)
- Add JFR integration for performance profiling via DAP custom requests
- Support `launch` mode (start JVM from the adapter)
- Hot code replace via ECJ + JDI `redefineClasses`
