package demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.servlet.WebMvcEndpointHandlerMapping;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;

/**
 * Other ways to switch actuator off. Every case also turns on the health probes
 * with additional paths, to see which ways also stop /livez on the main port.
 */
class AlternativesTest {

    static final String PROBES1 = "management.endpoint.health.probes.enabled=true";
    static final String PROBES2 = "management.endpoint.health.probes.add-additional-paths=true";

    abstract static class Base {
        @Autowired TestRestTemplate rest;
        @Autowired ApplicationContext ctx;

        int get(String path) { return rest.getForEntity(path, String.class).getStatusCode().value(); }

        long endpointBeans() {
            return Arrays.stream(ctx.getBeanDefinitionNames())
                    .filter(n -> n.endsWith("Endpoint")).count();
        }

        void report(String label) {
            System.out.printf("=== %-28s /actuator=%d /actuator/health=%d /livez=%d handlerMapping=%d endpointBeans=%d%n",
                    label, get("/actuator"), get("/actuator/health"), get("/livez"),
                    ctx.getBeansOfType(WebMvcEndpointHandlerMapping.class).size(), endpointBeans());
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
            properties = {"management.server.port=-1", PROBES1, PROBES2})
    class PortMinusOne extends Base {
        @Test void run() {
            report("port=-1");
            assertThat(get("/actuator")).isEqualTo(404);
            assertThat(get("/livez")).isEqualTo(200); // leaks
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
            properties = {"management.endpoints.access.default=none", PROBES1, PROBES2})
    class AccessNone extends Base {
        @Test void run() {
            report("access.default=none");
            assertThat(get("/actuator")).isEqualTo(200); // empty links page still served
            assertThat(get("/actuator/health")).isEqualTo(404);
            assertThat(get("/livez")).isEqualTo(404);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
            properties = {"management.endpoints.web.exposure.exclude=*", PROBES1, PROBES2})
    class ExposureExcludeAll extends Base {
        @Test void run() {
            report("web.exposure.exclude=*");
            assertThat(get("/actuator")).isEqualTo(200); // empty links page still served
            assertThat(get("/actuator/health")).isEqualTo(404);
            assertThat(get("/livez")).isEqualTo(404);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
            properties = {"spring.autoconfigure.exclude="
                    + "org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration",
                    PROBES1, PROBES2})
    class ExcludeManagementContextAutoConfig extends Base {
        @Test void run() {
            report("exclude ManagementContextAC");
            assertThat(get("/actuator")).isEqualTo(404);
            assertThat(get("/livez")).isEqualTo(200); // leaks, same as port=-1
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
            properties = {"management.endpoints.access.default=none",
                          "management.endpoints.web.discovery.enabled=false", PROBES1, PROBES2})
    class AccessNoneNoDiscovery extends Base {
        @Test void run() {
            report("access=none + discovery=off");
            assertThat(get("/actuator")).isEqualTo(404);
            assertThat(get("/actuator/health")).isEqualTo(404);
            assertThat(get("/livez")).isEqualTo(404);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
            properties = {"management.endpoints.access.default=none",
                          "management.server.port=-1", PROBES1, PROBES2})
    class AccessNoneAndPortMinusOne extends Base {
        @Test void run() {
            report("access=none + port=-1");
            assertThat(get("/actuator")).isEqualTo(404);
            assertThat(get("/actuator/health")).isEqualTo(404);
            assertThat(get("/livez")).isEqualTo(404);
            assertThat(endpointBeans()).isZero();
        }
    }
}
