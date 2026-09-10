package org.ovirt.engine.core.dao;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Guards against inconsistencies between the parameters that are used in stored procedures and the columns of the tables they reference.
 */
public class DbTypeConsistencyTest extends BaseDaoTestCase<TagDao> {

    /**
     * Helper functions ({@code fn_db_*}) take parameters like {@code v_table}
     * or {@code v_column} that are not bound to table columns, so they are out
     * of scope for this check.
     */
    private static final String HELPER_FUNCTION_PREFIX = "fn_db_";

    /**
     * Base tables a stored procedure body touches. Views ({@code *_view}) are
     * not part of the schema map and therefore contribute no columns.
     */
    private static final Pattern TABLE_REF_PATTERN = Pattern.compile(
            "\\b(?:INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|FROM|JOIN)\\s+([a-z][a-z0-9_]*)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Maps every sql type spelling used in the engine schema (both the
     * {@code information_schema} spellings of the column types and the
     * {@code format_type} spellings of the procedure parameters) to a single
     * canonical name. Length/precision modifiers are ignored because it does not affect assignment compatibility.
     */
    private static final Map<String, String> CANONICAL_TYPES = buildCanonicalTypes();

    /**
     * Width ordering within the integer family, used to tell a narrowing
     * (data-loss risk) from a widening (harmless) in the failure report.
     */
    private static final Map<String, Integer> INTEGER_WIDTH = Map.of(
            "SMALLINT", 1,
            "INTEGER", 2,
            "BIGINT", 3);

    @Inject
    private JdbcTemplate jdbcTemplate;

    @Test
    public void storedProcedureParameterTypesMatchColumnTypes() {
        Map<String, Map<String, String>> schema = loadColumnTypes();
        List<Mismatch> mismatches = new ArrayList<>();

        for (StoredProcedure storedProcedure : loadStoredProcedures()) {
            if (storedProcedure.name.startsWith(HELPER_FUNCTION_PREFIX)) {
                continue;
            }
            Set<String> tables = referencedTables(storedProcedure.definition, schema.keySet());
            for (Map.Entry<String, String> argument : storedProcedure.arguments.entrySet()) {
                ColumnRef expected = resolveType(columnName(argument.getKey()), tables, schema);
                String found = canonicalType(argument.getValue());
                if (expected == null || found == null || found.equals(expected.type)) {
                    continue;
                }
                mismatches.add(new Mismatch(storedProcedure.name,
                        argument.getKey(),
                        found,
                        expected));
            }
        }

        assertTrue(mismatches.isEmpty(), () -> render(mismatches));
    }

