# ieee2030_proxy

IEEE 2030.5 (SEP2) doorgeefluik: Apache Camel (Quarkus) reverse proxy die de
volledige berichtstroom tussen SEP2-clients en de SEP2-backend logt (structured
JSON) en voorzien van authenticatie-autorisatie op client-certificaat (CN).

## Architectuur

```
SEP2-client --mTLS--> Kong Ingress (cert-validatie, CN -> X-Client-CN)
                          |
                          v  HTTP + X-Client-CN
                    ieee2030-proxy (Camel, poort 8080)
                      - geen CN-header  -> 403 (reason=missing-client-cn)
                      - CN niet in allowlist -> 403 (reason=cn-not-in-allowlist)
                      - anders: log + doorsturen naar SEP2-backend
                          |
                          v  HTTPS
                    SEP2-backend (target)
```

## Bestanden

- `ieee2030-proxy.camel.yaml` — Camel YAML DSL (Karavan-formaat): proxy-route +
  error-route (dead letter channel, 3 redeliveries).
- `mock-sep2-target.camel.yaml` — DEV-ONLY mock SEP2-backend voor de
  Karavan/JBang-loop. Wordt NIET in de container gebakken (zie Dockerfile).
- `src/test/java/.../Ieee2030ProxyRouteTest.java` — CI-route-tests: dezelfde
  scenarios als hieronder handmatig, maar dan repeateerbaar in de build.
- `application.properties` — configuratie; alle waarden via env vars met
  safe defaults.
- `pom.xml` — Quarkus 3.15 + camel-quarkus-netty-http/core + junit5 (test).
- `Dockerfile` — multi-stage Maven build -> eclipse-temurin JRE, poort 8080.
  `Dockerfile.native` — Mandrel-variant (koude start ~ms, laag geheugen),
  langere buildtijd; bewuste keuze, geen default.
- `k8s/manifests.yaml` — ConfigMap, Deployment (probes, envFrom), Service,
  KongPlugins (anti-spoof clear_header + CN-header) en Ingress
  (`strip-path: false` zodat SEP2-paden intact blijven).
- `kong.conf` — referentie voor de Kong Ingress zelf: mTLS op Nginx-niveau
  (`nginx_proxy_ssl_verify_client = optional`) — niet actief in de app-repo,
  maar bij de Kong-configuratie te gebruiken.

## Dev-loop (Karavan / Camel JBang) — voor integratiespecialisten

De route ontwikkelen en testen doe je lokaal met Karavan en een mock-target;
geen cluster nodig, geen container-build.

1. **Route openen in Karavan** en bewerken; opslaan = hot reload.

2. **Proxy + mock-backend starten** (twee terminals of één commando):

   ```bash
   # in deze directory — start proxy + mock in dev-modus:
   IEEE2030_TARGET_BASE=http://localhost:9443 \
   IEEE2030_TARGET_HOST=localhost \
   IEEE2030_TARGET_PORT=9443 \
   IEEE2030_TARGET_TLS=false \
   camel run *.camel.yaml --dev
   ```

   `--dev` herlaadt de routes automatisch bij opslaan. De console toont
   live de structured events van beide routes.

3. **Scenarios naspelen** (dit zijn exact de CI-tests):

   ```bash
   # a) Geen client-certificaat (Kong uitgeschakeld lokaal = geen CN-header)
   curl -i http://localhost:8080/dcap
   #    -> 403 {"error":"client certificate required"}
   #    console: event=ieee2030-auth-rejected reason=missing-client-cn

   # b) CN niet toegelaten (allowlist via env var invullen om dit te testen)
   IEEE2030_CN_ALLOWLIST=device-a camel run *.camel.yaml --dev
   curl -i -H "X-Client-CN: onbekend-device" http://localhost:8080/dcap
   #    -> 403 {"error":"client not authorized"}
   #    console: event=ieee2030-auth-rejected reason=cn-not-in-allowlist

   # c) Toegestane client: doorgifte naar mock-target
   curl -i -H "X-Client-CN: device-a" http://localhost:8080/dcap
   #    -> 200 met mock-response body
   #    console: event=ieee2030-request ... clientCN=device-a
   #             event=mock-sep2-request path=/dcap
   #             event=ieee2030-response-ok status=200

   # d) POST met body (SEP2-resources zoals /der, /tm gebruiken POST)
   curl -i -X POST -H "X-Client-CN: device-a" \
     -H "Content-Type: application/vnd.vector_load" \
     -d '{"mrid":"test123"}' http://localhost:8080/dcap
   #    mock-log laat zien dat de body ongewijzigd aankomt
   ```

4. **Wijzigingen aan de route** zijn meteen zichtbaar: Karavan opslaan →
   herlaad → curl opnieuw. Foutmeldingen (bv. `reason=missing-client-cn`)
   wijzen direct naar de oorzaak.

Tip: wil je de echte CN-afhandeling van Kong simuleren? Zet de header handmatig
mee zoals in b/c — Kong doet in productie exact hetzelfde (na anti-spoof
clearing).

## Tests (CI)

De build draait automatisch `Ieee2030ProxyRouteTest` (mvn package):

| Test | Bewijst |
|---|---|
| weigertZonderClientCNHeader | 403 + juiste error-body bij ontbrekende CN |
| weigertMetNietToegelatenCN | allowlist werkt (403 bij onbekende CN) |
| stuurtDoorMetToegelatenCN | doorgifte naar target, response terug, SEP2-pad behouden |
| geeftBodyOnGewijzigdDoorNaarTarget | POST-body komt ongewijzigd bij de target aan |

Lokaal draaien: `mvn test`. De tests gebruiken poort 18080 (test-properties),
dus een draaiende dev-instantie op 8080 stoort niet.

Faalt een test, reproduceer het scenario dan lokaal met het bijbehorende
curl-commando uit de dev-loop hierboven.

## Belangrijke env vars

| Var | Default | Betekenis |
|---|---|---|
| `IEEE2030_PROXY_PORT` | 8080 | listen-poort van de proxy |
| `IEEE2030_CN_HEADER` | X-Client-CN | header waarin Kong het CN doorgeeft |
| `IEEE2030_TARGET_BASE` | https://127.0.0.1:8443 | SEP2-backend base URL |
| `IEEE2030_TARGET_HOST/PORT/TLS` | 127.0.0.1 / 8443 / true | SEP2-backend verbinding |
| `IEEE2030_CN_ALLOWLIST` | (leeg) | komma-gescheiden CN's; leeg = trustbased (alleen geldig cert) |
| `LOG_JSON` / `LOG_LEVEL` | true / INFO | structured logging (Filebeat-ready) |

## Contribueren aan deze integratie

1. Wijzig de route (`.camel.yaml`) of configuratie; test via de dev-loop hierboven.
2. Voeg een CI-test toe als je nieuw gedrag bouwt (testclass in `src/test/java`).
3. Commit + push naar `main`: de monorepo-workflow bouwt, test en deployt
   automatisch naar het test-cluster (image: `ghcr.io/<owner>/<repo>/ieee2030_proxy:<sha>`).
4. Zie Actions-tab voor de run; faalt een test dan draai hem lokaal na (`mvn test`).

De allowlist voor het test-cluster staat in de ConfigMap in `k8s/manifests.yaml`:
vullen via PR; de PR-historie is de auditlog van toegelaten devices.
