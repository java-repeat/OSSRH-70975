# aws-otel-spring-boot-starter

Adds AWS awareness to Spring Boot 4's own `spring-boot-starter-opentelemetry`.
Boot keeps ownership of the SDK, sampling, W3C propagation and OTLP export;
this starter only plugs into the beans Boot already creates.

| Concern | How | Why this way |
|---|---|---|
| Resource attributes (traces): AWS built in, plus other clouds and your own | `BeanPostProcessor` merges detected attributes into Boot's `Resource` bean | Replacing the bean would drop Boot's `OTEL_RESOURCE_ATTRIBUTES` / `management.opentelemetry.resource-attributes` merging |
| Resource attributes (metrics) | `BeanPostProcessor` wraps Boot's `OtlpConfig` bean so `resourceAttributes()` includes them | Boot's Micrometer OTLP registry never reads the `Resource` bean, so without this metrics carry only `service.name` |
| AWS SDK v2 spans | Global SPI interceptor (`LazyOtelAwsInterceptor`) bound to Boot's `OpenTelemetry` bean | Traces every client, including ones built inside libraries, with no `GlobalOpenTelemetry` and no startup-ordering trap |
| X-Ray headers (opt-in) | Extra `TextMapPropagator` bean | Boot composes every `TextMapPropagator` bean into `ContextPropagators` |

## Use it

```xml
<dependency>
  <groupId>com.example.otel</groupId>
  <artifactId>aws-otel-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
spring:
  application:
    name: orders

management:
  tracing:
    sampling:
      probability: 1.0
  opentelemetry:
    otlp:
      endpoint: http://localhost:4318   # Boot 4.2 common OTLP endpoint

aws:
  otel:
    resources:
      enabled: true                     # default
    xray-propagator:
      enabled: false                    # default; true if callers/ALB send X-Amzn-Trace-Id
    sdk:
      enabled: true                     # default
      experimental-span-attributes: false
```

Build AWS clients however you like. Nothing to wire:

```java
@Bean
S3Client s3() {
  return S3Client.create(); // traced automatically
}
```

AWS SDK spans become children of the current Micrometer observation (for example the
incoming HTTP request), because Boot's Micrometer bridge uses the OpenTelemetry `Context`.

## Resource detection: other clouds and your own detectors

Detection runs once at startup. Its result goes on traces and metrics, and Boot's own
configuration (`spring.application.name`, `OTEL_RESOURCE_ATTRIBUTES`,
`management.opentelemetry.resource-attributes`) always wins over anything detected.
Detectors run in this order, and a later one wins on a conflicting attribute:

| Order | Detector | What it does |
|---|---|---|
| `-200` (`ResourceDetector.AWS_ORDER`) | `awsResourceDetector` | Merges the contrib Beanstalk, EC2, ECS, EKS and Lambda detectors |
| `-100` (`ResourceDetector.RESOURCE_PROVIDERS_ORDER`) | `resourceProviderDetector` | Every OpenTelemetry `ResourceProvider` on the classpath (`META-INF/services`) |
| last (no `@Order`) | your `ResourceDetector` beans | Whatever you return |

**Another cloud:** add its contrib jar; nothing to wire. For example
`io.opentelemetry.contrib:opentelemetry-gcp-resources` or `opentelemetry-azure-resources`.
The contrib cloud providers skip themselves once `cloud.provider` is set, so on AWS they
never probe their metadata endpoints.

**Your own, as a Spring bean:**

```java
@Bean
ResourceDetector teamDetector() {
  return () -> Resource.builder().put("team", "payments").build();
}
```

Override `detect(Resource detected)` instead to see what earlier detectors found, for
example to skip work once `cloud.provider` is known.

**Your own, as an OpenTelemetry `ResourceProvider`:** implement
`io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider` and list it in
`META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider`. The same class
works with the OpenTelemetry Java agent. Its `ConfigProperties` reads `otel.*` keys from
the Spring `Environment`, so `OTEL_*` environment variables and `application.yml` both work.

