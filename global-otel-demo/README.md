# global-otel-demo

Should Spring Boot publish its `OpenTelemetry` bean as `GlobalOpenTelemetry`? Every claim
below is either a test in this project or a scan of published jars.

```sh
mvn test   # 15 tests, each test class in its own JVM (the global is per JVM)
```

Versions used: Spring Boot 4.2.0-RC1 (`spring-boot-starter-opentelemetry`), opentelemetry-api
1.67.0, opentelemetry-java-instrumentation 2.33.0. The jar scan also covers Boot 4.2.0 GA.

## Short answer

Publishing Boot's SDK as the global is worth it **only** if you depend on a library that can
get OpenTelemetry *only* from the global. If you do, don't call `GlobalOpenTelemetry.set(bean)`
when the bean is created ("eager"). Publish a delegating instance before any bean exists and
bind it to Boot's bean later ("delegating", in `src/main`). If you run the OpenTelemetry Java
agent, the agent owns the global and none of this applies.

## What is true today

- **Spring Boot never sets the global.** The Boot 4.2.0 jars have no reference to
  `GlobalOpenTelemetry`, and Boot builds its SDK with `OpenTelemetrySdk.builder().build()`,
  not `buildAndRegisterGlobal()`. OpenTelemetry's own Spring Boot starter (2.34.0) doesn't set
  it either. Test: `BootLeavesGlobalUnsetTests`.
- **`GlobalOpenTelemetry.get()` before `set()` installs a no-op for the life of the JVM.** A
  later `set()` throws `IllegalStateException`, with the first caller's stack trace as the
  cause. Since API 1.57, `getOrNoop()` and `isSet()` read the global without locking it, but
  each library has to switch to them. The only way to unset the global is `resetForTest()`.
  Test: `GetBeforeSetTests`.
- **Libraries often cache what they read.** `opentelemetry-aws-sdk-2.2-autoconfigure` reads
  the global once, in a static field, when the first AWS client is built. Whatever the global
  was at that moment is what the JVM keeps.

## Why hooking Boot into the global is good

1. **Libraries that can only use the global start reporting to Boot's pipeline**, with Boot's
   exporter, sampler, resource and propagators. Their spans become children of Boot's spans,
   because both use the same `Context`. Test: `EagerRegistrationTests`.
2. **Without it, those libraries do worse than nothing.** One `get()` locks the global to a
   no-op, so nothing can fix it later in that JVM. Test: `BootLeavesGlobalUnsetTests`.
3. **Libraries that only read the global's propagators** start using Boot's propagators
   instead of none. Examples are the trace context the AWS SDK instrumentation puts on a
   Lambda `Invoke`, and the Lambda helper that reads API Gateway headers.
4. **Code you don't control sees the same SDK.** That covers third-party SDKs that fall back
   to the global (Azure, Spanner, Elasticsearch, Camel, Vert.x; see the table below) and
   instrumentation that's configured by class name, where no Spring bean can be passed in
   (Dubbo and SOFARPC filters, the AWS SDK v1 handler, the AWS SDK v2 SPI interceptor).

## Why it's bad

1. **Order of startup decides whether it works.** In a normal app, an `S3Client` `@Bean` is
   created *before* Boot's `OpenTelemetry` bean. Building it reads the global, which locks it
   to a no-op, so eager registration silently does nothing (it only logs a warning). Test:
   `EagerRegistrationOrderingTests`.
2. **There is one global per JVM, but there can be several contexts per JVM**: test suites
   with cached contexts, DevTools restarts, parent and child contexts. With eager
   registration, a second context sets the global again, but libraries that cached the first
   context's SDK keep sending spans to it. That SDK is shut down, so those spans are lost. A
   third context that starts while the second is still running can't register at all. Test:
   `EagerRegistrationRestartTests`.
3. **You can't cover code that runs before Spring.** Anything that touches the global in a
   static initializer or in `main`, before `SpringApplication.run`, has already locked it.
   Even delegating mode can't recover from that unless `main` installs the bridge first.
   Tests: `CallerBeforeSpringTests`, `InstallFirstInMainTests`.
