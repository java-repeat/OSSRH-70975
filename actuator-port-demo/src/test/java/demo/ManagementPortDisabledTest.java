package demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.actuate.endpoint.web.servlet.WebMvcEndpointHandlerMapping;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;

class ManagementPortDisabledTest {

    static void dump(String label, ApplicationContext ctx) {
        String[] actuatorBeans = Arrays.stream(ctx.getBeanDefinitionNames())
                .filter(n -> n.toLowerCase().contains("endpoint"))
                .sorted().toArray(String[]::new);
        System.out.println("=== " + label + ": " + actuatorBeans.length + " beans with 'endpoint' in name");
        for (String n : actuatorBeans) System.out.println("    " + n);
    }

    /** Baseline: no management.server.port set -> actuator shares the app port. */
    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
    class Baseline {
        @Autowired TestRestTemplate rest;
        @Autowired ApplicationContext ctx;

        @Test
        void actuatorServedOnMainPort() {
            dump("baseline", ctx);
            assertThat(rest.getForEntity("/actuator", String.class).getStatusCode().value()).isEqualTo(200);
            assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode().value()).isEqualTo(200);
            assertThat(ctx.getBeansOfType(WebMvcEndpointHandlerMapping.class)).hasSize(1);
        }
    }

    /** management.server.port=-1 */
    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
            properties = "management.server.port=-1")
    class PortMinusOne {
        @Autowired TestRestTemplate rest;
        @Autowired ApplicationContext ctx;

        @Test
        void noActuatorHttpAnywhere() {
            dump("port=-1", ctx);
            assertThat(rest.getForEntity("/actuator", String.class).getStatusCode().value()).isEqualTo(404);
            assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode().value()).isEqualTo(404);
            // No web endpoint infrastructure at all
            assertThat(ctx.getBeansOfType(WebMvcEndpointHandlerMapping.class)).isEmpty();
            // NOTE: the discoverer bean is still created (WebEndpointAutoConfiguration is not
            // port-conditional) and still discovers the health endpoint; it is simply never
            // mapped to HTTP because the management-context handler mapping is never created.
            WebEndpointsSupplier supplier = ctx.getBean(WebEndpointsSupplier.class);
            System.out.println("=== port=-1: web endpoints discovered = " + supplier.getEndpoints());
            assertThat(supplier.getEndpoints()).isNotEmpty();
            // Only one web server context: no child management context was created
            assertThat(ctx.getParent()).isNull();
            System.out.println("=== port=-1: HealthEndpoint beans = " + ctx.getBeansOfType(HealthEndpoint.class).keySet());
        }
    }

    /** port=-1 even with every endpoint explicitly exposed over web. */
    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
            properties = {"management.server.port=-1",
                          "management.endpoints.web.exposure.include=*"})
    class PortMinusOneExposeAll {
        @Autowired TestRestTemplate rest;
        @Autowired ApplicationContext ctx;

        @Test
        void stillNoActuatorHttp() {
            dump("port=-1 + exposure=*", ctx);
            assertThat(rest.getForEntity("/actuator", String.class).getStatusCode().value()).isEqualTo(404);
            assertThat(rest.getForEntity("/actuator/env", String.class).getStatusCode().value()).isEqualTo(404);
            assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode().value()).isEqualTo(404);
            assertThat(ctx.getBeansOfType(WebMvcEndpointHandlerMapping.class)).isEmpty();
        }
    }

    /**
     * port=-1 but health probes mapped onto the MAIN server port via additional-path.
     * Does that bypass the "disabled" management port?
     */
    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
            properties = {"management.server.port=-1",
                          "management.endpoint.health.probes.enabled=true",
                          "management.endpoint.health.probes.add-additional-paths=true",
                          "management.endpoint.health.group.custom.include=ping",
                          "management.endpoint.health.group.custom.additional-path=server:/custom-health"})
    class PortMinusOneWithAdditionalPaths {
        @Autowired TestRestTemplate rest;

        @Test
        void additionalPathsOnMainPort() {
            for (String path : new String[] {"/livez", "/readyz", "/custom-health", "/actuator/health"}) {
                var r = rest.getForEntity(path, String.class);
                System.out.println("=== port=-1 + additional-path: GET " + path + " -> "
                        + r.getStatusCode().value() + " " + r.getBody());
            }
            // /actuator/* is gone...
            assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode().value()).isEqualTo(404);
            // ...but health groups with additional-path=server:... are STILL served on the main port.
            assertThat(rest.getForEntity("/livez", String.class).getStatusCode().value()).isEqualTo(200);
            assertThat(rest.getForEntity("/readyz", String.class).getStatusCode().value()).isEqualTo(200);
            assertThat(rest.getForEntity("/custom-health", String.class).getStatusCode().value()).isEqualTo(200);
        }
    }
}
