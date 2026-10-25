# syntax=docker/dockerfile:1
# Multi-stage build: Maven Wrapper on a JDK 25 image, layered Spring Boot jar on a JRE 25 runtime image.

FROM eclipse-temurin:25.0.4.1_1-jdk-alpine-3.24 AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/modulithplatformservice-*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:25.0.4.1_1-jre-alpine-3.24
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
