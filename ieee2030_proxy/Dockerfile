# Multi-stage build; docker build context = deze directory (ieee2030_proxy/)
# Karavan root-layout: pom zet root-bestanden zelf op het classpath.
FROM docker.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B dependency:go-offline
# ALLÉÉN de echte route + properties; de DEV-mock (mock-sep2-target.camel.yaml)
# wordt bewust NIET gekopieerd en laadt ook niet mee (routes-include-pattern).
COPY ieee2030-proxy.camel.yaml application.properties ./
RUN mvn -B package -DskipTests -Dmaven.test.skip=true

# JVM-mode: quarkus-run.jar heeft een gewone JRE nodig.
# (quarkus-micro-image is ALLEEN voor native executables - geen java binary!)
FROM docker.io/library/eclipse-temurin:21-jre
WORKDIR /work/
COPY --from=build /workspace/target/quarkus-app/ /work/
EXPOSE 8080
# HTTP only: mTLS-terminatie gebeurt in Kong Ingress (kong.conf),
# CN van het client-certificaat komt binnen via X-Client-CN header.
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
