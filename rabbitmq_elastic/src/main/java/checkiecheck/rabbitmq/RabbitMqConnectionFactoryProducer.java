package checkiecheck.rabbitmq;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;

/**
 * Levert de Spring AMQP ConnectionFactory die de camel-spring-rabbitmq
 * component verwacht. De component heeft geen host/user/password-opties
 * in de endpoint-URI: connectiegegevens leven hier, gevoed vanuit
 * application.properties / environment variables (patroon 2).
 *
 * Als enige CachingConnectionFactory in de registry wordt deze bean
 * automatisch door de component autowired.
 */
@ApplicationScoped
public class RabbitMqConnectionFactoryProducer {

    @Produces
    @ApplicationScoped
    CachingConnectionFactory cachingConnectionFactory(
            @ConfigProperty(name = "rabbitmq.host") String host,
            @ConfigProperty(name = "rabbitmq.port") Integer port,
            @ConfigProperty(name = "rabbitmq.username") String username,
            @ConfigProperty(name = "rabbitmq.password") String password,
            @ConfigProperty(name = "rabbitmq.vhost") String vhost) {
        com.rabbitmq.client.ConnectionFactory rabbitFactory = new com.rabbitmq.client.ConnectionFactory();
        rabbitFactory.setHost(host);
        rabbitFactory.setPort(port);
        rabbitFactory.setUsername(username);
        rabbitFactory.setPassword(password);
        rabbitFactory.setVirtualHost(vhost);
        return new CachingConnectionFactory(rabbitFactory);
    }
}
