# RabbitMQ → Elasticsearch

Camel 4.x YAML DSL integration route: consumes from RabbitMQ and indexes messages into Elasticsearch.

- Karavan-compatible YAML (`rabbitmq-to-elastic.camel.yaml`)
- Fully parametrised via `application.properties` placeholders
- Structured JSON logging for Filebeat (logstash-logback-encoder)
- Dead-letter exchange on failure

Dependencies: `camel-rabbitmq`, `camel-elasticsearch`, `camel-yaml-dsl`, `net.logstash.logback:logstash-logback-encoder`.
