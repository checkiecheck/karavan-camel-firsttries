package checkiecheck.ebms;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;

/**
 * Registreert de EbmsDeliveryProcessor in de Camel-registry voor de Quarkus
 * runtime (route-stap: process ref:ebmsDeliveryProcessor).
 *
 * In de JBang/Karavan-dev-loop gebeurt dat via @BindToRegistry in
 * rabbitmq-beans.java (geen CDI daar).
 */
@ApplicationScoped
public class EbmsDeliveryProducer {

    @Produces
    @ApplicationScoped
    @Named("ebmsDeliveryProcessor")
    EbmsDeliveryProcessor ebmsDeliveryProcessor() {
        return new EbmsDeliveryProcessor();
    }
}
