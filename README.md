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
| anything else | Custom 404 page for browsers, JSON error for API clients |

## Test

```bash
./mvnw test     # unit tests (*Test)
./mvnw verify   # unit + integration tests (*IT, via Failsafe)
```
