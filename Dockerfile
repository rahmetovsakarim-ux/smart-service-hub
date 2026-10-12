# ---- build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- run ----
FROM eclipse-temurin:17-jre
WORKDIR /app
# do not run the application as root
RUN useradd --system --create-home --shell /usr/sbin/nologin hub
COPY --from=build /app/target/smart-service-hub-1.0.0.jar app.jar
USER hub
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
