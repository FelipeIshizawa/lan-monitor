FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B verify
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/lan-monitor-0.1.0.jar app.jar
USER 10001
ENTRYPOINT ["java", "-jar", "app.jar"]
