# Multi-stage build; docker build context = deze directory (ieee2030_proxy/)
FROM docker.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B dependency:go-offline
RUN mkdir -p src/main/resources
COPY *.camel.yaml src/main/resources/
COPY application.properties src/main/resources/
RUN mvn -B package -DskipTests

FROM quay.io/quarkus/quarkus-micro-image:2.1.0
WORKDIR /work/
COPY --from=build /workspace/target/quarkus-app/ /work/
EXPOSE 8080
# HTTP only: mTLS-terminatie gebeurt in Kong Ingress (kong.conf),
# CN van het client-certificaat komt binnen via X-Client-CN header.
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
