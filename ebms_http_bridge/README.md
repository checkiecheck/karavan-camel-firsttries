successfully downloaded text file (SHA: d29313e29590098a54fb00e00e1ff6af5d22a575)
# ebms_http_bridge

EBMS → legacy-apps webhook-bridge: Apache Camel (Quarkus) die EBMS-berichten uit
RabbitMQ per vragende app aflevert op een HTTP-endpoint (webhook-idee). Oude
applicaties die alleen HTTP kunnen, krijgen zo berichten van de EBMS-adapter
zonder zelf met RabbitMQ te hoeven praten. Zie `VOORSTEL.md` voor de bewuste
keuzes (route-paar per app, 4xx = geen retry, notify-sink).

## Architectuur

```
EBMS-adapter -> RabbitMQ (exchange: ebms.events)
                  |  routing key: ebms.demo.*   queue: ebms.demo.out (per app)
                  v
              ebms-http-bridge (Camel worker)
                - per app een route-paar:
                    delivery:  queue -> EbmsDeliveryProcessor POST naar
                               {{ebms.<app>.endpointUrl}}
                               2xx   -> delivered (log)
                               4xx   -> direct naar error-route (GEEN retry)
                               5xx   -> redelivery (3x, 2s interval, ECHTE herhaalaanroep)
                    error:     log ERROR -> notify-sink (beheer-melding)
                               -> DLX ({{ebms.<app>.dlx.exchange}})
                  v
legacy-app HTTP-endpoint (POST, header X-Correlation-Id)
```

De ingesteweg (HTTP → RabbitMQ) loopt via de bestaande Kong-plugin en zit hier
bewust NIET in (VOORSTEL.md, scope-keuze).

## Bestanden

- `ebms_http_bridge.camel.yaml` — Camel YAML DSL (Karavan-formaat): per app een
  delivery- + error-route (hier app "demo" als eerste instantie van het
  template), plus een gedeelde notify-sink. De RabbitMQ-component is
  **spring-rabbitmq** (patroon 15): op Quarkus bestaat géén
  `camel-quarkus-rabbitmq`. Connectiegegevens staan in de
  `CachingConnectionFactory`-bean, niet in de endpoint-URI.
- `src/main/java/checkiecheck/ebms/EbmsDeliveryProcessor.java` — doet de
  HTTP-POST naar de legacy-app. Bewust een processor i.p.v. een
  `to: netty-http` + throwException ná de aanroep: **Camel-redelivery herstart op het
  faalpunt, niet aan het begin van de route.** Alleen als de retrybare fout
  (5xx/netwerk) IN de producentstap ontstaat, wordt het endpoint bij elke
  retry echt opnieuw aangeroepen. 4xx keert normaal terug (geen retry,
  route stuurt direct naar de error-route).
- `mock-legacy-http-target.camel.yaml` — DEV-ONLY mock van een legacy-app
  (netty-http op :18081); response-code instelbaar via `?status=400|500`.
  Wordt NIET in de container gebakken (zie Dockerfile).
- `src/test/java/checkiecheck/ebms/EbmsHttpBridgeRouteTest.java` — CI-route-tests
  (zelfde scenarios als de dev-loop, repeateerbaar in de build).
- `application.properties` — configuratie; per app een property-blok
  (`ebms.<app>.*`), alle waarden via env vars met safe defaults.
- `src/main/java/checkiecheck/ebms/EbmsDeliveryProducer.java` — CDI-registratie
  van de delivery-processor in de Camel-registry (Quarkus-runtime).
