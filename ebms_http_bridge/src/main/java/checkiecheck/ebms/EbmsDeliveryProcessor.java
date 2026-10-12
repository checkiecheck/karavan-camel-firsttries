package checkiecheck.ebms;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;

/**
 * Levert één EBMS-bericht af op het HTTP-webhook-endpoint van een legacy-app.
 *
 * Belangrijk ontwerp (VOORSTEL.md + retry-semantiek): Camel-redelivery herstart
 * op het FAALPUNT, niet aan het begin van de route. Een retrybare fout (5xx of
 * netwerkfout) moet dus IN deze producentstap ontstaan, anders zou een
 * "throwException na de aanroep" alleen hetzelfde antwoord opnieuw gooien en
 * het endpoint nooit echt opnieuw aanroepen.
 *
 * Contract met de route:
 *   - route zet header X-EBMS-Target-Url (uit {{ebms.<app>.endpointUrl}})
 *   - 2xx: return, route logt event=delivered
 *   - 4xx: return met CamelHttpResponseCode op de exchange, route stuurt
 *     bewust ZONDER retry naar de error-route (client-fout = structureel)
 *   - 5xx / netwerkfout: throw -> DLC-redelivery herhaalt DEZE aanroep
 */
public class EbmsDeliveryProcessor implements Processor {

    public static final String TARGET_URL_HEADER = "X-EBMS-Target-Url";

    /** ProducerTemplate is thread-safe: 1x per context is genoeg. */
    private volatile ProducerTemplate template;

    @Override
    public void process(Exchange exchange) {
        String targetUrl = exchange.getIn().getHeader(TARGET_URL_HEADER, String.class);
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new IllegalStateException(
                    "Header " + TARGET_URL_HEADER + " ontbreekt; de route moet deze zetten");
        }
        ProducerTemplate t = template;
        if (t == null) {
            synchronized (this) {
                if (template == null) {
                    template = exchange.getContext().createProducerTemplate();
                }
                t = template;
            }
        }

        Exchange result = t.send("netty-http:" + targetUrl, inner -> {
            Message in = inner.getIn();
            in.setBody(exchange.getIn().getBody());
            in.setHeader(Exchange.CONTENT_TYPE,
                    exchange.getIn().getHeader(Exchange.CONTENT_TYPE, "application/json"));
            String correlationId = exchange.getIn().getHeader("X-Correlation-Id", String.class);
            if (correlationId != null) {
                in.setHeader("X-Correlation-Id", correlationId);
            }
        });

        Exception failure = result.getException();
        if (failure != null) {
            // netwerk-/connectiefout: retryable
            throw new RuntimeException("delivery naar " + targetUrl
                    + " faalde (netwerk): " + rootMessage(failure), failure);
        }

        Integer status = result.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
        if (status == null) {
            throw new RuntimeException("delivery naar " + targetUrl
                    + ": geen HTTP-status ontvangen");
        }
        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, status);

        if (status >= 500) {
            // 5xx = vermoedelijk tijdelijk: hier gooien zodat de DLC-redelivery
            // de aanroep ECHT herhaalt (redelivery herstart op dit faalpunt).
            throw new RuntimeException("app reageerde 5xx (status=" + status
                    + ") op " + targetUrl + " - retryable");
        }
        // 2xx en 4xx: normaal terugkeren; de route beslist (4xx -> error-route
        // zonder retry, 2xx -> delivered).
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : cur.getClass().getSimpleName();
    }
}
