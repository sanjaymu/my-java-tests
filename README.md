# my-java-tests
Spring Boot 4.1 / Java 21 hello world application.

## Run

```bash
./mvnw spring-boot:run
curl localhost:8080/hello            # Hello, World!
curl "localhost:8080/hello?name=Bob" # Hello, Bob!
```

## Test

```bash
./mvnw test     # unit tests (HelloControllerTest, HelloControllerWebMvcTest)
./mvnw verify   # unit + integration tests (HelloControllerIT, via Failsafe)
```
