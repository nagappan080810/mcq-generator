# MCQ Question Generator (Spring AI + Redis)

A Spring Boot 4 service that generates technical interview MCQs asynchronously
using **Spring AI** with **OpenRouter** (free models), pushes each generated
question into a Redis queue (Upstash), and exposes REST endpoints for job
status and question retrieval.

When you submit a generation job, the API returns a `jobId` immediately and
runs the AI generation process in the background. As each question is
produced, it is written into a Redis hash keyed by
`technology:difficulty:jobTitle`, so all questions for a given combination are
easy to read back.

---

## Tech Stack

| Area            | Technology                                                        |
|-----------------|-------------------------------------------------------------------|
| Framework       | Spring Boot 4.0.x (Spring Framework 7)                            |
| AI              | Spring AI 2.0.x + `spring-ai-starter-model-openai`                |
| AI Provider     | OpenRouter (OpenAI-compatible endpoint) — free models by default  |
| Cache / Queue   | Redis via `spring-boot-starter-data-redis` (Upstash compatible)   |
| Language        | Java 21                                                           |
| Async           | Spring `@Async` + dedicated `ThreadPoolTaskExecutor`              |

> **Why the OpenAI starter?** OpenRouter exposes an OpenAI-compatible API, so
> Spring AI talks to it through the OpenAI Chat model with a `base-url`
> override pointing at `https://openrouter.ai/api`. To change providers later,
> swap in the matching Spring AI starter and adjust config — no controller
> changes are required.

---

## Architecture

```
┌──────────────────────┐      POST /api/v1/generate
│                      │ ────────────────────────────────►  ┌──────────────────────┐
│     HTTP Client      │      returns { "jobId": "..." }     │   GenerationController │
│                      │ ◄────────────────────────────────  │   (/api/v1)           │
└──────────────────────┘                                     └──────────┬───────────┘
                                                                         │ creates PENDING job hash
                                                                         │ returns ACCEPTED + jobId
                                                                         ▼
                                                              ┌──────────────────────┐
                                                              │   JobProcessorService │  (@Async)
                                                              │   - per technology    │
                                                              │   - update job status │
                                                              └──────────┬───────────┘
                                                                         │ for each technology
                                                                         ▼
                                                              ┌──────────────────────┐
                                                              │  McqGeneratorService  │
                                                              │  - build agent prompt │
                                                              │  - Spring AI call     │
                                                              │  - parse JSON array   │
                                                              └──────────┬───────────┘
                                                                         │ List<GenerationQuestion>
                                                                         ▼
                                                              ┌──────────────────────┐
                                                              │  RedisQuestionService │
                                                              │  - HSET question into │
                                                              │    q:tech:diff:title  │
                                                              │  - HINCR job counters  │
                                                              └──────────┬───────────┘
                                                                         ▼
                                                              ┌──────────────────────┐
                                                              │   Redis (Upstash)     │
                                                              │  job:{jobId}          │
                                                              │  q:{tech}:{diff}:{title}│
                                                              └──────────────────────┘

  GET  /api/v1/jobs/{jobId}/status        ◄── reads job:{jobId} hash
  GET  /api/v1/questions?technology=...   ◄── reads q:{tech}:{diff}:{title} hash
```

### How generation runs async

1. Client calls `POST /api/v1/generate` with a `GenerationRequest`.
2. Controller writes a `PENDING` job status hash, then fires
   `JobProcessorService.processJob(...)` on the `jobTaskExecutor` thread pool
   and returns `202 Accepted` + `jobId` immediately.
3. The async processor, for **each technology**, calls the AI and pushes each
   returned question into Redis as it is produced.
4. Job status counters (`processed_count`, `failed_count`, `current_stage`)
   are updated continuously so clients can poll progress.
5. When all technologies are done, status becomes `COMPLETED`.

---

## Redis Models

Two Redis **hash** structures are used.

### 1. Job tracking hash

