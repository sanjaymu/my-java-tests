package com.sanjaymu.schemaregistry.serde;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Enforces a Java model as the schema contract — in code, without a JSON Schema validator.
 * <p>
 * The contract of a class is:
 * <ul>
 *   <li><b>types</b> — the Java field types. JSON is mapped strictly: no string→number, number→string,
 *       float→int or number→boolean coercion, and unknown enum values are rejected;</li>
 *   <li><b>required properties</b> — primitives, {@code @JsonProperty(required = true)}, {@code @NotNull},
 *       {@code @NotBlank} and {@code @NotEmpty} properties must be present and non-null (checked on the JSON,
 *       so a missing {@code int} is an error rather than silently {@code 0});</li>
 *   <li><b>constraints</b> — Jakarta Bean Validation annotations such as {@code @Size}, {@code @Min},
 *       {@code @PositiveOrZero}, {@code @Pattern}, {@code @Email}, including nested objects marked {@code @Valid}.</li>
 * </ul>
 * {@link JavaTypeSchemaGenerator} expresses the same rules in the generated JSON schema, so the registered
 * schema and the code agree.
 */
public final class JavaContract {

    private static final List<Class<? extends Annotation>> REQUIRED_ANNOTATIONS =
            List.of(NotNull.class, NotBlank.class, NotEmpty.class);

    private static final Validator VALIDATOR;

    static {
        ValidatorFactory factory = Validation.byDefaultProvider().configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory();
        VALIDATOR = factory.getValidator();
    }

    private final ObjectMapper mapper;

    public JavaContract(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    /** Annotations that make a property required (in addition to primitives and {@code @JsonProperty(required=true)}). */
    public static List<Class<? extends Annotation>> requiredAnnotations() {
        return REQUIRED_ANNOTATIONS;
    }

    /** Whether a Jackson property is required by the Java contract. */
    public static boolean isRequired(BeanPropertyDefinition p) {
        if (p.getPrimaryType().isPrimitive() || p.isRequired()) {
            return true;
        }
        for (AnnotatedMember m : new AnnotatedMember[]{p.getField(), p.getGetter(), p.getSetter(),
                p.getConstructorParameter()}) {
            if (m != null) {
                for (Class<? extends Annotation> a : REQUIRED_ANNOTATIONS) {
                    if (m.hasAnnotation(a)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** True for targets that carry no contract (raw JSON, maps, {@code Object}). */
    public static boolean isUntyped(Class<?> type) {
        return type == Object.class || JsonNode.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type);
    }

    /**
     * Maps JSON into {@code type}, enforcing the contract.
     *
     * @throws SerdeException with {@link SerdeException#validationErrors()} when the payload violates the contract
     */
    public <T> T read(JsonNode json, Class<T> type) {
        String what = type.getSimpleName();
        List<String> missing = new ArrayList<>();
        missingRequired("$", json, mapper.constructType(type), missing);
        if (!missing.isEmpty()) {
            throw new SerdeException("Payload does not satisfy " + what, missing);
        }
        T value;
        try {
            value = mapper.treeToValue(json, type);
        } catch (MismatchedInputException e) {
            throw new SerdeException("Payload does not satisfy " + what, List.of(describe(e)));
        } catch (JsonProcessingException e) {
            throw new SerdeException("Could not map payload to " + type.getName() + ": " + e.getOriginalMessage(), e);
        }
        validateOrThrow(value);
        return value;
    }

    /** Bean Validation violations for {@code value}, as {@code $.path: message}, sorted. */
    public List<String> violations(Object value) {
        List<String> out = new ArrayList<>();
        for (ConstraintViolation<Object> v : VALIDATOR.validate(value)) {
            String path = v.getPropertyPath().toString();
            out.add((path.isEmpty() ? "$" : "$." + path) + ": " + v.getMessage());
        }
        out.sort(null);
        return out;
    }

    /** Throws {@link SerdeException} if {@code value} violates its Bean Validation constraints. */
    public void validateOrThrow(Object value) {
        List<String> errors = violations(value);
        if (!errors.isEmpty()) {
            throw new SerdeException(value.getClass().getSimpleName() + " violates its constraints", errors);
        }
    }

    private void missingRequired(String path, JsonNode node, JavaType type, List<String> errors) {
        if (node == null || node.isNull() || isUntyped(type.getRawClass())) {
            return;
        }
        if ((type.isCollectionLikeType() || type.isArrayType()) && node.isArray()) {
            int i = 0;
            for (JsonNode element : node) {
                missingRequired(path + "[" + i++ + "]", element, type.getContentType(), errors);
            }
            return;
        }
        if (type.isMapLikeType() && node.isObject()) {
            node.fields().forEachRemaining(e ->
                    missingRequired(path + "." + e.getKey(), e.getValue(), type.getContentType(), errors));
            return;
        }
        Class<?> raw = type.getRawClass();
        if (!node.isObject() || type.isPrimitive() || type.isEnumType() || raw.getName().startsWith("java.")) {
            return;
        }
        for (BeanPropertyDefinition p : mapper.getDeserializationConfig().introspect(type).findProperties()) {
            if (!p.couldDeserialize()) {
                continue;
            }
            JsonNode child = node.get(p.getName());
            String childPath = path + "." + p.getName();
            if (child == null || child.isNull()) {
                if (isRequired(p)) {
                    errors.add(childPath + ": required property is " + (child == null ? "missing" : "null"));
                }
            } else {
                missingRequired(childPath, child, p.getPrimaryType(), errors);
            }
        }
    }

    private static String describe(MismatchedInputException e) {
        StringBuilder path = new StringBuilder("$");
        for (JsonMappingException.Reference r : e.getPath()) {
            if (r.getFieldName() != null) {
                path.append('.').append(r.getFieldName());
            } else if (r.getIndex() >= 0) {
                path.append('[').append(r.getIndex()).append(']');
            }
        }
        String msg = e.getOriginalMessage();
        int nl = msg.indexOf('\n');
        msg = nl > 0 ? msg.substring(0, nl) : msg;
        // Drop Jackson's configuration hint, which is noise for payload authors.
        msg = msg.replaceAll("\\s*\\(but (might|could) if coercion[^)]*\\)", "")
                .replaceAll("`java\\.(lang|math|time|util)\\.", "`")
                .replaceAll("^Cannot coerce (.+?) to (`[^`]+`) value$", "expected $2 but got $1");
        return path + ": " + msg;
    }
}
