# my-java-tests — Schema Registry SDK & CLI

A Java SDK and command-line tool (`srctl`) for **Confluent-compatible Schema Registry** REST APIs
(Confluent Platform, Confluent Cloud, Redpanda, Karapace, Apicurio's ccompat API, …), plus a lightweight
**in-memory registry** for local development and tests.

| Module | Artifact | What it is |
|---|---|---|
| `sdk/` | `schema-registry-sdk` | Thread-safe Java client (JDK `HttpClient` + Jackson) |
| `local-server/` | `schema-registry-local` | Embeddable in-memory registry server with Avro compatibility checks |
| `serde/` | `schema-registry-serde` | JSON serializer/deserializer where the **Java model is the schema**: generates schemas from classes and enforces them in code |
| `cli/` | `schema-registry-cli` → `srctl.jar` | picocli-based CLI built on the SDK |

Requires Java 17+ and Maven 3.9+.

## Build

```bash
mvn package            # builds everything and runs all tests
./bin/srctl --help     # runs cli/target/srctl.jar
```

## Quick start (no external registry needed)

```bash
# terminal 1 – start an in-memory registry on :8081
./bin/srctl local-server

# terminal 2
./bin/srctl schema register orders-value -f examples/order-v1.avsc
./bin/srctl compat check    orders-value -f examples/order-incompatible.avsc --verbose   # exit code 3
./bin/srctl compat check    orders-value -f examples/order-v2.avsc                       # COMPATIBLE
./bin/srctl schema register orders-value -f examples/order-v2.avsc
./bin/srctl subjects versions orders-value
./bin/srctl schema get --subject orders-value            # latest version
./bin/srctl -o json schema get --id 1
```

## CLI reference

Global options (put them **before** the sub-command):

| Option | Env var | Default |
|---|---|---|
| `-u, --url` | `SR_URL` | `http://localhost:8081` |
| `--username` / `--password` (basic auth, e.g. Confluent Cloud API key/secret) | `SR_USERNAME` / `SR_PASSWORD` | – |
| `--token` (bearer auth) | `SR_TOKEN` | – |
| `-o, --output text\|json` | – | `text` |
| `--timeout <seconds>` | – | `30` |
| `--debug` (stack traces) | – | off |

Prefer the environment variables for secrets so they don't end up in shell history.

| Command | Description |
|---|---|
| `subjects list [--prefix P] [--deleted]` | List subjects |
| `subjects versions <subject> [--deleted]` | List versions of a subject |
| `subjects delete <subject> [--permanent]` | Soft delete (or hard delete after a soft delete) |
| `schema register <subject> (-f FILE \| -s SCHEMA) [-t AVRO\|JSON\|PROTOBUF] [-r name=subject:version]...` | Register a schema, prints its id |
| `schema get (--id N \| --subject S [-v VERSION]) [--raw]` | Fetch a schema |
| `schema lookup <subject> (-f FILE \| -s SCHEMA)` | Is this schema registered? (exit code 2 if not) |
| `schema delete <subject> <version\|latest> [--permanent]` | Delete a version |
| `schema referenced-by <subject> [version]` | Ids of schemas referencing a version |
| `schema types` | Supported schema types |
| `compat check <subject> (-f FILE \| -s SCHEMA) [-v VERSION] [--verbose]` | Test compatibility (exit code 3 if incompatible) |
| `config get [subject] [--no-default-to-global]` | Show compatibility level |
| `config set <LEVEL> [--subject S]` | Set `NONE`, `BACKWARD`, `FORWARD`, `FULL` (+ `_TRANSITIVE`) |
| `config delete <subject>` | Remove subject-level override |
| `mode get [subject]` / `mode set <READWRITE\|READONLY\|IMPORT> [--subject S]` / `mode delete <subject>` | Registry modes |
| `schema generate -c FQCN [--cp PATH] [--register SUBJECT] [--out FILE]` | Generate a JSON schema from a compiled Java class (optionally register it) |
| `schema check-type <subject> -c FQCN [--cp PATH] [-v VERSION] [--strict]` | Check a Java class against every (or one) registered version (exit code 5 on mismatch) |
| `payload validate -c FQCN [--cp PATH] (-p FILE \| -d JSON)` | Map a JSON payload into a Java class and enforce its contract (exit code 4 if invalid) |
| `payload encode <subject> -c FQCN [--cp PATH] (-p FILE \| -d JSON) [--register] [--out FILE]` | Enforce the class contract and frame the payload with the class's schema id (base64 unless `--out`) |
| `payload decode (-i FILE \| --base64 B64) [-c FQCN [--cp PATH]] [--subject S]` | Decode a framed message; with `--class`, map it into the class and enforce its contract |
| `local-server [--port 8081] [--host 127.0.0.1]` | Run the in-memory registry |

`-f -` reads the schema from stdin. Exit codes: `0` success, `1` error, `2` usage error / schema not found
(`lookup`), `3` incompatible (`compat check`), `4` payload violates the Java model's contract (`payload`), `5` Java type does not match the schema (`check-type`).

## SDK usage

```xml
<dependency>
  <groupId>com.sanjaymu.schemaregistry</groupId>
  <artifactId>schema-registry-sdk</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

```java
SchemaRegistryClient client = SchemaRegistryClient.builder()
        .baseUrl("https://psrc-xxxx.confluent.cloud")
        .basicAuth(apiKey, apiSecret)          // or .bearerToken(token)
        .requestTimeout(Duration.ofSeconds(10))
        .maxRetries(3)                          // retries IO errors, 429 and 5xx with exponential backoff
        .build();

int id = client.register("orders-value", RegisterSchemaRequest.avro(avroJson));
SchemaVersion latest = client.getLatestSchema("orders-value");
Schema byId = client.getSchemaById(id);         // cached in memory

CompatibilityResult r = client.testCompatibility("orders-value", RegisterSchemaRequest.avro(nextJson), true);
if (!r.compatible()) r.messages().forEach(System.out::println);

client.setSubjectCompatibility("orders-value", CompatibilityLevel.FULL);

try {
    client.getVersions("missing");
} catch (SchemaRegistryException e) {
    if (e.errorCode() == SchemaRegistryException.SUBJECT_NOT_FOUND) { ... }
}
```

Other schema types and references:

```java
client.register("events-value", RegisterSchemaRequest.of(jsonSchema, SchemaType.JSON));
client.register("customer-value", RegisterSchemaRequest.of(customerAvro, SchemaType.AVRO,
        List.of(new SchemaReference("com.example.Address", "address", 1))));
```

The SDK covers subjects, versions, register/lookup, schemas by id, soft/hard deletes, references,
compatibility testing, and global/subject config and mode.

## The Java model is the schema (`serde`)

The serde module implements the producer → registry → consumer flow. The **Java class is the single source of
truth**: the registered JSON schema is generated from it, and payloads are enforced **in Java code**. There is
no runtime JSON Schema validation.

```
Producer ──register schema generated from Order.class──► Schema Registry ◄──fetch schema by id── Consumer
    │                                                                                           ▲
    └──── [0][schema id][JSON payload] ─────────────────────────────────────────────────────────┘
          enforce Order's contract before sending                         map strictly into Order + enforce contract
```

### Defining the contract

The model's types, required fields and constraints form the contract:

```java
public record Order(long id,                                        // primitive → required
                    @NotBlank String customer,                      // required, non-blank
                    @NotNull @PositiveOrZero BigDecimal amount,     // required, ≥ 0
                    @Size(max = 20) String coupon,                  // optional, ≤ 20 chars
                    @Valid Buyer buyer,                             // nested object, validated too
                    Instant createdAt) { }                          // optional, ISO-8601 date-time
```

| Rule | In Java | In the generated schema |
|---|---|---|
| Types | Field types; JSON is mapped **strictly**: `"10"` is not a `long`, `10` is not a `String`, `1.5` is not an `int`, `1` is not a `boolean`, ordinals are not enums | `integer`, `number`, `string` + `format`, `array`, `object`, string `enum` |
| Required | Primitives, `@NotNull`, `@NotBlank`, `@NotEmpty`, `@JsonProperty(required = true)` must be present and non-null. A missing `int` is an error, not `0` | `required`, non-nullable |
| Optional | Every other field | Nullable (`["string","null"]`) |
| Constraints | [Jakarta Bean Validation](https://beanvalidation.org/) annotations (`@Size`, `@Min`, `@Max`, `@Positive…`, `@Pattern`, `@Email`, `@Valid` for nested objects) | `minLength`, `maximum`, `pattern`, `format: email`, … |

### Producer and consumer

```java
// Producer: schema generated from Order.class and registered on first use.
// serialize() rejects objects that violate the contract, and untyped values (Map, JsonNode, String…).
JsonSchemaSerializer serializer = JsonSchemaSerializer.builder(client)
        .subject("orders-value").forType(Order.class).autoRegister(true).build();
byte[] message = serializer.serialize(order);

// Consumer: maps strictly into Order and enforces its contract on every message.
JsonSchemaDeserializer deserializer = JsonSchemaDeserializer.builder(client)
        .expectedSubject("orders-value")   // reject schemas from other subjects
        .targetType(Order.class)            // check Order against the registered versions (below)
        .build();
Order order = deserializer.deserialize(message, Order.class);
DeserializedMessage<Order> withMeta = deserializer.deserializeWithMetadata(message, Order.class);
withMeta.version();                         // which schema version the producer used

try { ... } catch (SerdeException e) {
    e.validationErrors();   // e.g. ["$.amount: must be greater than or equal to 0", "$.id: required property is missing"]
}
```

Producers on **different versions** (v1, v2, …) can write to the same consumer. Unknown properties are ignored,
so an older model can still read newer payloads. Deserializing to `JsonNode` returns the raw payload without
enforcement, and `.validate(false)` turns off contract enforcement on either side.

A JSON type error stops the mapping, so it is reported on its own. Constraint violations are only reported once
the payload maps into the class.

### Checking the model against registered versions

Producers using `forType` always match their own schema. Consumers, and producers that write with an existing
version (`useLatestVersion()` / `useVersion(n)`), are checked against the registered schemas:

- `targetType(Order.class)` checks the class against **every registered version** when the deserializer is built,
  and against any **new schema id the first time it arrives**, such as a producer moving to v3 after the consumer
  started.
- A mismatch throws `SchemaTypeMismatchException` instead of silently losing data. `failOnTypeWarnings(true)` makes
  warnings fail too, and `startupTypeReports()` returns the per-version warnings for logging.

| Errors (refuses to start / to send) | Warnings (normal schema evolution) |
|---|---|
| Required schema property missing from the class | Optional schema property missing from the class (ignored on read) |
| Incompatible types: `number` → `int`, `string` → `long`, `array` → `String`, … | Optional Java property the schema doesn't define (stays `null`) |
| Schema allows `null` but the Java type is primitive | Property required in Java but optional in the schema (messages without it are rejected) |
| Property required in Java but not in the schema (every message would be rejected) | `oneOf`/`anyOf`/`allOf` or external `$ref` that can't be checked |
| Schema enum values the Java enum lacks | |
| Java property forbidden by `additionalProperties: false` | |

### From the CLI

```bash
javac -cp cli/target/srctl.jar -d /tmp/model examples/model/com/example/orders/*.java
M="-c com.example.orders.Order --cp /tmp/model"

./bin/srctl schema generate $M --register orders-value
./bin/srctl payload validate $M -p examples/order.json              # VALID
./bin/srctl payload validate $M -p examples/order-invalid.json      # INVALID, exit 4: $.id: expected `long` but got String value ("1003")
MSG=$(./bin/srctl payload encode orders-value $M -p examples/order-v2.json)
./bin/srctl payload decode --base64 "$MSG" $M --subject orders-value   # Schema ID: 1 (orders-value v1) as com.example.orders.Order
./bin/srctl schema check-type orders-value $M                                                 # OK, exit 0
./bin/srctl schema check-type orders-value -c com.example.orders.LegacyOrder --cp /tmp/model  # MISMATCH, exit 5
```

`SerdeMappers.defaultMapper()` holds the strict Jackson settings. It writes `java.time` values as ISO-8601 strings,
keeps `BigDecimal` values exact and ignores unknown properties. If you pass your own `ObjectMapper`, start from it.

## Using the local server in tests

```java
try (LocalSchemaRegistryServer server = LocalSchemaRegistryServer.start(0)) {   // 0 = random port
    SchemaRegistryClient client = SchemaRegistryClient.create(server.url());
    ...
}
```

The local server keeps everything in memory. It enforces compatibility for **Avro**: primitive promotions,
record field defaults and aliases, enums, arrays, maps, fixed, unions, and recursive types. **JSON Schema**
and **Protobuf** schemas only get a syntax check and are treated as compatible. Use a real registry when you
need exact compatibility behaviour for those formats.
