# Spring Boot 4 Hello World (GraalVM Native Image)

A minimal Spring Boot 4 REST application that is compiled to a GraalVM native
image and packaged in a tiny Docker container (no JVM needed at runtime), ready
to deploy to **Vercel** as a container image (Vercel Function).

## Tech Stack

- **Spring Boot 4.0.8** (Spring Framework 7) — `spring-boot-starter-webmvc`
- **Java 25** (GraalVM) — native builds require GraalVM 25+ (Spring Boot 4 requirement)
- **GraalVM Native Image** — milliseconds startup, ~80 MB container image
- **Docker** — multi-stage build (compile + runtime stages)
- **Vercel** — deploys the OCI image via `Dockerfile.vercel`

## REST Endpoints

| Method | Path      | Description                          |
|--------|-----------|--------------------------------------|
| GET    | `/`       | Plain-text info message              |
| GET    | `/hello`  | JSON greeting, e.g. `{"message":"...", "status":"UP"}` |

The server binds to port `8080` by default, or the `$PORT` environment variable
when set (used by Vercel, which defaults to port `80`).

---

## Project Layout

```
.
├── Dockerfile              # Generic GraalVM-native Docker image
├── Dockerfile.vercel       # Docker image used by Vercel
├── pom.xml                 # Maven build (Boot 4 + native plugin)
├── mvnw / mvnw.cmd         # Maven wrapper (Java 21)
├── .mvn/wrapper/           # Wrapper config
└── src/
    ├── main/java/com/example/hello/
    │   ├── HelloApplication.java   # @SpringBootApplication entry point
    │   └── HelloController.java    # REST controller (/ and /hello)
    ├── main/resources/
    │   └── application.properties  # server.port=${PORT:8080}
    └── test/java/com/example/hello/
        ├── HelloApplicationTests.java
        └── HelloControllerTest.java  # MockMvc tests for / and /hello
```

---

## Prerequisites

- **JDK 21+** (any distribution works for running tests and the JVM app; GraalVM 25
  is needed only for native builds)
- **Docker** 20.10+ (for building images). Give Docker at least **8 GB RAM**
  and 2+ CPUs — native compilation is memory intensive.
- **Maven 3.9+** (or use the bundled `./mvnw` wrapper — no install needed)
- **Vercel CLI** (only for the deploy step): `npm i -g vercel`

---

## Run Locally (JVM — fast feedback)

```bash
# Run tests
./mvnw test

# Start the app on the JVM (port 8080)
./mvnw spring-boot:run
```

Then test:

```bash
curl http://localhost:8080/hello
# {"message":"Hello from Spring Boot 4 with GraalVM Native Image!","status":"UP"}
```

Stop with `Ctrl+C`.

---

## Build the GraalVM Native Image

### Option A — Native binary with Docker (recommended)

```bash
docker build -t hello-app .
```

This uses a multi-stage Dockerfile:
1. `ghcr.io/graalvm/native-image-community:25` — compiles the native binary
2. `debian:bookworm-slim` — minimal runtime (no JVM)

The first build downloads GraalVM + Maven deps and compiles the native image,
so it takes **10–15 minutes**. Subsequent builds are faster via layer caching.

### Option B — Native binary locally (requires GraalVM 25 installed)

```bash
./mvnw -Pnative native:compile
# binary at target/hello
./target/hello
```

> GraalVM 25 is required — Spring Boot 4 adds a runtime version check and refuses
> to start AOT-processed images built with GraalVM 21 or 24.

## Native Image Config Notes

A project-level native reflection config lives at
`src/main/resources/META-INF/native-image/com.example/hello/reflect-config.json`.
It registers Tomcat's connector/protocol classes (`AbstractProtocol`,
`AbstractHttp11Protocol`, `Http11NioProtocol`, `Connector`) with `allPublicMethods`
because Tomcat's `IntrospectionUtils` reflectively reads/writes connector
properties during startup; without these hints the native binary fails with
`MissingReflectionRegistrationError`.

---

## Run the Docker Image Locally

```bash
docker run -p 8080:8080 -e PORT=8080 hello-app
```

The native app starts in **milliseconds**:

```text
Started HelloApplication in 0.047 seconds
```

Test it:

```bash
curl http://localhost:8080/hello
```

---

## Deploy to Vercel

Vercel detects a `Dockerfile.vercel` (or `Containerfile.vercel`) at the project
root and deploys the resulting OCI image as a **Vercel Function** (container
image). Vercel builds the image, pushes it to the Vercel Container Registry
(VCR), and serves it, auto-scaling to zero when idle.

### Step 1 — Install / login to the Vercel CLI

```bash
npm i -g vercel
vercel login
```

### Step 2 — Deploy

```bash
# Preview deployment (URL like https://your-app-<hash>.vercel.app)
vercel

# Production deployment
vercel --prod
```

Alternatively, push this repo to GitHub and **Import** it in the Vercel
dashboard — Vercel auto-detects `Dockerfile.vercel` and deploys it.

### Step 3 — Verify

```bash
curl https://your-app.vercel.app/hello
```

Expected response:

```json
{"message":"Hello from Spring Boot 4 with GraalVM Native Image!","status":"UP"}
```

### Vercel Notes

- **Stateless only.** Container functions scale to zero and keep nothing between
  requests. Persist any state in an external service (DB/cache).
- **Port**: Vercel routes HTTP to port `80` by default, or the `$PORT` env var.
  `Dockerfile.vercel` starts the app with `-Dserver.port=${PORT:-80}`.
- **Limits**: Secure Compute and Static IPs are not available for custom
  container images yet.
- **Logs** (`stdout`/`stderr`) are broadcast to requests on the instance and
  visible in the Vercel dashboard.

---

## Testing

```bash
# All tests (JVM)
./mvnw test
```

- `HelloApplicationTests` — verifies the Spring context loads.
- `HelloControllerTest` — uses MockMvc to assert `/` and `/hello` behavior.

For a smoke test against a running instance:

```bash
# after `./mvnw spring-boot:run` or a docker container
curl -sf http://localhost:8080/hello | grep -q '"status":"UP"' && echo "OK"
```

> Note: Spring Boot 4 modularized the servlet web test support. The MockMvc
> annotations (`@AutoConfigureMockMvc`, `@WebMvcTest`) now live in
> `org.springframework.boot.webmvc.test.autoconfigure` and require the
> `spring-boot-starter-webmvc-test` test dependency (already configured in
> `pom.xml`).

---

## Removing the compiled artifacts

```bash
./mvnw clean
rm -rf target
```

The `.gitignore` excludes `target/`, IDE files, and `.vercel/`.
