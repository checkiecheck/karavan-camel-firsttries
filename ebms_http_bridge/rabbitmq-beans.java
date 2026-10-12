// DEV-ONLY (Camel JBang / Karavan dev-loop): de spring-rabbitmq-component vereist
// een ConnectionFactory-bean in de registry (patroon 15). In de Quarkus-container
// verzorgt src/main/java/.../RabbitMqConnectionFactoryProducer.java dit (CDI);
// in de JBang-loop gebeurt dat hier via @BindToRegistry.
//
// Gebruik: camel run ebms_http_bridge.camel.yaml rabbitmq-beans.java EbmsDeliveryProcessor.java mock-legacy-http-target.camel.yaml --dev
public class RabbitMqBeans {

    @org.apache.camel.BindToRegistry
    public org.springframework.amqp.rabbit.connection.CachingConnectionFactory rabbitMQConnectionFactory() {
        com.rabbitmq.client.ConnectionFactory cf = new com.rabbitmq.client.ConnectionFactory();
        cf.setHost(System.getenv().getOrDefault("RABBITMQ_HOST", "localhost"));
        cf.setPort(Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", "5672")));
        cf.setUsername(System.getenv().getOrDefault("RABBITMQ_USERNAME", "guest"));
        cf.setPassword(System.getenv().getOrDefault("RABBITMQ_PASSWORD", "guest"));
        cf.setVirtualHost(System.getenv().getOrDefault("RABBITMQ_VHOST", "/"));
        return new org.springframework.amqp.rabbit.connection.CachingConnectionFactory(cf);
    }


    // DEV-ONLY: registreert de delivery-processor voor de route-stap
    // `process ref:ebmsDeliveryProcessor` (in Quarkus doet
    // src/main/java/.../EbmsDeliveryProducer.java dit via CDI).
    @org.apache.camel.BindToRegistry("ebmsDeliveryProcessor")
    public checkiecheck.ebms.EbmsDeliveryProcessor ebmsDeliveryProcessor() {
        return new checkiecheck.ebms.EbmsDeliveryProcessor();
    }
}
