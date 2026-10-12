# ebms_http_bridge — voorstel (fase 0, nog geen code)

## Context

De EBMS-adapter is RabbitMQ-gebaseerd: verwerkte berichten komen op RabbitMQ terecht.
Legacy-apps kunnen geen AMQP praten, alleen HTTP. Deze integratie is de **brug tussen
RabbitMQ en HTTP** voor die apps.

Twee benen:

1. **Ingest (HTTP → RabbitMQ):** legacy-app POST een bericht dat in RabbitMQ belandt.
   Kan al via een Kong-plugin — open punt of dit ook een Camel-route wordt (keuze 1).
2. **Terugweg (RabbitMQ → HTTP, "webhook-gedachte"):** per vragende app een eigen
   queue; de bridge levert berichten af op het HTTP-endpoint van die app.

Dit document bevat het voorstel en de bewuste keuzes die we MOETEN maken vóór we
bouwen. Pas als die vastliggen: routes, tests, Dockerfile, k8s en README (patronen 1-15).

## Architectuur-voorstel (terugweg)

```
EBMS-adapter ──publish──▶ topic exchange "ebms.events"
                             │ routing key: ebms.<app>.<eventtype>
                             ▼
                 queue "ebms.<app>.out"   (per app, binding via routing key)
                             │
                             ▼
                 ebms_http_bridge: consume per app
                             │  POST JSON + X-Correlation-Id header
                             ▼
                 HTTP-endpoint van de legacy-app (webhook)
                             │
              2xx = ACK      │      faalt → errorHandler (patroon 3):
                             │      3 redeliveries → error-route → DLX "ebms.<app>.dlx"
```

- RabbitMQ-component: **spring-rabbitmq** (patroon 15: geen camel-quarkus-rabbitmq,
  CachingConnectionFactory-bean via CDI-producer, camel-quarkus-yaml-dsl verplicht).
- Bericht blijft ongewijzigd (body, content-type) bij aflevering; de bridge voegt alleen
  correlatie-headers toe.
- Structured logging per event: `event=delivered app=<app> correlationId=...`,
  `event=delivery-failed`, `event=dead-lettered` (patroon 4).
- Per app een eigen DLX + DLQ, zodat één haperende app de andere niet vult.
- Later eventueel: DLQ-drain/replay-commando voor de dev-loop.

## Keuzes die we nu moeten maken

### 1. Scope van deze integratie
- **a)** Alleen terugweg (Rabbit→HTTP); ingest blijft bij de Kong-plugin.
- **b)** Ook ingest als Camel-route (netty-http consumer → spring-rabbitmq producer),
  zodat beide benen één integratie met één README/dev-loop zijn.
- Overweging: (a) is kleiner; (b) geeft symmetrie en CI-baar bewijs voor beide benen,
  maar verdubbelt het route/test-werk.

### 2. Route-model voor per-app afhandeling (belangrijkste keuze)
- **a) Route per app (template-copy):** voor elke app een route `ebms-<app>-delivery`
  + `ebms-<app>-error-route`, elk met eigen queue, endpoint-URL, retry-policy en DLX.
  - Pro: 1-op-1 met patronen 1/3/14; per-app retry/timeout/DLQ; volledig
    Karavan-renderbaar en voor de specialist leesbaar/editeerbaar per app.
  - Contra: duplicatie; nieuwe app = nieuwe routes (maar: PR = auditlog, patroon 8-stijl).
- **b) Eén generieke route + app-registry:** consumeert alle `ebms.*.out`-queues,
  bepaalt doel-URL via header/properties (recipientList of dynamische `to`), config
  per app in properties.
  - Pro: één route; nieuwe app = alleen config.
  - Contra: per-app retry-policy moeilijker; minder zichtbaar in Karavan; fouten van
    app A kunnen de route voor app B raken; error-handling generiek i.p.v. per app.
- **c) Camel route templates:** geparametriseerde template + per app een instantie.
  Elegant, maar Karavan-support voor route templates is onbewezen — risico tegen het
  patroon-doel "moet in Karavan renderen".
- **Voorstel:** (a) bij een klein, bekend aantal apps (zeg ≤ 5-6); heroverweeg (b)/(c)
  pas bij groei of frequente app-toevoegingen.

### 3. Ack/retry-semantiek per aflevering
- 2xx → ACK, klaar.
- 5xx/netwerkfout/timeout → redeliveries (patroon 3: bv. 3x met delay) → daarna DLX.
- **4xx → bewuste keuze:** direct naar DLX (client-fout, retry heeft geen zin — wel
  loggen met reason) óf toch redeliveren (als een app tijdelijk 400 geeft).