4. **The Java agent already owns the global.** Boot can't replace it, so you end up with two
   pipelines. Boot's spans go through Boot's exporter and the libraries' spans go through the
   agent's exporter (still in the same trace, because they share `Context`). Use the agent or
   Boot's SDK, not both. Test: `AgentAlreadySetTests`.
5. **An early `get()` can quietly start a second SDK.** With
   `opentelemetry-sdk-extension-autoconfigure` on the classpath and
   `otel.java.global-autoconfigure.enabled=true`, `get()` builds a full SDK from `OTEL_*`
   settings, which exports OTLP to localhost by default. Boot then can't register its own.
   Test: `GlobalAutoconfigureTests`.
6. **Setting the global only fixes traces and propagation, not metrics.** Boot does metrics
   with Micrometer and never creates an OpenTelemetry `SdkMeterProvider`. So OpenTelemetry-API
   meters recorded through the global (or through Boot's bean) are dropped. Test:
   `EagerRegistrationTests.metricsRecordedThroughTheGlobalGoNowhere`.
7. **It hides dependencies.** Code that reads a static global can't be given a different
   instance in a test, and the OpenTelemetry docs recommend passing `OpenTelemetry` instances
   around rather than using the global. Where a library takes an instance, prefer that: for
   example, this repo's starter wires the AWS SDK interceptor from Boot's bean.

## Which libraries use the global

Found by scanning bytecode for calls to `io.opentelemetry.api.GlobalOpenTelemetry`, in all 91
library artifacts of the instrumentation BOM 2.33.0 and in the latest release of about 40
popular libraries (November 2026). `get()` locks the global to a no-op if it runs too early.
`getOrNoop()` and `isSet()` don't lock it.

**OpenTelemetry instrumentation libraries (2.33.0):**

| Artifact | Call | When |
|---|---|---|
| `opentelemetry-aws-sdk-2.2-autoconfigure` (through `aws-sdk-2.2`'s `AwsSdkTelemetryFactory`) | `get()` | Once per JVM, when the first AWS v2 client is built |
| `opentelemetry-aws-sdk-2.2` (`LambdaImpl`) | `getPropagators()` | Every Lambda `Invoke`, even when it's wired from a bean |
| `opentelemetry-aws-sdk-1.11-autoconfigure` | `get()` | Once per JVM, in a static field |
| `opentelemetry-apache-dubbo-2.7` filters | `get()` | Per filter instance (Dubbo creates them through SPI) |
| `opentelemetry-sofa-rpc-5.4-autoconfigure` filters | `get()` | Per filter instance |
| `opentelemetry-kafka-clients-common-0.11` (`KafkaPropagation`) | `getPropagators()` | Only the overload without a propagator argument; `kafka-clients-2.6` passes its own |
| `opentelemetry-aws-lambda-core-1.0` | `getPropagators()` | Reading API Gateway headers |
| `opentelemetry-log4j-context-data-2.17-autoconfigure` | `getOrNoop()` | Config only |
| `opentelemetry-instrumentation-api` (`SemconvStability`) | `getOrNoop()` | Config only |

The other 82 do not use the global, including JDBC, Kafka 2.6, Logback and Log4j appenders,
Spring Web/WebFlux/Kafka, gRPC, OkHttp, Lettuce, Mongo and R2DBC. These take an instance or
use `install(OpenTelemetry)`.

**Other libraries:**

| Library (version scanned) | Call | Notes |
|---|---|---|
| `azure-core-tracing-opentelemetry` 1.0.0-beta.68 | `getTracerProvider()` | Default when no provider is passed in the options |
| `azure-core-metrics-opentelemetry` 1.0.0-beta.41 | `getMeterProvider()` | Same; metrics are dropped under Boot (see "Why it's bad", point 6) |
| `google-cloud-spanner` 6.128.0 | `get()` | Default unless `SpannerOptions.setOpenTelemetry(...)` |
| `elasticsearch-java` 9.5.7 | `get()` | `OpenTelemetryForElasticsearch` default |
| `camel-opentelemetry` 4.23.0 | `get()` | When no `OpenTelemetry` is configured |
| `vertx-opentelemetry` 5.2.3 | `get()` | `OpenTelemetryTracingFactory` no-arg default |
| `anthropic-java-core` 3.14.0 | `getOrNoop()` | Doesn't lock the global |
| `opentelemetry-exporter-otlp` / `-zipkin` (used by Boot) | `getOrNoop()` | Exporter self-metrics, read when the exporter is built |
| `micronaut-tracing-opentelemetry` 8.3.0 | `isSet()`, `get()`, `setResultAsGlobal()` | Reuses the global if it is already set (the Java agent); otherwise builds its own SDK and sets it as the global only if `otel.register.global=true` (default `false`) |
| `helidon-tracing-providers-opentelemetry` 27.0.2 | `isSet()`, `set` | Sets the global by default (`global`, default `true`), unless it is already set |
| `opentelemetry-sdk` / `-sdk-extension-autoconfigure` | `set` | `buildAndRegisterGlobal()`, `setResultAsGlobal()` |
| OpenTelemetry Java agent | `set` | Owns the global when present |

**No reference to the global**: Spring Boot 4.2.0, `opentelemetry-spring-boot-autoconfigure`
2.34.0, Micrometer Tracing's OTel bridge 1.8.0, gax, google-cloud-storage, -pubsub and
-bigtable, grpc-opentelemetry, the AWS SDK v2 core, kafka-clients, Lettuce, Jedis, the MongoDB
driver, the Cassandra driver, Hibernate, HikariCP, OpenSearch, Solr, the Couchbase OTel
module, openai-java, Temporal's tracing module, and the contrib X-Ray and samplers modules.
(`micrometer-registry-otlp` contains a *shaded* copy of the API, so it has a separate global
of its own.)

This is a bytecode scan: it shows a call exists, not how often it runs. The "When" and
"Notes" columns come from reading the sources of the instrumentation libraries, and from the
class and method names for the others.

## The three options, side by side

| | `none` (Boot default) | `eager` | `delegating` |
|---|---|---|---|
| Libraries that use the global are traced | No, and the global gets locked to no-op | Only if first used after Boot's bean is created | Yes, for anything first used after the context starts |
| Bean built before Boot's `OpenTelemetry` | Not traced | Locks the global to no-op | Traced |
| Second context in the same JVM | n/a | Spans lost to the closed SDK | Follows the new context |
| Code that runs before `SpringApplication.run` | Locks the global | Locks the global | Locks it, unless `main` calls `GlobalOpenTelemetryBridge.install()` first |
| Java agent present | Agent's global | Agent's global; logs a warning | Agent's global; logs a warning |
| OTel-API metrics | n/a | Dropped (no `SdkMeterProvider`) | Dropped, and a meter fetched before binding stays a no-op |

Set the mode with `demo.otel.global.mode=none|eager|delegating`. The code is in
`src/main/java/demo/globalotel`: `GlobalOpenTelemetryInitializer` publishes the delegating
global before any bean exists, `GlobalOpenTelemetryAutoConfiguration` binds it (or does eager
registration), and `DelegatingOpenTelemetry` hands out tracers and propagators that look up
the current SDK on every call.

## Questions and their tests

| Question | Test |
|---|---|
| What does `get()` before `set()` do? Do `getOrNoop()` and `isSet()` avoid it? | `GetBeforeSetTests` |
| Does Boot set the global? What happens to a library that relies on it? | `BootLeavesGlobalUnsetTests` |
| Does eager `set(bean)` route global libraries into Boot's SDK? Metrics too? | `EagerRegistrationTests` |
| Eager, when an ordinary bean uses the library first | `EagerRegistrationOrderingTests` |
| Eager, with a second context in the same JVM | `EagerRegistrationRestartTests` |
| Does a delegating global fix ordering and restarts? | `DelegatingGlobalTests` |
| A library used before Spring starts | `CallerBeforeSpringTests`, `InstallFirstInMainTests` |
| Java agent (or anything else) already set the global | `AgentAlreadySetTests` |
| `get()` with global autoconfigure enabled | `GlobalAutoconfigureTests` |
