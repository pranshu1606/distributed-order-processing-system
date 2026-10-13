FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build
COPY pom.xml .
COPY src ./src

RUN --mount=type=cache,id=maven-17,target=/root/.m2,sharing=locked \
    MAVEN_OPTS="-Xmx256m -XX:ActiveProcessorCount=2" mvn -B clean package -DskipTests

FROM eclipse-temurin:17-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/target/order-service-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "app.jar"]
