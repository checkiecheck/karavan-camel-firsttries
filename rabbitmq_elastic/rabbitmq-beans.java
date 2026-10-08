// DEV-ONLY (Camel JBang / Karavan dev-loop): de spring-rabbitmq-component vereist
// een ConnectionFactory-bean in de registry. In de Quarkus-container verzorgt
// src/main/java/.../RabbitMqConnectionFactoryProducer.java dit (CDI-producer);
// in de JBang-loop gebeurt dat hier via @BindToRegistry.
//
// Gebruik: camel run rabbitmq_elastic.camel.yaml rabbitmq-beans.java mock-elastic-target.camel.yaml --dev
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
}
