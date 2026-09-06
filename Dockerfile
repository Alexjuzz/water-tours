# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src src
RUN ./mvnw -B -DskipTests package && \
    mv target/*.jar target/app.jar

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN apk add --no-cache curl && \
    addgroup -S app && adduser -S app -G app

WORKDIR /app
COPY --from=build /workspace/target/app.jar app.jar
RUN chown app:app app.jar
USER app

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s --retries=5 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
