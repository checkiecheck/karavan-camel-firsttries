# rabbitmq_elastic

RabbitMQ â Elasticsearch worker: Apache Camel (Quarkus) die berichten van een
RabbitMQ-queue consumeert en in Elasticsearch indexeert â met structured JSON-logging (Filebeat-ready), parametrisering via env vars en een dead-letter-exchange als foutafhandeling.

## Architectuur

```
RabbitMQ (exchange: orders.events)
    |  routing key: order.#   queue: orders-to-elastic
    v
rabbitmq-elastic (Camel worker)
  - correlatie-id (exchangeId) als document-id
  - indexeert in {{es.index}}
  - ES faalt: 3 redeliveries -> error-route -> DLX (orders.events.dlx)
    v
Elasticsearch (index: orders)
```

## Bestanden

- `rabbitmq_elastic.camel.yaml` â Camel YAML DSL (Karavan-formaat): hoofdroute
  + error-route (dead letter channel, 3 redeliveries).
- `mock-elastic-target.camel.yaml` â DEV-ONLY mock Elasticsearch-endpoint
  (netty-http op :9200) voor de Karavan/JBang-loop. Wordt NIET in de container
  gebakken (zie Dockerfile).
- `src/test/java/checkiecheck/rabbitmq/RabbitmqElasticRouteTest.java` â
  CI-route-tests: dezelfde scenarios als hieronder handmatig, maar repeateerbaar in de build.
- `application.properties` â configuratie; alle waarden via env vars met safe defaults.
- `pom.xml` â Quarkus 3.15 + camel-quarkus-rabbitmq/elasticsearch-rest-client/core + junit5 (test).
- `Dockerfile` â multi-stage Maven build â eclipse-temurin JRE, poort 8080 (health only).
- `k8s/manifests.yaml` â ConfigMap, Secret-referentie, Deployment (probes, envFrom),
  Service. GEEN Ingress: messaging-worker.

## Dev-loop (Karavan / Camel JBang) â voor integratiespecialisten

Lokaal heb je alleen RabbitMQ nodig; Elasticsearch simuleer je met de mock.

1. **RabbitMQ starten** (Ã©C©nmalig):

   ```bash
   docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
   ```

2. **Route + mock starten** (in deze directory):

   ```bash
   ELASTICSEARCH_HOST=http://localhost:9200 camel run *.camel.yaml --dev
   ```

   `--dev` herlaadt automatisch bij opslaan in Karavan. De console toont live de
   structured events (`event=message-received`, `event=mock-es-request`, â¦).

3. **Scenarios naspelen** (dit zijn exact de CI-tests):

   ```bash
   # a) Bericht publiceren op de exchange -> route pikt op, mock indexeert
   docker exec rabbitmq rabbitmqadmin publish exchange=orders.events \
     routing_key=order.created payload='{"order":"123","bedrag":42.50}'
   #    console: event=message-received queue=orders-to-elastic
   #             event=mock-es-request uri=/orders/_doc/<exchangeId>
   #             event=message-indexed index=orders

   # b) ES "uitval" simuleren: mock stoppen (Ctrl-C niet nodig â tweede terminal)
   #    en opnieuw publiceren: 3 redeliveries (2s interval), dan DLX
   docker exec rabbitmq rabbitmqadmin publish exchange=orders.events \
     routing_key=order.created payload='{"order":"fail"}'
   #    console: event=message-failed level=ERROR ... errorMessage=...
   #             event=message-dead-lettered dlx=orders.events.dlx
   #    check: docker exec rabbitmq rabbitmqadmin list queues name messages
   ```

4. **Wijzigingen aan de route** zijn meteen zichtbaar: Karavan opslaan â
   herlaad â publiceer opnieuw.

## Tests (CI)

De build draait automatisc` RabbbitmqElasticRouteTest` (mvn test). De
RabbitMQ-consumer en ES-producer worden geadviced naar direct:-mocks, dus er
is geen broker of ES nodig in CI.

| Test | Bewijst |
|---|---|
| indexeertBerichtMetExchangeIdAlsDocumentId | bericht komt bij de ES-indexstap aan met exchangeId als document-id |
| stuurtMisluktBerichtNaRedeliveriesNaarDeadLetter | ES-fout -> 3 redeliveries -> originele bericht op de dead-letter |

Lokaal draien: `mvn test`. Faalt een test, reproduceer het scenario dan met
Het bijihorende `rabbitmqadmin publish`-commando uit de dev-loop hierboven.

## Belangrike env vars

| Var | Default | Betekenis |
|---|---|---|
| `RABBITMQ_HOST/PORT/VHOST` | localhost / 5672 / / | broker-adres |
| `RABBITMQ_EXCHANGE/QUEUE/ROUTING_KEY` | orders.events / orders-to-elastic / order.# | bron-binding |
| `RABBITMQ_DLX_EXCHANGE/DLX_ROUTING_KEY` | orders.events.dlx / dead | dead-letter bestemming |
| `RABBITMQ_USERNAME/PASSWORD` | guest / guest | credentials (prod: via Secret) |
| `ELASTICSEARCH_HOST` | http://localhost:9200 | ES-endpoint (prod: service DNS) |
| `ELASTICSEARCH_INDEX` | orders | doel-index |
| `ELASTICSEARCH_USERNAME/PASSWORD` | (leeg) | ES credentials (prod: via Secret) |
| `LOG_JSON` / `LOG_LEVEL` | true / INFO | structured logging (Filebeat-ready) |

## Contribueren aan deze integratie

1. Wijzig de route (`.camel.yaml`) of configuratie; test via de dev-loop hierboven.
2. Voeg een CI-test tod als je nieuw gedrag bouwt (testclass in `src/test/java`).
3. Commit + push naar `main`: de monorepo-workflow draait de dependency-check
   (JBang â pom) en de route-tests, bouwt de image
   (`ghcr.io/<owner>/<repo>/rabbitmq_elastic:<sha>`) en deployt naar het
   test-cluster (manifesten uit `k8s/`).
4. Zie Actions-tab voor de run; faalt een test dan draai hem lokaal na (`mvn test`).
