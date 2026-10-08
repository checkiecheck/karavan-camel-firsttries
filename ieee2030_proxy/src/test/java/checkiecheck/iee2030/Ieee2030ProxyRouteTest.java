package checkiecheck.iee2030;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import io.quarkus.test.junit.QuarkusTest;
import org.apache.camel.CamelContext;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.quarkus.test.CamelQuarkusTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Route-tests voor het IEEE 2030.5-doorgeefluik.
 *
 * De echte SEP2-target (netty-http producer) wordt vervangen door een
 * direct:mock-sep2 route — dezelfde mocks als de Karavan-dev-loop
 * (mock-sep2-target.camel.yaml), maar dan in de CI afdwingbaar.
 *
 * Scenarios die de specialist in Karavan handmatig kan naspelen met curl,
 * staan hier als repeateerbare bewijzen:
 *   1. geen X-Client-CN      -> 403 client certificate required
 *   2. CN niet in allowlist  -> 403 client not authorized
 *   3. CN in allowlist       -> doorgestuurd naar target, response terug
 *   4. POST met body         -> body komt ongewijzigd bij de target aan
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ieee2030ProxyRouteTest extends CamelQuarkusTestSupport {

    static final int PROXY_PORT = 18080;
    static final AtomicReference<String> forwardedUri = new AtomicReference<>();
    static final AtomicReference<String> forwardedBody = new AtomicReference<>();
    static HttpClient client;

    /** adviceWith vóór de start: target-producer vervangen door direct:mock-sep2 */
    @Override
    public boolean isUseAdviceWith() {
        return true;
    }

    /** Mock-target met inbouw: onthoud wat er doorgestuurd werd + canned 200-response */
    @Override
    protected RoutesBuilder createTestCamelContextRoutes() {
        return new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:mock-sep2")
                    .id("mock-sep2")
                    .process(exchange -> {
                        forwardedUri.set(exchange.getIn().getHeader("CamelHttpUri", String.class));
                        forwardedBody.set(exchange.getIn().getBody(String.class));
                    })
                    .setHeader("CamelHttpResponseCode", constant(200))
                    .setHeader("Content-Type", constant("application/json"))
                    .setBody(constant("{\"mrid\":\"mock-response\"}"));
            }
        };
    }

    @BeforeAll
    static void httpClient() {
        client = HttpClient.newHttpClient();
    }

    @BeforeEach
    void startRouteMetMockTarget() throws Exception {
        Advice.with(context(), "ieee2030-proxy-route",
            a -> a.weaveById("to-ieee2030-target").replace().to("direct:mock-sep2"));
        context().start();
    }

    private HttpResponse<String> get(String cnHeader) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + PROXY_PORT + "/dcap"))
            .GET();
        if (cnHeader != null) {
            b.header("X-Client-CN", cnHeader);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String cnHeader, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + PROXY_PORT + "/dcap"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (cnHeader != null) {
            b.header("X-Client-CN", cnHeader);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void weigertZonderClientCNHeader() throws Exception {
        HttpResponse<String> resp = get(null);
        assertEquals(403, resp.statusCode());
        assertEquals("{\"error\":\"client certificate required\"}", resp.body());
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void weigertMetNietToegelatenCN() throws Exception {
        HttpResponse<String> resp = get("onbekend-device");
        assertEquals(403, resp.statusCode());
        assertEquals("{\"error\":\"client not authorized\"}", resp.body());
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void stuurtDoorMetToegelatenCNEnGeeftTargetResponseTerug() throws Exception {
        // allowlist staat in src/test/resources/application.properties: device-test-a
        HttpResponse<String> resp = get("device-test-a");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("mock-response"), "target-response moet doorkomen");
        // bewijs dat de proxy naar de juiste base-URL + pad doorstuurt:
        assertTrue(forwardedUri.get().endsWith("/dcap"),
            "CamelHttpUri moet het SEP2-pad bevatten, was: " + forwardedUri.get());
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void geeftBodyOnGewijzigdDoorNaarTarget() throws Exception {
        String sep2Payload = "{\"mrid\":\"payload-123\",\"value\":42}";
        HttpResponse<String> resp = post("device-test-a", sep2Payload);
        assertEquals(200, resp.statusCode());
        assertEquals(sep2Payload, forwardedBody.get(), "body moet ongewijzigd bij de target aankomen");
    }

    // --- Camélcontext: netty-consumer start op de test-poort uit de test-properties ---

    @Override
    protected CamelContext createCamelContext() throws Exception {
        CamelContext ctx = super.createCamelContext();
        return ctx;
    }
}
