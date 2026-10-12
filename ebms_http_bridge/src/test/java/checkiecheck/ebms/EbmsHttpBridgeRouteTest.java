package checkiecheck.ebms;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.quarkus.test.junit.QuarkusTest;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.quarkus.test.CamelQuarkusTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Route-tests voor de EBMS -> legacy-app webhook-bridge (app "demo" als
 * template-instantie).
 *
 * De RabbitMQ-consumer, de HTTP-producer en de DLX-producer worden geadviced
 * naar inline mocks - hetzelfde mock-idee als de Karavan-dev-loop
 * (mock-legacy-http-target.camel.yaml), maar dan in de CI afdwingbaar.
 *
 * Bewijzen (keuzes uit VOORSTEL.md):
 *   1. 2xx     -> afgeleverd (bericht + correlatie-header bij de app)
 *   2. 5xx     -> 3 redeliveries (4 pogingen) -> error-route -> DLX + notify
 *   3. netwerkfout -> zelfde pad als 5xx
 *   4. 4xx     -> GEEN redelivery (1 poging), direct error-route -> DLX + notify
 */
@QuarkusTest
class EbmsHttpBridgeRouteTest extends CamelQuarkusTestSupport {

    static final AtomicReference<String> deliveredBody = new AtomicReference<>();
    static final AtomicReference<Object> deliveredCorrelationId = new AtomicReference<>();
    static final AtomicReference<String> deadLetteredBody = new AtomicReference<>();
    static final AtomicInteger attempts = new AtomicInteger();
    /** De Quarkus-app start 1x per testklasse; advice dus ook maar 1x. */
    static final AtomicBoolean advised = new AtomicBoolean(false);

    @Override
    public boolean isUseAdviceWith() {
        return true;
    }

    @BeforeEach
    void startRoutesMetMocks() throws Exception {
        CamelContext ctx = context();
        if (advised.compareAndSet(false, true)) {
            AdviceWith.adviceWith(ctx, "ebms-demo-delivery", a -> {
                a.replaceFromWith("direct:mock-source");
                // HTTP-producer -> inline mock-app:
                //   geen X-Test-Respond header        -> 2xx (afgeleverd)
                //   X-Test-Respond=network-error     -> connectiefout (redelivery)
                //   X-Test-Respond=500               -> 5xx (set header + throw, zoals de echte
//                                          EbmsDeliveryProcessor: redelivery)
                //   X-Test-Respond=400                -> 4xx (direct DLX, geen retry)
                a.weaveById("post-to-demo-app").replace().process(exchange -> {
                    attempts.incrementAndGet();
                    String respond = exchange.getIn().getHeader("X-Test-Respond", String.class);
                    if ("network-error".equals(respond)) {
                        throw new RuntimeException("simulated connection refused");
                    }
                    if ("500".equals(respond)) {
                        // 5xx = retryable: de echte EbmsDeliveryProcessor gooit IN de
                        // producentstap zodat DLC-redelivery de aanroep herhaalt.
                        exchange.getIn().setHeader("CamelHttpResponseCode", 500);
                        throw new RuntimeException("app reageerde 5xx (status=500) - retryable");
                    }
                    if (respond != null) {
                        exchange.getIn().setHeader("CamelHttpResponseCode",
                                Integer.valueOf(respond));
                    } else {
                        deliveredBody.set(exchange.getIn().getBody(String.class));
                        deliveredCorrelationId.set(exchange.getIn().getHeader("X-Correlation-Id"));
                    }
                });
            });
            AdviceWith.adviceWith(ctx, "ebms-demo-error-route", a ->
                a.weaveById("send-to-dlx-demo").replace().process(exchange ->
                    deadLetteredBody.set(exchange.getIn().getBody(String.class))));
            ctx.start();
        }
    }

    @Test
    void levertBerichtAfOpAppEndpointMetCorrelatie() {
        template().sendBody("direct:mock-source", "{\"ebms\":\"ok-1\"}");

        assertNotNull(deliveredBody.get(), "bericht moet bij de app-mock aankomen");
        assertEquals("{\"ebms\":\"ok-1\"}", deliveredBody.get());
        assertNotNull(deliveredCorrelationId.get(),
                "X-Correlation-Id moet gezet zijn vóór aflevering");
    }

    @Test
    void doetRedeliveriesBij5xxEnDeadLetteredDaarna() throws Exception {
        int pogingenVooraf = attempts.get();
        template().sendBodyAndHeader("direct:mock-source", "{\"ebms\":\"fail-5xx\"}",
                "X-Test-Respond", "500");

        assertTrue(wachtOpDeadLetter(), "bericht moet na redeliveries op de DLX-mock aankomen");
        assertEquals("{\"ebms\":\"fail-5xx\"}", deadLetteredBody.get());
        assertEquals(pogingenVooraf + 4, attempts.get(),
                "initial try + 3 redeliveries (maximumRedeliveries=3)");
    }

    @Test
    void doetRedeliveriesBijNetwerkfoutEnDeadLetteredDaarna() throws Exception {
        int pogingenVooraf = attempts.get();
        template().sendBodyAndHeader("direct:mock-source", "{\"ebms\":\"fail-net\"}",
                "X-Test-Respond", "network-error");

        assertTrue(wachtOpDeadLetter(), "bericht moet na redeliveries op de DLX-mock aankomen");
        assertEquals("{\"ebms\":\"fail-net\"}", deadLetteredBody.get());
        assertEquals(pogingenVooraf + 4, attempts.get(),
                "initial try + 3 redeliveries (maximumRedeliveries=3)");
    }

    @Test
    void stuurt4xxZonderRedeliveryDirectNaarDeadLetter() throws Exception {
        int pogingenVooraf = attempts.get();
        template().sendBodyAndHeader("direct:mock-source", "{\"ebms\":\"fail-4xx\"}",
                "X-Test-Respond", "400");

        assertTrue(wachtOpDeadLetter(), "bericht moet direct (zonder retry) op de DLX-mock aankomen");
        assertEquals("{\"ebms\":\"fail-4xx\"}", deadLetteredBody.get());
        assertEquals(pogingenVooraf + 1, attempts.get(),
                "4xx = client-fout: bewust GEEN redelivery, direct naar error-route");
    }

    @Test
    void schrijftNotificatieVoorBeheerNaarNotifySink() throws Exception {
        template().sendBodyAndHeader("direct:mock-source", "{\"ebms\":\"fail-4xx-notify\"}",
                "X-Test-Respond", "400");

        assertTrue(wachtOpDeadLetter(), "bericht moet op de DLX-mock aankomen");
        // Notify-sink is een echte file-component in de test (geen weave):
        // bewijst dat de beheer-notificatie daadwerkelijk weggeschreven wordt.
        Path notify = Path.of("target/ebms-notify-test/ebms-notify.log");
        assertTrue(Files.exists(notify), "notify-sink moet een tekstbestand schrijven");
        assertTrue(Files.readString(notify).contains("{\"ebms\":\"fail-4xx-notify\"}"),
                "gefaald bericht moet in de notify-tekst staan");
    }

    private boolean wachtOpDeadLetter() throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (System.currentTimeMillis() < deadline) {
            if (deadLetteredBody.get() != null) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }
}
