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
