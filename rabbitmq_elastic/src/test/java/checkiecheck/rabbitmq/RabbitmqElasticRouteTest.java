package checkiecheck.rabbitmq;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.quarkus.test.junit.QuarkusTest;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.quarkus.test.CamelQuarkusTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Route-tests voor de RabbitMQ -> Elasticsearch-integratie.
 *
 * De RabbitMQ-consumer en de Elasticsearch-producer worden geadviced:
 * de consumer wordt een direct: source en de producers worden vervangen
 * door inline processors — hetzelfde mock-idee als de Karavan-dev-loop
 * (mock-elastic-target.camel.yaml), maar dan in de CI afdwingbaar.
 * Inline (i.p.v. aparte direct:-mockroutes) is bewust: exceptions uit
 * een aparte mockroute worden door déé default error-handler van die
 * route afgehandeld en bereiken de error-handler van de hoofdroute niet.
 *
 * Scenarios die de specialist in Karavan handmatig kan naspelen (bericht
 * publiceren in de queue / ES stoppen), staan hier als repeateerbare bewijzen:
 *   1. bericht binnen    -> geïndexeerd met exchangeId als document-id
 *   2. ES faalt (na 3 redeliveries) -> error-route -> dead-letter exchange
 */
@QuarkusTest
class RabbitmqElasticRouteTest extends CamelQuarkusTestSupport {

    static final AtomicReference<String> indexedBody = new AtomicReference<>();
    static final AtomicReference<Object> indexedDocId = new AtomicReference<>();
    static final AtomicReference<String> deadLetteredBody = new AtomicReference<>();
    /** De Quarkus-app start 1x per testklasse; advice dus ook maar 1x. */
    static final AtomicBoolean advised = new AtomicBoolean(false);

    /** adviceWith vóór de start: consumer en producers vervangen door mocks */
    @Override
    public boolean isUseAdviceWith() {
        return true;
    }

    @BeforeEach
    void startRoutesMetMocks() throws Exception {
        CamelContext ctx = context();
        if (advised.compareAndSet(false, true)) {
            AdviceWith.adviceWith(ctx, "rabbitmq-to-elastic", a -> {
                a.replaceFromWith("direct:mock-source");
                // ES-producer -> inline mock: onthoud wat geïndexeerd werd,
                // of simuleer een ES-outage via de X-Test-Force-Failure header
                a.weaveById("index-in-elastic").replace().process(exchange -> {
                    if (Boolean.TRUE.equals(exchange.getIn().getHeader("X-Test-Force-Failure", Boolean.class))) {
                        throw new RuntimeException("simulated ES outage");
                    }
                    indexedBody.set(exchange.getIn().getBody(String.class));
                    indexedDocId.set(exchange.getIn().getHeader("ElasticsearchDocumentId"));
                });
            });
            AdviceWith.adviceWith(ctx, "rabbitmq-error-route", a ->
                a.weaveById("send-to-dlx").replace().process(exchange ->
                    deadLetteredBody.set(exchange.getIn().getBody(String.class))));
            ctx.start();
        }
    }

    @Test
    void indexeertBerichtMetExchangeIdAlsDocumentId() {
        template().sendBody("direct:mock-source", "{\"order\":\"123\"}");

        assertNotNull(indexedBody.get(), "bericht moet bij de ES-mock aankomen");
        assertEquals("{\"order\":\"123\"}", indexedBody.get());
        assertNotNull(indexedDocId.get(), "ElasticsearchDocumentId moet gezet zijn (exchangeId)");
    }

    @Test
    void stuurtMisluktBerichtNaRedeliveriesNaarDeadLetter() throws Exception {
        template().sendBodyAndHeader("direct:mock-source", "{\"order\":\"fail\"}",
            "X-Test-Force-Failure", true);

        // errorHandler: 3 redeliveries x 2000ms delay, daarna error-route -> DLX
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
        while (deadLetteredBody.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
        }
        assertEquals("{\"order\":\"fail\"}", deadLetteredBody.get(),
            "originele bericht moet na redeliveries op de dead-letter aankomen");
    }
}
