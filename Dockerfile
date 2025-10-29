# Multi-stage build for Spring Boot (Gradle) on Java 17

# ---- Build stage ----
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

# Copy Gradle wrapper and build scripts first to leverage layer caching
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew \
    && sed -i 's/\r$//' gradlew

# Copy source
COPY src src

# Build Spring Boot fat jar (skip tests for faster image builds)
RUN ./gradlew --no-daemon clean bootJar -x test \
    && mkdir -p build/boot \
    && JAR_FILE=$(ls build/libs/*-SNAPSHOT.jar 2>/dev/null | grep -v "-plain" || true) \
    && if [ -z "$JAR_FILE" ]; then JAR_FILE=$(ls build/libs/*.jar | grep -v "-plain" | head -n1); fi \
    && cp "$JAR_FILE" build/boot/app.jar

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-jammy
ENV TZ=Asia/Seoul \
    JAVA_OPTS="-Xms256m -Xmx512m"

# Create non-root user
RUN useradd -r -u 1001 -m spring
WORKDIR /app

# Copy the built jar from the build stage; deterministic location
COPY --from=build /workspace/build/boot/app.jar /app/app.jar

EXPOSE 8080
USER spring

# You can override server.port by passing -Dserver.port or env SERVER_PORT
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar /app/app.jar"]