```
Key: job:{jobId}                  e.g. job:batch_3f8a91c2

Field              Type      Example
--------------------------------------------------------------
status             string    "PROCESSING" | "PENDING" | "COMPLETED" | "FAILED"
total_records      int       8
processed_count    int       4
failed_count       int       1
current_stage      string    "GENERATING:Java" | "PUSHED:React" | "DONE"
started_at         string    "2026-09-09T12:00:00Z"   (ISO-8601 Instant)
last_updated       string    "2026-09-09T12:05:00Z"
error              string    "" (or message on failure)
provider           string    "openrouter"
model              string    "openrouter/free"
difficulty         string    "Medium"
job_title          string    "Senior Engineer"
```

### 2. Question hash

```
Key: q:{technology}:{difficulty}:{jobTitle}
     e.g.  q:Java:Medium:Senior Engineer
           q:React:Medium:Senior Engineer

Field (questionId)      Value (JSON-serialized GenerationQuestion)
--------------------------------------------------------------
java_q_1                { "jobId":"batch_3f8a91c2", "technology":"Java", ... }
java_q_2                { ... }
...
react_q_1               { ... }
```

`questionId` is **sequential per technology within a job**:
`{technology lowered & sanitized}_q_{seq}` (e.g. `java_q_1`, `react_q_2`).

> **Note:** Question hashes are **shared** across jobs that use the same
> `technology:difficulty:jobTitle` combination. Deleting a job only removes the
> `job:{jobId}` tracking hash; questions are deliberately left in place so other
> jobs' data is not destroyed.

---

## Java Models

### `GenerationQuestion` — a single generated/parsed MCQ

| Field           | Type            | Description                                                        |
|-----------------|-----------------|--------------------------------------------------------------------|
| `jobId`         | `String`        | Owning job id (populated when pushed to Redis)                    |
| `technology`    | `String`        | e.g. `"Java"`, `"React"`                                           |
| `area`          | `String`        | Sub-topic, e.g. `"Core Java & OOP"`                                |
| `question`      | `String`        | The MCQ stem                                                        |
| `isMultiSelect` | `boolean`       | `true` if "Select ALL that apply"                                  |
| `options`       | `List<String>`  | Exactly 4 options                                                   |
| `correctAnswer` | `List<String>`  | The correct option text(s) (derived from `correctIndexes`)         |
| `explanation`   | `String`        | 1–2 sentence layman-friendly explanation                           |
| `source`        | `QuestionSource`| `AI_GENERATED` / `MANUAL` / `IMPORTED`                             |
| `model`         | `String`        | Model id (with provider) that generated the question               |
| `generatedAt`   | `Instant`       | Timestamp of generation                                            |

### `GenerationRequest` — POST body

| Field                | Type                       | Required | Notes                                        |
|----------------------|----------------------------|----------|----------------------------------------------|
| `sessionId`          | `String`                   | no       | Client correlation id                        |
| `technologies`       | `List<String>`             | **yes**  | E.g. `["Java", "React"]`                     |
| `difficulty`         | `String`                   | **yes**  | `Easy` / `Medium` / `Hard`                   |
| `jobTitle`           | `String`                   | **yes**  | E.g. `"Senior Engineer"`                     |
| `questionsPerTech`   | `Integer`                  | **yes**  | >= 1. Total = technologies × this            |
| `areasByTechnology`  | `Map<String,List<String>>` | no       | Sub-topics per technology                    |
| `existingQuestions`  | `List<String>`             | no       | Questions to avoid repeating                 |
| `provider`           | `String`                   | no       | Optional override, e.g. `"openrouter"`       |
| `model`              | `String`                   | no       | Optional override, e.g. `"openrouter/free"`  |
| `temperature`        | `Double`                   | no       | Optional sampling temperature override       |

### `JobStatus` — status read model

