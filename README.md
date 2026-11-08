# my-java-tests — Schema Registry SDK & CLI

A Java SDK and command-line tool (`srctl`) for **Confluent-compatible Schema Registry** REST APIs
(Confluent Platform, Confluent Cloud, Redpanda, Karapace, Apicurio's ccompat API, …), plus a lightweight
**in-memory registry** for local development and tests.

| Module | Artifact | What it is |
|---|---|---|
| `sdk/` | `schema-registry-sdk` | Thread-safe Java client (JDK `HttpClient` + Jackson) |
| `local-server/` | `schema-registry-local` | Embeddable in-memory registry server with Avro compatibility checks |
| `serde/` | `schema-registry-serde` | JSON Schema serializer/deserializer: validates payloads and tags them with their schema id |
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
| `payload validate <subject> (-p FILE \| -d JSON) [-v VERSION]` | Validate a JSON payload against a JSON schema (exit code 4 if invalid) |
| `payload encode <subject> (-p FILE \| -d JSON) [-v VERSION] [--out FILE]` | Validate and frame a payload with its schema id (prints base64 unless `--out`) |
| `payload decode (-i FILE \| --base64 B64) [--subject S] [--no-validate]` | Resolve a framed message's schema, validate it and print the payload |
| `local-server [--port 8081] [--host 127.0.0.1]` | Run the in-memory registry |

`-f -` reads the schema from stdin. Exit codes: `0` success, `1` error, `2` usage error / schema not found
(`lookup`), `3` incompatible (`compat check`), `4` payload failed schema validation (`payload`).

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

## Producing and consuming JSON payloads (`serde`)

The serde module implements the producer → registry → consumer flow:

```
Producer ──register/lookup schema──► Schema Registry ◄──fetch schema by id── Consumer
    │                                                                          ▲
    └──── [0][schema id][JSON payload] ────────────────────────────────────────┘
                                                          validate → map to object
```

1. The **producer** resolves its JSON schema's id (registering it if `autoRegister(true)`), validates
   each object against the schema and prefixes the message with the standard wire header: magic byte `0`
   plus a 4-byte schema id.
2. The **consumer** reads the id, fetches that exact schema (cached per id), validates the payload and maps it
   to a Java class with Jackson.
3. Producers on **different versions** (v1, v2, …) can write to the same consumer. Each message is validated
   against the version it was written with. Unknown properties are ignored, so an older model can still read
   newer payloads.

```java
// Producer
JsonSchemaSerializer serializer = JsonSchemaSerializer.builder(client)
        .subject("orders-value")
        .schema(orderJsonSchema)     // or .useLatestVersion() / .useVersion(2)
        .autoRegister(true)
        .build();
byte[] message = serializer.serialize(new Order(1001, "acme", 25.5));   // throws SerdeException if invalid

// Consumer
JsonSchemaDeserializer deserializer = JsonSchemaDeserializer.builder(client)
        .expectedSubject("orders-value")   // optional: reject schemas from other subjects
        .build();
Order order = deserializer.deserialize(message, Order.class);
DeserializedMessage<Order> withMeta = deserializer.deserializeWithMetadata(message, Order.class);
withMeta.version();   // which schema version the producer used
```

`SerdeException.validationErrors()` lists every schema violation. JSON Schema drafts 4 through 2020-12 are
supported; the draft is read from `$schema` and defaults to draft-07. Validation uses
[networknt/json-schema-validator](https://github.com/networknt/json-schema-validator). Schema references
(`$ref` to other registered subjects) are not resolved yet.

From the CLI:

```bash
./bin/srctl schema register orders-value -t JSON -f examples/order-v1.schema.json
./bin/srctl schema register orders-value -t JSON -f examples/order-v2.schema.json
./bin/srctl payload validate orders-value -p examples/order-invalid.json      # INVALID, exit 4
MSG=$(./bin/srctl payload encode orders-value -p examples/order-v2.json)
./bin/srctl payload decode --base64 "$MSG" --subject orders-value              # Schema ID: 2 (orders-value v2)
```

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