- Timeouts per app configureerbaar (ook korte timeouts voor trage legacy's).
- Vraag: lineaire delay (patroon 3) of exponential backoff?

### 4. App-registratie & configuratie
- **a)** Per app een blok in `application.properties` (`${ENV:default}`, patroon 2):
  `ebms.<app>.endpoint-url`, `ebms.<app>.queue`, retry, timeout.
- **b)** ConfigMap in `k8s/manifests.yaml` als bron (repo-PR = auditlog van toelating,
  patroon 8-stijl) met envFrom; Secrets (API-keys) apart.
- Voorstel: (a) voor waarden die de route(s) letterlijk gebruiken + (b) voor
  runtime-only zaken; auth-geheimen altijd via Secret/env var.

### 5. Authenticatie richting legacy-apps
- Geen / static API-key of bearer-token header (uit Secret) / mTLS — en zo ja, waar
  termineren we dat (onze pod of een Kong vóór de legacy-apps)?

### 6. Response-afhandeling
- **a) Fire-and-forget:** alleen statuscode telt (2xx = goed), body negeren.
- **b) Response-body opvangen:** naar `ebms.<app>.reply` queue of loggen als event.
- Voorstel: (a) tenzij de EBMS-flow expliciet antwoorden terugverwacht.

### 7. Correlatie & idempotentie
- `X-Correlation-Id` (exchangeId-patroon uit rabbitmq_elastic) meesturen; optioneel
  een `X-Delivery-Id` zodat de app duplicaten kan herkennen (at-least-once!).

### 8. Volgorde vs. doorvoer
- Per app 1 consumer (streng FIFO, past bij document-achtige EBMS-berichten) of
  prefetch/concurrent (doorvoer, maar volgorde niet gegarandeerd)?
- Voorstel: default 1 consumer per app, per app configureerbaar.

## CI/impact
- Deze map heeft nog GEEN `application.properties` → de changed-detection van de
  workflow pakt hem niet op; er draait dus niets in CI. Pas bij de bouwfase toevoegen
  (samen met pom.xml, routes, tests, Dockerfile, k8s, README volgens patroon 14).
- Naamgeving: k8s-namen met hyphens (`ebms-http-bridge`), queue-namen met punten
  (`ebms.<app>.out`), mapnaam met underscore conform repo-conventie.

---

## Genomen beslissingen (na review, vastgesteld vóór bouw)

| Keuze | Beslissing | Toelichting |
|---|---|---|
| 1. Scope | Alleen de terugweg (Rabbit → HTTP-webhook) | Ingest blijft bij de bestaande Kong-plugin; de terugweg heeft geen eigen ingest-orchestration nodig. |
| 2. Route-model | **2a: route-paar per app** (delivery + error) | Per-app extra logica (auth, mapping, retry-policy) mogelijk zonder andere apps te raken; bij 4–6 apps goed beheerbaar. |
| 3. 4xx-afhandeling | Direct naar DLX, géén redelivery + notificatie naar beheer | 4xx = structureel (bericht/app zal niet lukken); retry verspelt alleen. Notify-sink is MVP: tekstbestand, later echte notificatie. |
| 4. Aantal apps | 4–6 apps | Route-per-app blijft houdbaar; template-benadering (APP-REGISTRY) maakt toevoegen een kopieerstap. |

Overige punten (5–8) zijn geïmplementeerd zoals in de voorstellen: fire-and-forget
op statuscode, `X-Correlation-Id` = exchangeId, per app default 1 consumer
(configureerbaar), endpoint-config via properties + ConfigMap (envFrom), secrets
via Secret/env var.

### Retry-semantiek (bepaald tijdens de bouw)

Camel-redelivery herstart op het **faalpunt**, niet aan het begin van de route
([Camel-gedrag, zie ook](https://stackoverflow.com/questions/22719023/camel-message-redelivery-not-behaving-as-expected)).
Daarom zit de HTTP-aanroep in een `EbmsDeliveryProcessor` (producer-stap): een
retrybare fout (5xx of netwerkfout) ontstaat ín de producentstap, zodat elke
redelivery het legacy-endpoint écht opnieuw aanroept. Een `to: http` +
`throwException` ná de aanroep zou dezelfde response opnieuw gooien zonder
het endpoint ooit opnieuw te raken (schijn-retry). 4xx keert normaal terug uit
de processor; de route stuurt het bericht bewust zonder retry naar de
error-route (→ notify + DLX).