    /**
     * @return all columns of all base tables in the public schema as {@code table -> (column -> canonical type)}
     */
    private Map<String, Map<String, String>> loadColumnTypes() {
        String sql = "SELECT c.table_name, c.column_name, c.data_type"
                + " FROM information_schema.columns c"
                + " JOIN information_schema.tables t"
                + "   ON t.table_schema = c.table_schema AND t.table_name = c.table_name"
                + " WHERE c.table_schema = 'public'"
                + "   AND t.table_type = 'BASE TABLE'";
        Map<String, Map<String, String>> schema = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String type = canonicalType(rs.getString(3));
            if (type != null) {
                schema.computeIfAbsent(rs.getString(1).toLowerCase(),
                        table -> new HashMap<>())
                        .put(rs.getString(2).toLowerCase(), type);
            }
        });
        return schema;
    }

    /**
     * @return all stored functions of the public schema with their declared
     *         parameters, as reported by the database
     */
    private List<StoredProcedure> loadStoredProcedures() {
        String sql = "SELECT p.oid,"
                + " p.proname,"
                + " u.argname,"
                + " format_type(u.argtype, NULL),"
                + " pg_get_functiondef(p.oid)"
                + " FROM pg_proc p"
                + " CROSS JOIN LATERAL unnest("
                + "        coalesce(p.proallargtypes, p.proargtypes::oid[]),"
                + "        coalesce(p.proargnames, '{}')) AS u(argtype, argname)"
                + " WHERE p.pronamespace = 'public'::regnamespace"
                + "   AND p.prokind = 'f'"
                + "   AND u.argname IS NOT NULL"
                + "   AND u.argtype IS NOT NULL";
        Map<Long, StoredProcedure> storedProcedures = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String name = rs.getString(2);
            String definition = rs.getString(5);
            storedProcedures.computeIfAbsent(rs.getLong(1),
                    id -> new StoredProcedure(name, definition))
                    .addArgument(rs.getString(3), rs.getString(4));
        });
        return new ArrayList<>(storedProcedures.values());
    }

    /**
     * Strips the conventional v_ parameter prefix.
     */
    private static String columnName(String parameterName) {
        String name = parameterName.toLowerCase();
        return name.startsWith("v_") ? name.substring(2) : name;
    }

    private static Set<String> referencedTables(String definition, Set<String> knownTables) {
        Set<String> tables = new HashSet<>();
        Matcher matcher = TABLE_REF_PATTERN.matcher(definition);
        while (matcher.find()) {
            String table = matcher.group(1).toLowerCase();
            if (knownTables.contains(table)) {
                tables.add(table);
            }
        }
        return tables;
    }

    /**
     * The column's type as seen through the tables the procedure touches.
     * Returns null when the column is unknown or its type is ambiguous
     * across the referenced tables, in which case the parameter is not
     * checkable.
     */
    private static ColumnRef resolveType(String column,
            Set<String> tables,
            Map<String, Map<String, String>> schema) {
        ColumnRef result = null;
        for (String table : tables) {
            String type = schema.get(table).get(column);
            if (type == null) {
                continue;
            }
            if (result != null && !result.type.equals(type)) {
                return null;
            }
            if (result == null) {
                result = new ColumnRef(table, type);
            }
        }
        return result;
    }

    /**
     * Canonical type name for a column/parameter type spec, or null
     * when the type is outside the families this test understands.
     * Ignores length/precision modifiers.
     */
    private static String canonicalType(String typeSpec) {
        String type = typeSpec.replaceAll("\\([^)]*\\)", " ")
                .toLowerCase()
                .trim()
                .replaceAll("\\s+", " ");
        return CANONICAL_TYPES.get(type);
    }

    private static String classify(String found, String expected) {
        Integer foundWidth = INTEGER_WIDTH.get(found);
        Integer expectedWidth = INTEGER_WIDTH.get(expected);
        if (foundWidth != null && expectedWidth != null) {
            return foundWidth < expectedWidth ? "NARROWING" : "WIDENING";
        }
        return "MISMATCH";
    }

    private static String render(List<Mismatch> mismatches) {
        StringBuilder message = new StringBuilder(
                "\nThe following stored procedure parameters do not match the column types in the database:\n");
        for (Mismatch mismatch : mismatches) {
            message.append(String.format("  %s: parameter %s is declared %s, but column %s.%s is %s (%s)%n",
                    mismatch.functionName,
                    mismatch.parameterName,
                    mismatch.found,
                    mismatch.expected.table,
                    columnName(mismatch.parameterName),
                    mismatch.expected.type,
                    classify(mismatch.found, mismatch.expected.type)));
        }
        message.append("\nUpdate the parameter declarations in packaging/dbscripts/*_sp.sql")
                .append(" to match the column types.\n");
        return message.toString();
    }

    private static Map<String, String> buildCanonicalTypes() {
        Map<String, String> types = new HashMap<>();
        // integer family
        types.put("smallint", "SMALLINT");
        types.put("int2", "SMALLINT");
        types.put("integer", "INTEGER");
        types.put("int", "INTEGER");
        types.put("int4", "INTEGER");
        types.put("bigint", "BIGINT");
        types.put("int8", "BIGINT");
        // boolean
        types.put("boolean", "BOOLEAN");
        types.put("bool", "BOOLEAN");
        // exact / floating-point numeric
        types.put("numeric", "NUMERIC");
        types.put("decimal", "NUMERIC");
        types.put("real", "REAL");
        types.put("float4", "REAL");
        types.put("double precision", "DOUBLE PRECISION");
        types.put("float8", "DOUBLE PRECISION");
        // character / text
        types.put("character varying", "VARCHAR");
        types.put("varchar", "VARCHAR");
        types.put("character", "CHAR");
        types.put("char", "CHAR");
        types.put("bpchar", "CHAR");
        types.put("text", "TEXT");
        // uuid
        types.put("uuid", "UUID");
        // date / time
        types.put("timestamp with time zone", "TIMESTAMPTZ");
        types.put("timestamptz", "TIMESTAMPTZ");
        types.put("timestamp without time zone", "TIMESTAMP");
        types.put("timestamp", "TIMESTAMP");
        types.put("date", "DATE");
        types.put("time without time zone", "TIME");
        types.put("time", "TIME");
        types.put("time with time zone", "TIMETZ");
        // binary / json
        types.put("jsonb", "JSONB");
        types.put("json", "JSON");
        types.put("bytea", "BYTEA");
        return types;
    }

    private static final class StoredProcedure {
        private final String name;
        private final String definition;
        private final Map<String, String> arguments = new LinkedHashMap<>();

        private StoredProcedure(String name, String definition) {
            this.name = name;
            this.definition = definition;
        }

        private void addArgument(String name, String type) {
            arguments.put(name, type);
        }
    }

    private static final class ColumnRef {
        private final String table;
        private final String type;

        private ColumnRef(String table, String type) {
            this.table = table;
            this.type = type;
        }
    }

    private static final class Mismatch {
        private final String functionName;
        private final String parameterName;
        private final String found;
        private final ColumnRef expected;

        private Mismatch(String functionName, String parameterName, String found, ColumnRef expected) {
            this.functionName = functionName;
            this.parameterName = parameterName;
            this.found = found;
            this.expected = expected;
        }
    }
}
