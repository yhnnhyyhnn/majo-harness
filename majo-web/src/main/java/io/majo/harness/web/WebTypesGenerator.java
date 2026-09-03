package io.majo.harness.web;

import io.majo.harness.session.SessionEventType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates {@code web-ui/src/types.ts} from the Java wire contract — the DTO
 * records in {@link WebApiModels} plus the {@link SessionEventType} enum — so
 * the browser types cannot drift from what {@link WebMain} actually emits.
 *
 * <p>Run after changing a DTO or the event enum:
 * <pre>
 * mvn -q -pl majo-web -am compile exec:java \
 *   -Dexec.mainClass=io.majo.harness.web.WebTypesGenerator \
 *   -Dexec.args="web-ui/src/types.ts"
 * </pre>
 * (or {@code bash scripts/gen-web-types.sh}).
 */
public final class WebTypesGenerator {

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "web-ui/src/types.ts");
        StringBuilder ts = new StringBuilder();
        ts.append("// AUTO-GENERATED from ").append(WebApiModels.class.getName())
                .append(" and ").append(SessionEventType.class.getName()).append('\n')
                .append("// Do not edit by hand — run scripts/gen-web-types.sh after changing the wire contract.\n\n");

        ts.append("export type EventKind =\n");
        for (SessionEventType type : SessionEventType.values()) {
            ts.append("  | \"").append(type.name()).append("\"\n");
        }

        List<Class<?>> dtos = Arrays.stream(WebApiModels.class.getDeclaredClasses())
                .sorted(Comparator.comparing(Class::getSimpleName))
                .toList();
        for (Class<?> dto : dtos) {
            ts.append('\n');
            emitRecord(ts, dto);
        }

        ts.append('\n')
                .append("export type StreamEvent =\n")
                .append("  | { event: \"log\"; data: EventFrame }\n")
                .append("  | { event: \"chunk\"; data: StreamChunk }\n")
                .append("  | { event: \"done\"; data: StreamDone }\n")
                .append("  | { event: \"fail\"; data: StreamFail }\n")
                .append("  | { event: \"approval\"; data: ApprovalFrame }\n")
                .append("  | { event: \"question\"; data: QuestionFrame };\n");

        Files.createDirectories(output.getParent());
        Files.writeString(output, ts.toString(), StandardCharsets.UTF_8);
        System.out.println("wrote " + output);
    }

    private static void emitRecord(StringBuilder ts, Class<?> dto) {
        ts.append("export interface ").append(dto.getSimpleName()).append(" {\n");
        for (RecordComponent component : dto.getRecordComponents()) {
            boolean optional = component.getAnnotation(OptionalWire.class) != null;
            String typeName = component.getName().equals("kind") ? "EventKind" : tsType(component.getGenericType());
            ts.append("  ").append(component.getName());
            if (optional) {
                ts.append('?');
            }
            ts.append(": ").append(typeName).append(";\n");
        }
        ts.append("}\n");
    }

    private static String tsType(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            if (parameterized.getRawType() == List.class) {
                Type item = parameterized.getActualTypeArguments()[0];
                return tsType(item) + "[]";
            }
            if (parameterized.getRawType() == Map.class) {
                return "Record<string, unknown>";
            }
        }
        if (type == String.class) {
            return "string";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class) {
            return "number";
        }
        if (type instanceof Class<?> clazz) {
            return clazz.getSimpleName();
        }
        throw new IllegalStateException("unsupported wire type " + type);
    }

    private WebTypesGenerator() {}
}
