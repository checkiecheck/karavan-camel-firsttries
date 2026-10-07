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
- `application.properties` — configuratie; alle waarden via env vars met
  safe defaults (zie hieronder).
- `pom.xml` — Quarkus 3.15 + camel-quarkus-netty-http/core/smallrye-health.
- `Dockerfile` — multi-stage Maven build -> quarkus-micro-image, poort 8080.
- `k8s/manifests.yaml` — ConfigMap, Deployment (probes, envFrom), Service,
  KongPlugins (pre-function: anti-spoof clear_header + CN-header) en Ingress
  (`strip-path: false` zodat SEP2-paden intact blijven).
- `kong.conf` — referentie voor de Kong Ingress zelf: mTLS op Nginx-niveau
  (`nginx_proxy_ssl_verify_client = optional`) — niet in de app-repo actief,
  maar bij de Kong-configuratie te gebruiken.

## Belangrijke env vars

| Var | Default | Betekenis |
|---|---|---|
| `IEEE2030_PROXY_PORT` | 8080 | listen-poort van de proxy |
| `IEEE2030_CN_HEADER` | X-Client-CN | header waarin Kong het CN doorgeeft |
| `IEEE2030_TARGET_BASE` | https://127.0.0.1:8443 | SEP2-backend base URL |
| `IEEE2030_CN_ALLOWLIST` | (leeg) | komma-gescheiden CN's; leeg = trustbased (alleen geldig cert) |
| `LOG_JSON` / `LOG_LEVEL` | true / INFO | structured logging (Filebeat-ready) |

## Deploy

De generieke monorepo-workflow (`.github/workflows/deploy-integrations.yml`)
detecteert wijzigingen in deze directory, bouwt de image
(`ghcr.io/<owner>/<repo>/ieee2030_proxy:<sha>`), substitueert `<IMAGE>` en
`<CA_CERTIFICATE_ID>` in `k8s/manifests.yaml` en appt deze naar het
`integration` namespace. Rollback = vorige sha-tag opnieuw deployen.

De allowlist staat in de ConfigMap in `k8s/manifests.yaml`: vullen via PR;
de PR-historie is de auditlog van toegelaten devices. Een Secret met de env
var `IEEE2030_CN_ALLOWLIST` wint altijd van de ConfigMap-default.
