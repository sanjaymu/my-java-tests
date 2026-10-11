# my-java-tests
Spring Boot 4.1 / Java 21 hello world application.

## Run

```bash
./mvnw spring-boot:run
curl localhost:8080/hello            # Hello, World!
curl "localhost:8080/hello?name=Bob" # Hello, Bob!
```

## Endpoints

| Path | Description |
|---|---|
| `GET /` | Welcome page listing the endpoints |
| `GET /hello?name=X` | Returns `Hello, X!` (`name` defaults to `World`) |
| `GET /actuator/health` | Health check, returns `{"status":"UP"}` |
| `GET /actuator/health/liveness`, `/actuator/health/readiness` | Liveness and readiness probes |
| `GET /ai/ask?question=...` | Answers a question with Claude. **Requires the `X-API-Key` header.** |
| anything else | Custom 404 page for browsers, JSON error for API clients |

Everything except `/ai/**` is public.

## Configuration

| Environment variable | Purpose |
|---|---|
| `ANTHROPIC_API_KEY` | Claude API key from https://console.anthropic.com |
| `APP_API_KEY` | Key clients must send as `X-API-Key` to call `/ai/**`. If unset, all `/ai/**` calls are rejected. |

Run locally with AI enabled:

```bash
ANTHROPIC_API_KEY=sk-ant-... APP_API_KEY=my-local-key ./mvnw spring-boot:run
curl -H "X-API-Key: my-local-key" "localhost:8080/ai/ask?question=What+is+Spring+Boot"
```

## Deploy to Google Cloud Run (keys in Secret Manager)

```bash
# 1. Store both keys in Secret Manager (printf avoids a trailing newline in the secret)
gcloud services enable secretmanager.googleapis.com
printf '%s' 'sk-ant-YOUR-KEY' | gcloud secrets create anthropic-api-key --data-file=-
openssl rand -hex 32 | tr -d '\n' | gcloud secrets create app-api-key --data-file=-

# 2. Let Cloud Run's service account read them
PROJECT_NUMBER=$(gcloud projects describe "$(gcloud config get-value project)" --format='value(projectNumber)')
for s in anthropic-api-key app-api-key; do
  gcloud secrets add-iam-policy-binding "$s" \
    --member="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
    --role=roles/secretmanager.secretAccessor
done

# 3. Deploy, exposing the secrets as environment variables
gcloud run deploy demo --source . --region us-central1 --allow-unauthenticated \
  --set-build-env-vars GOOGLE_RUNTIME_VERSION=21 \
  --set-secrets=ANTHROPIC_API_KEY=anthropic-api-key:latest,APP_API_KEY=app-api-key:latest

# 4. Call the AI endpoint
APP_KEY=$(gcloud secrets versions access latest --secret=app-api-key)
curl -H "X-API-Key: $APP_KEY" "https://YOUR-SERVICE-URL/ai/ask?question=Hello"
```

To rotate a key, add a new version (`gcloud secrets versions add ...`) and redeploy.

## Test

```bash
./mvnw test     # unit tests (*Test), no API keys needed
./mvnw verify   # unit + integration tests (*IT, via Failsafe)
```