- `src/main/java/checkiecheck/ebms/RabbitMqConnectionFactoryProducer.java` —
  CDI-producer van de `CachingConnectionFactory` (spring-rabbitmq verplicht
  zo'n bean in de registry).
- `rabbitmq-beans.java` — DEV-ONLY: zelfde ConnectionFactory voor de
  JBang/Karavan-loop.
- `pom.xml` — Quarkus 3.15 + camel-quarkus-spring-rabbitmq/netty-http/file/
  yaml-dsl/core + junit5 (test). `camel-quarkus-yaml-dsl` is verplicht: zonder
  loader wordt `*.camel.yaml` niet geladen.
- `Dockerfile` — multi-stage Maven build → eclipse-temurin JRE, poort 8080
  (health only). Route + properties worden expliciet gecopieerd.
- `k8s/manifests.yaml` — ConfigMap, Secret-referentie, Deployment (probes,
  envFrom, emptyDir voor notify-bestanden), Service. GEEN Ingress:
  messaging-worker.

## App toevoegen (APP-REGISTRY)

1. Kopieer het route-paar `ebms-demo-*` in `ebms_http_bridge.camel.yaml` en
   vervang `demo` door de nieuwe app-naam (route-ids, headers, placeholders).
2. Voeg een `ebms.<app>.*`-blok toe in `application.properties`.
3. Voeg de entries toe in de ConfigMap in `k8s/manifests.yaml`.
4. Declareer de queue + binding op de exchange in RabbitMQ (de route declareert
   zelf niets: `autoDeclare: false`).
5. Voeg evt. een testklasse toe (kopieer `EbmsHttpBridgeRouteTest`) en draai
   `mvn test`.

## Dev-loop (Karavan / Camel JBang)

Lokaal heb je alleen RabbitMQ nodig; de legacy-app simuleer je met de mock.

1. **RabbitMQ starten** (éénmalig):

   ```bash
   docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:3-management
   ```

2. **Queue + binding declareren**:

   ```bash
   docker exec rabbitmq rabbitmqadmin declare exchange name=ebms.events type=topic
   docker exec rabbitmq rabbitmqadmin declare queue name=ebms.demo.out
   docker exec rabbitmq rabbitmqadmin declare binding source=ebms.events \
     destination=ebms.demo.out routing_key="ebms.demo.#"
   docker exec rabbitmq rabbitmqadmin declare exchange name=ebms.demo.dlx type=topic
   ```

3. **Route + mock starten** (in deze directory):

   ```bash
   camel run ebms_http_bridge.camel.yaml rabbitmq-beans.java \
     EbmsDeliveryProcessor.java mock-legacy-http-target.camel.yaml --dev
   ```

   `rabbitmq-beans.java` is nodig omdat ook in JBang de spring-rabbitmq-component
   een ConnectionFactory-bean vereist, en registreert de delivery-processor
   (de Quarkus-container gebruikt de CDI-producer). `--dev` herlaadt
   automatisch bij opslaan in Karavan.

4. **Scenarios naspelen** (dit zijn exact de CI-tests):

   ```bash
   # a) Bericht publiceren -> route levert af op de mock (2xx)
   docker exec rabbitmq rabbitmqadmin publish exchange=ebms.events \
     routing_key=ebms.demo.created payload='{"ebms":"bericht-123"}'
   #    console: event=message-received app=demo ...
   #             event=delivered app=demo status=200 ...

   # b) 5xx simuleren: payload met ?status=500 als endpoint (of mock aanpassen),
   #    opnieuw publiceren: 3 redeliveries (2s interval), dan error-route -> DLX
   #    console: event=delivery-failed ... reason=app demo responded 5xx
   #             event=dead-lettered app=demo dlx=ebms.demo.dlx
   #    check: docker exec rabbitmq rabbitmqadmin list queues name messages

   # c) 4xx simuleren: status=400 -> GEEN retry, direct naar DLX
   #    console: event=delivery-client-rejected ... reason=no-retry-4xx
   ```

   Beheer-meldingen komen in het notify-bestand (`/tmp/ebms-notify/ebms-notify.log`).

## Tests (CI)

De build draait automatisch `EbmsHttpBridgeRouteTest` (mvn test). De
RabbitMQ-consumer en HTTP-producer worden geadviced naar inline mocks, dus er
is geen broker of endpoint nodig in CI.

| Test | Bewijst |
|---|---|
| levertBerichtAfOpAppEndpointMetCorrelatie | bericht komt bij de HTTP-stap aan met X-Correlation-Id (exchangeId-fallback) |
| meegegevenCorrelatieIdWintVanExchangeIdFallback | een meegegeven X-Correlation-Id (van de ebms-adapter) blijft behouden i.p.v. overschreven te worden |
| doetRedeliveriesBij5xxEnDeadLetteredDaarna | 5xx -> 3 redeliveries (elke retry herhaalt de aanroep) -> DLX |
| doetRedeliveriesBijNetwerkfoutEnDeadLetteredDaarna | connectiefout -> 3 redeliveries -> DLX |
| stuurt4xxZonderRedeliveryDirectNaarDeadLetter | 4xx -> 1 poging -> DLX (geen redelivery) |
| deadLetterBehoudtCorrelatieIdVoorTraceerbaarheid | de dead-letter behoudt de X-Correlation-Id (DLX-bericht is traceerbaar) |
| schrijftNotificatieVoorBeheerNaarNotifySink | falende aflevering komt in het notify-bestand voor beheer |

Lokaal draaien: `mvn test`. Faalt een test, reproduceer het scenario dan met het
bijbehorende `rabbitmqadmin publish`-commando uit de dev-loop hierboven.

## Belangrijke env vars

| Var | Default | Betekenis |
|---|---|---|
| `RABBITMQ_HOST/PORT/VHOST` | localhost / 5672 / / | broker-adres |
| `RABBITMQ_USERNAME/PASSWORD` | guest / guest | credentials (prod: via Secret) |
| `EBMS_NOTIFY_FILE_URI` | file:/tmp/ebms-notify?... | notify-sink bestemming (prod: emptyDir /var/ebms/notify) |
| `EBMS_DEMO_EXCHANGE/QUEUE` | ebms.events / ebms.demo.out | bron-binding app demo |
| `EBMS_DEMO_ENDPOINT_URL` | http://localhost:18081/api/ebms | webhook-endpoint app demo (prod: service DNS) |
| `EBMS_DEMO_DLX_EXCHANGE/DLX_ROUTING_KEY` | ebms.demo.dlx / dead | dead-letter bestemming |
| `EBMS_DEMO_PREFETCH_COUNT/CONSUMERS` | 1 / 1 | consumer-instellingen |
| `LOG_JSON` / `LOG_LEVEL` | true / INFO | structured logging (Filebeat-ready) |

## Contribueren aan deze integratie

1. Wijzig de route (`.camel.yaml`) of configuratie; test via de dev-loop hierboven.
2. Voeg een CI-test toe als je nieuw gedrag bouwt (testclass in `src/test/java`).
3. Commit + push naar `main`: de monorepo-workflow draait de dependency-check
   (JBang ↔ pom) en de route-tests, bouwt de image en (bij een self-hosted
   runner) de deploy. Nieuwe app = APP-REGISTRY-stappen hierboven.
