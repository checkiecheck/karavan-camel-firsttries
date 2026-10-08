package checkiecheck.iee2030;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end route-tests voor het IEEE 2030.5-doorgeefluik.
 *
 * Geen adviceWith/Camel-test-API: de test start een echte mini-HTTP-mockserver
 * (jdk HttpServer, zelfde rol als mock-sep2-target.camel.yaml in de Karavan-loop)
 * en laat de proxy-ROUTE er echt tegenaan praten via de test-properties:
 *   - proxy-consumer op poort 18080 (src/test/resources/application.properties)
 *   - SEP2-target -> http://localhost:19443 (deze mockserver)
 *   - allowlist -> device-test-a,device-test-b
 *
 * Scenarios (identiek aan de curl-voorbeelden in de README):
 *   1. geen X-Client-CN      -> 403 client certificate required
 *   2. CN niet in allowlist  -> 403 client not authorized
 *   3. CN in allowlist       -> doorgestuurd naar target, SEP2-pad behouden, response terug
 *   4. POST met body         -> body komt ongewijzigd bij de target aan
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ieee2030ProxyRouteTest {

    static final int PROXY_PORT = 18080;    // ieee2030.proxy.port uit test-properties
    static final int MOCK_PORT = 19443;     // ieee2030.target.port uit test-properties

    static HttpServer mockServer;
    static final List<String> receivedPaths = new CopyOnWriteArrayList<>();
    static final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    static HttpClient client;

    @BeforeAll
    static void startMockSep2Target() throws IOException {
        mockServer = HttpServer.create(new InetSocketAddress("localhost", MOCK_PORT), 0);
        mockServer.createContext("/", (HttpExchange ex) -> {
            receivedPaths.add(ex.getRequestURI().getPath());
            try (InputStream in = ex.getRequestBody()) {
                receivedBodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] resp = "{\"mrid\":\"mock-response\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        mockServer.start();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stopMockSep2Target() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    private HttpResponse<String> request(String method, String cnHeader, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + PROXY_PORT + "/dcap"));
        if ("POST".equals(method)) {
            b.header("Content-Type", "application/json")
             .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.GET();
        }
        if (cnHeader != null) {
            b.header("X-Client-CN", cnHeader);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void weigertZonderClientCNHeader() throws Exception {
        HttpResponse<String> resp = request("GET", null, null);
        assertEquals(403, resp.statusCode());
        assertEquals("{\"error\":\"client certificate required\"}", resp.body());
        // bewijs: niets mag doorgestuurd zijn
        assertEquals(0, receivedPaths.size(), "geen request mag de target bereiken");
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void weigertMetNietToegelatenCN() throws Exception {
        HttpResponse<String> resp = request("GET", "onbekend-device", null);
        assertEquals(403, resp.statusCode());
        assertEquals("{\"error\":\"client not authorized\"}", resp.body());
        assertEquals(0, receivedPaths.size(), "geen request mag de target bereiken");
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void stuurtDoorMetToegelatenCNEnGeeftTargetResponseTerug() throws Exception {
        HttpResponse<String> resp = request("GET", "device-test-a", null);
        assertEquals(200, resp.statusCode(), "target-response moet doorkomen");
        assertTrue(resp.body().contains("mock-response"), "body moet de mock-response bevatten");
        assertEquals("/dcap", receivedPaths.get(receivedPaths.size() - 1),
            "SEP2-pad moet behouden bij doorgifte");
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void geeftBodyOnGewijzigdDoorNaarTarget() throws Exception {
        String sep2Payload = "{\"mrid\":\"payload-123\",\"value\":42}";
        HttpResponse<String> resp = request("POST", "device-test-a", sep2Payload);
        assertEquals(200, resp.statusCode());
        assertEquals(sep2Payload, receivedBodies.get(receivedBodies.size() - 1),
            "body moet ongewijzigd bij de target aankomen");
    }
}