**Filtering classpath providers** uses OpenTelemetry's standard keys, with class names:

```yaml
otel:
  java:
    disabled:
      resource:
        providers: io.opentelemetry.contrib.gcp.resource.GCPResourceProvider
    # or allow-list: otel.java.enabled.resource.providers
```

**Replacing a built-in:** declare a bean named `awsResourceDetector` or
`resourceProviderDetector`. Give it the same `@Order` if it should keep its place; without
one it runs last.

## Design notes

- **Why not `opentelemetry-aws-sdk-2.2-autoconfigure`?** Its telemetry is a JVM-wide
  static singleton that calls `GlobalOpenTelemetry.get()` once, when the first AWS client
  is built. Spring Boot never sets the global, and `get()` before `set()` installs a no-op
  permanently, so one early client disables AWS tracing for the whole JVM.
- **Calls during startup:** AWS calls made before Boot's `OpenTelemetry` bean exists pass
  through untraced. Binding happens the moment that bean is initialized. Each call pins its
  delegate in `beforeExecution`, so a call is never half-instrumented.
- **AWS detector order** mirrors OpenTelemetry's declarative `aws` detector (Beanstalk,
  EC2, ECS, EKS, Lambda), so the more specific platform wins on overlapping attributes.
  It merges all five on purpose: with the contrib `ResourceProvider`s the first match stops
  the rest, so ECS on EC2 would keep only the EC2 attributes.
- **Startup latency off AWS:** the EC2 detector probes IMDS. Set
  `aws.otel.resources.enabled=false` locally, or replace the `awsResourceDetector` bean
  (for example with `Resource::empty`).
- **`ConditionalResourceProvider`** is in the SPI's `internal` package. The contrib
  detectors and the SDK both use it, so the starter does too, but it may change between
  OpenTelemetry releases.
- **X-Ray order:** this auto-configuration runs after Boot's, so the X-Ray propagator is
  composed last. On extract the last propagator wins, so `X-Amzn-Trace-Id` takes precedence
  when both headers arrive. Outgoing calls carry both.
- **AWS SDK calls always send `X-Amzn-Trace-Id`:** the AWS SDK instrumentation injects it
  itself, whatever `aws.otel.xray-propagator.enabled` says. That property only changes
  Boot's own propagation (incoming requests and non-AWS HTTP clients).
- **Metrics resource:** Micrometer's resource attributes are strings, so array-valued AWS
  attributes are exported as comma-separated values.
- **Lambda `Invoke` context:** the AWS SDK instrumentation injects trace context into a
  Lambda `Invoke` client context using `GlobalOpenTelemetry.getPropagators()`, not the
  instance it was built with. Without a global, that injection is a no-op (the span is
  still recorded; the invoked function just won't continue the trace), and the first such
  call locks the global to a no-op. If you need it, publish Boot's `OpenTelemetry` as the
  global very early, accepting the ordering caveats.
- **SQS producer/consumer links** need `AwsSdkTelemetry#wrap(SqsClient)` on top of the
  interceptor; add a `BeanPostProcessor` for `SqsClient` beans if you need them.

## Verifying with a local collector

`mvn verify` runs the unit tests and an end-to-end test against fakes. To check what a real
OpenTelemetry Collector receives, run (needs Go; the collector is built on first run):

```sh
./local-collector/verify.sh
```

It starts a collector on `127.0.0.1:4318` with the debug and file exporters, runs
`LocalCollectorTests` (a web app whose request calls S3), and asserts the collector got
the server and `S3.ListBuckets` spans with the right parent/child links and attributes,
and AWS resource attributes on both traces and metrics. Output is left in
`local-collector/target/out` (`traces.jsonl`, `metrics.jsonl`, `collector.log`).

## Verified against

Spring Boot 4.2.0-RC1 source, opentelemetry-java-instrumentation v2.33.0,
opentelemetry-java-contrib v1.61.0 and AWS SDK for Java v2 (current `ExecutionInterceptor`).