Fields match the Redis job hash (`jobId`, `status`, `totalRecords`,
`processedCount`, `failedCount`, `currentStage`, `startedAt`, `lastUpdated`,
`error`, `provider`, `model`, `difficulty`, `jobTitle`).

### `QuestionSource` — enum

`AI_GENERATED`, `MANUAL`, `IMPORTED`.

---

## API Details

| Method | Path                                   | Description                                        |
|--------|----------------------------------------|----------------------------------------------------|
| POST   | `/api/v1/generate`                      | Start an async generation job → `202` + `jobId`    |
| GET    | `/api/v1/jobs/{jobId}/status`           | Get job status metadata                            |
| GET    | `/api/v1/questions`                     | Get questions by `technology`+`difficulty`+`jobTitle` |
| GET    | `/api/v1/questions/{questionId}`        | Get a single question by its field id              |
| POST   | `/api/v1/jobs/{jobId}/report`           | Report an issue with a job                         |
| DELETE | `/api/v1/jobs/{jobId}`                  | Delete a job tracking hash                         |

### POST /api/v1/generate

**Request**

```json
{
  "sessionId": "abc123",
  "technologies": ["Java", "React"],
  "difficulty": "Medium",
  "jobTitle": "Senior Engineer",
  "questionsPerTech": 3,
  "areasByTechnology": {
    "Java": ["Core Java & OOP", "Collections & Generics"],
    "React": ["Hooks", "Rendering & Performance"]
  },
  "existingQuestions": ["Which isolation level prevents phantom reads?"],
  "provider": "openrouter",
  "model": "openrouter/free",
  "temperature": 0.7
}
```

**Response** — `202 Accepted`

```json
{ "jobId": "batch_3f8a91c2" }
```

### GET /api/v1/jobs/{jobId}/status

```json
{
  "jobId": "batch_3f8a91c2",
  "status": "PROCESSING",
  "totalRecords": 6,
  "processedCount": 3,
  "failedCount": 0,
  "currentStage": "GENERATING:React",
  "startedAt": "2026-09-09T12:00:00Z",
  "lastUpdated": "2026-09-09T12:00:12Z",
  "provider": "openrouter",
  "model": "openrouter/free",
  "difficulty": "Medium",
  "jobTitle": "Senior Engineer"
}
```

### GET /api/v1/questions

Query params: `technology`, `difficulty`, `jobTitle`.

```json
{
  "java_q_1": "{\"jobId\":\"batch_3f8a91c2\",\"technology\":\"Java\", ...}",
  "java_q_2": "{\"jobId\":\"batch_3f8a91c2\",\"technology\":\"Java\", ...}"
}
```

### POST /api/v1/jobs/{jobId}/report

```json
{
  "reason": "duplicate-question",
  "questionId": "java_q_2",
  "details": "Option C repeats another question"
}
```

---

## Configuration (`application.properties`)

| Property                                     | Env var                 | Default                    |
|----------------------------------------------|-------------------------|----------------------------|
| `spring.ai.openai.base-url`                  | `OPENROUTER_BASE_URL`   | `https://openrouter.ai/api`|
| `spring.ai.openai.api-key`                   | `OPENROUTER_API_KEY`    | *(required)*               |
| `spring.ai.openai.chat.options.model`        | `MCQ_MODEL`             | `openrouter/free`          |
| `spring.ai.openai.chat.options.temperature`  | `MCQ_TEMPERATURE`       | `0.7`                      |
| `mcq.provider`                               | `MCQ_PROVIDER`          | `openrouter`               |
| `mcq.default-model`                          | `MCQ_MODEL`             | `openrouter/free`          |
| `mcq.temperature`                            | `MCQ_TEMPERATURE`       | `0.7`                      |
| `mcq.max-retries`                            | `MCQ_MAX_RETRIES`       | `2`                        |
| `spring.data.redis.url`                      | `REDIS_URL`             | *(required, Upstash URL)*  |
| `spring.data.redis.timeout`                  | `REDIS_TIMEOUT_MS`      | `5000`                     |
| `mcq.redis.ssl`                              | `MCQ_REDIS_SSL`         | `false`                    |
| `server.port`                                | `PORT`                  | `8080`                     |

