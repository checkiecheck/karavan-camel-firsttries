# Multi-stage build; docker build context = deze directory (rabbitmq_elastic/)
FROM docker.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B dependency:go-offline
RUN mkdir -p src/main/resources
# Expliciet de productie-route; de DEV-mock (mock-elastic-target.camel.yaml)
# hoort NIET in de productie-image.
COPY src/main/java src/main/java
COPY rabbitmq_elastic.camel.yaml src/main/resources/camel/
COPY application.properties src/main/resources/
RUN mvn -B package -DskipTests -Dmaven.test.skip=true

# JVM-mode: quarkus-run.jar heeft een gewone JRE nodig.
# (quarkus-micro-image is ALLEEN voor native executables - geen java binary!)
FROM docker.io/library/eclipse-temurin:21-jre
WORKDIR /work/
COPY --from=build /workspace/target/quarkus-app/ /work/
EXPOSE 8080
# Poort 8080 dient alléén voor health (/q/health/*); er is géén HTTP-ingress:
# dit is een messaging-worker die RabbitMQ pollt en naar Elasticsearch schrijft.
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
