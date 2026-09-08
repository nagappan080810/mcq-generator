# -----------------------------------------------------------------------------
# Dockerfile - Spring Boot 4 Hello World, GraalVM Native Image (multi-stage)
#
# Build:   docker build -t hello-app .
# Run:     docker run -p 8080:8080 -e PORT=8080 hello-app
# Test:    curl http://localhost:8080/hello
# -----------------------------------------------------------------------------

# ---- Stage 1: compile + build native image with GraalVM ----
FROM ghcr.io/graalvm/native-image-community:25 AS builder

WORKDIR /build

# Copy Maven wrapper + pom first to leverage Docker layer caching
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -DskipTests dependency:go-offline || true

# Copy source and compile the native image
COPY src ./src
RUN ./mvnw -Pnative native:compile -DskipTests

# ---- Stage 2: minimal runtime - no JVM needed ----
FROM debian:bookworm-slim

RUN groupadd -r spring && useradd -r -g spring spring
WORKDIR /app

# Copy the native executable from the builder stage
COPY --from=builder /build/target/hello ./app

USER spring

# Vercel/cloud platforms set $PORT at runtime; default to 8080 otherwise
ENV PORT=8080

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "./app -Dserver.port=$PORT"]
