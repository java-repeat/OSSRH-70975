# actuator-port-demo

Question: does `management.server.port=-1` disable actuator over HTTP completely?

Empty Spring Boot 3.5.0 app (`web` + `actuator`). Run `mvn test`. See `ManagementPortDisabledTest`.

| Config | `/actuator`, `/actuator/*` | `/livez`, `/readyz`, custom `server:` paths |
|---|---|---|
| default | 200 | 404 |
| `management.server.port=-1` | 404 | 404 |
| `-1` + `management.endpoints.web.exposure.include=*` | 404 | 404 |
| `-1` + health probes / groups with `additional-path=server:...` | 404 | **200** |

Findings:
- `-1` removes every actuator URL under `/actuator`. No management server or child context is started, and
  `WebMvcEndpointHandlerMapping` is never created. Setting `exposure.include=*` does not bring them back.
- The endpoint **beans** are still created, and `WebEndpointDiscoverer` still finds them. Only the HTTP
  mapping is missing. With `exposure.include=*`, beans such as `env` and `beans` exist but cannot be reached over HTTP.
- **Exception:** health groups with `additional-path=server:/...`, including
  `management.endpoint.health.probes.add-additional-paths=true`, are still served on the
  main application port.
- JMX exposure is a separate setting and is not affected by the port.

## Alternatives (`AlternativesTest`)

Each case also turns on health probes with `add-additional-paths=true`, so `/livez` shows whether the probe paths still respond.

| Config | `/actuator` | `/actuator/health` | `/livez` | endpoint beans |
|---|---|---|---|---|
| `management.server.port=-1` | 404 | 404 | **200** | created |
| exclude `ManagementContextAutoConfiguration` | 404 | 404 | **200** | created |
| `management.endpoints.web.exposure.exclude=*` | **200** (empty links page) | 404 | 404 | none |
| `management.endpoints.access.default=none` | **200** (empty links page) | 404 | 404 | none |
| `access.default=none` + `web.discovery.enabled=false` | 404 | 404 | 404 | none |
| `access.default=none` + `server.port=-1` | 404 | 404 | 404 | none |

The fullest switch-off without removing the dependency: `management.endpoints.access.default=none` plus
`management.server.port=-1`. This leaves no endpoint beans, no handler mapping and no actuator URL.