### Default model

`openrouter/free` is a **Free Models Router** — OpenRouter picks an available
free model at runtime based on the request's needs. Because different routed
models may format output slightly differently, the generator defensively
strips markdown fences and **retries** (up to `mcq.max-retries`) with a nudged
prompt if JSON parsing fails.

### Connecting to Upstash Redis (TLS)

Upstash **requires TLS**. A plain `redis://` connection against a TLS-only
endpoint fails with:

```
RedisConnectionFailureException: Unable to connect to Redis
io.lettuce.core.RedisConnectionException: Connection closed prematurely
```

The app automatically enables TLS when:
- the `REDIS_URL` uses the **`rediss://`** scheme (recommended), **or**
- the host ends in **`.upstash.io`**, **or**
- `mcq.redis.ssl=true` / `MCQ_REDIS_SSL=true` is set.

Use the Upstash **connection string** (from the Redis database page) as your
`REDIS_URL`, for example:

```bash
export REDIS_URL="redis://default:password@host:port"
```

> Note: Upstash also provides a REST API endpoint, but for the Spring app use
> the TCP **connection string** (`rediss://...`), not the REST URL.

---

## Running

### Prerequisites

- OpenRouter API key (`OPENROUTER_API_KEY`) — from https://openrouter.ai/keys
- A Redis URL (`REDIS_URL`) — e.g. an Upstash `redis://default:...@...` string

```bash
export OPENROUTER_API_KEY=sk-or-v1-...
export REDIS_URL=redis://default:password@host:port

./mvnw spring-boot:run
```

### Build & test

```bash
./mvnw compile
./mvnw test
```

---

## Project Layout

```
src/main/java/com/example/hello/
├── HelloApplication.java               # @SpringBootApplication + @EnableAsync
├── HelloController.java                # legacy / and /hello endpoints
├── config/
│   ├── AsyncConfig.java                # jobTaskExecutor thread pool
│   ├── JacksonConfig.java              # Jackson 2 ObjectMapper bean
│   └── RedisConfig.java                # TLS-enabled Lettuce connection factory
├── controller/
│   └── GenerationController.java       # /api/v1 REST endpoints
├── model/
│   ├── GenerateResponse.java
│   ├── GenerationQuestion.java
│   ├── GenerationRequest.java
│   ├── JobReport.java
│   ├── JobStatus.java
│   └── QuestionSource.java
└── service/
    ├── JobNotFoundException.java
    ├── JobProcessorService.java        # @Async orchestration
    ├── McqGeneratorService.java        # Spring AI call + JSON parsing
    └── RedisQuestionService.java       # job + question hash persistence
src/main/resources/application.properties
src/test/java/com/example/hello/
├── GenerationControllerTest.java       # @WebMvcTest for /api/v1
├── HelloApplicationTests.java          # context loads (mocked AI/Redis)
└── HelloControllerTest.java            # legacy endpoint test
src/test/resources/application.properties  # disables Redis auto-config for tests
```

---

## Notes on Tests

The full Spring context requires a live Redis and an OpenRouter key, so tests
mock those external dependencies:

- `HelloApplicationTests` / `HelloControllerTest` use `@MockitoBean` for
  `StringRedisTemplate` and `ChatModel`.
- `src/test/resources/application.properties` excludes the Redis
  auto-configurations (`DataRedisAutoConfiguration`,
  `DataRedisReactiveAutoConfiguration`,
  `DataRedisRepositoriesAutoConfiguration`) so no real connection is attempted —
  and it sets a dummy OpenAI key/base-url. This keeps the tests green even when
  `REDIS_URL` / `OPENROUTER_API_KEY` are exported in the environment.
- `GenerationControllerTest` is a `@WebMvcTest` slice with mocked services.
