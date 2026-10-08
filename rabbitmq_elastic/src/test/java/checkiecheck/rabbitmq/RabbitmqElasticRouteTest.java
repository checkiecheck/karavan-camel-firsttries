package checkiecheck.rabbitmq;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.quarkus.test.junit.QuarkusTest;
import org.apache.camel.CamelContext;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.quarkus.test.CamelQuarkusTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Route-tests voor de RabbitMQ -> Elasticsearch-integratie.
 *
 * De RabbitMQ-consumer en de Elasticsearch-producer worden geadviced naar
 * direct: endpoints â hetzelfde mock-idee als de Karavan-dev-loop
 * (mock-elastic-target.camel.yaml), maar dan in de CI afdwingbaar.
 *
 * Scenarios die de specialist in Karavan handmatig kan naspelen (bericht
 * publiceren in de queue / ES stoppen), staan hier als repeateerbare bewijzen:
 *   1. bericht binnen    -> geÃ¯ndexeerd met exchangeId als document-id
 *   2. ES faalt (na 3 redeliveries) -> error-route -> dead-letter exchange
 */
@QuarkusTest
class RabbitmqElasticRouteTest extends CamelQuarkusTestSupport {

    static final AtomicReference<String> indexedBody = new AtomicReference<>();
    static final AtomicReference<Object> indexedDocId = new AtomicReference<>();
    static final AtomicReference<String> deadLetteredBody = new AtomicReference<>();

    /** adviceWith vÃ³Ã³r de start: consumer en producers vervangen door mocks */
    @Override
    public boolean isUseAdviceWith() {
        return true;
    }

    /** Mock-target met inbouw: onthoud wat er doorgestuurd werd */
    @Override
    protected RoutesBuilder createTestCamelContextRoutes() {
        return new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:mock-es-index")
                    .id("mock-es-index")
                    .process(exchange -> {
                        if (Boolean.TRUE.equals(exchange.getIn().getHeader("X-Test-Force-Failure", Boolean.class))) {
                            throw new RuntimeException("simulated ES outage");
                        }
                        indexedBody.set(exchange.getIn().getBody(String.class));
                        indexedDocId.set(exchange.getIn().getHeader("ElasticsearchDocumentId"));
                    });

                from("direct:mock-dlx")
                    .id("mock-dlx")
                    .process(exchange ->
                        deadLetteredBody.set(exchange.getIn().getBody(String.class)));
            }
        };
    }

    @BeforeEach
    void startRoutesMetMocks() throws Exception {
        CamelContext ctx = context();
        Advice.with(ctx, "rabbitmq-to-elastic", a -> {
            a.replaceFromWith("direct:mock-source");
            a.weaveById("index-in-elastic").replace().to("direct:mock-es-index");
        });
        Advice.with(ctx, "rabbitmq-error-route", a ->
            a.weaveById("send-to-dlx").replace().to("direct:mock-dlx"));
        ctx.start();
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
