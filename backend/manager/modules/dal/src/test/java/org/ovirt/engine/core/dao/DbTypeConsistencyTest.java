package org.ovirt.engine.core.dao;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Guards against inconsistencies between the parameters that are used in
 * stored procedures and the columns of the tables they reference, including
 * length/precision modifiers.
 */
public class DbTypeConsistencyTest extends BaseDaoTestCase<TagDao> {

    @Inject
    private JdbcTemplate jdbcTemplate;

    /**
     * Helper functions ({@code fn_db_*}) take parameters like {@code v_table}
     * or {@code v_column} that are not bound to table columns, so they are out
     * of scope for this check.
     */
    private static final String HELPER_FUNCTION_PREFIX = "fn_db_";

    /**
     * Base tables a stored procedure body touches. Views are
     * not part of the schema map and therefore contribute no columns.
     */
    private static final Pattern TABLE_REF_PATTERN = Pattern.compile(
            "\\b(?:INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|FROM|JOIN)\\s+([a-z][a-z0-9_]*)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Strips SQL line comments from a function body so that
     * commented-out statements cannot inject phantom table references into
     * the table scan.
     */
    private static String stripLineComments(String definition) {
        return definition.replaceAll("(?m)^.*--.*$", "");
    }

    /**
     * Resolves the directory path holding the stored procedure sources.
     */
    private static Path dbscriptsDir() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("packaging/dbscripts"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException(
                    "Cannot locate packaging/dbscripts above " + System.getProperty("user.dir"));
        }
        return dir.resolve("packaging/dbscripts");
    }

    /**
     * Matches a complete {@code CREATE OR REPLACE FUNCTION} declaration with a
     * dollar-quoted body: group 1 is the function name, group 2 the parameter
     * list, group 3 the dollar-quote tag and group 4 the function body. The
     * parameter-list pattern allows one level of nested parentheses so that
     * modifiers such as {@code numeric(18,9)} do not terminate the list.
     */
    private static final Pattern FUNCTION_PATTERN = Pattern.compile(
            "(?is)\\bcreate\\s+(?:or\\s+replace\\s+)?function\\s+"
            + "([a-z][a-z0-9_]*)\\s*"
            + "\\(([^)]*(?:\\([^)]*\\)[^)]*)*)\\)\\s*"
            + "(?:returns\\s+[^$]*?)?\\s*as\\s*"
            + "(\\$[a-z0-9_]*\\$)(.*?)\\3");

    /**
     * Matches a trailing length/precision modifier, e.g. the {@code (18,9)} of
     * {@code numeric(18,9)}.
     */
    private static final Pattern MODIFIER_AT_END = Pattern.compile("\\(([^)]*)\\)$");

    /**
     * Matches a single parameter declaration, e.g.
     * v_name character varying(128). Accepts the in/inout parameter
     * modes; out-only parameters are not caller-supplied and are left
     * unmatched on purpose. Leading whitespace is tolerated because the
     * parameter list is split on commas from a multi-line signature.
     * An optional DEFAULT clause is swallowed but not captured, so that
     * declarations like v_timezone VARCHAR(300) DEFAULT NULL are still
     * checked.
     */
    private static final Pattern PARAMETER_DECLARATION_PATTERN = Pattern.compile(
            "(?i)\\s*(?:in(?:out)?\\s+)?(v_[a-z0-9_]+)\\s+"
            + "([a-z][a-z0-9_]*(?:\\s+(?!default\\b)[a-z][a-z0-9_]*)*(?:\\([^)]*\\))?)"
            + "(?:\\s+default\\b.*)?"
            + "\\s*");

    /**
     * Normalizes the type spellings used in the SQL sources to the spellings
     * produced, which is how column types are reported
     * by the database.
     */
    private static final Map<String, String> BASE_TYPE_ALIASES = buildBaseTypeAliases();

    /**
     * Ordering within the integer family, used to tell a narrowing
     * (data-loss risk) from a widening (harmless) in the failure report.
     */
    private static final Map<String, Integer> INTEGER_WIDTH = Map.of(
            "smallint", 1,
            "integer", 2,
            "bigint", 3);

    @Test
    public void storedProcedureParameterTypesMatchColumnTypes() {
        Map<String, Map<String, String>> schema = loadColumnTypes();
        List<Mismatch> mismatches = new ArrayList<>();
        int checkedParameters = 0;

        for (StoredProcedure storedProcedure : loadDeclaredProcedures()) {
            if (storedProcedure.name.startsWith(HELPER_FUNCTION_PREFIX)) {
                continue;
            }
            Set<String> tables = referencedTables(storedProcedure.definition, schema.keySet());
            for (Map.Entry<String, String> argument : storedProcedure.arguments.entrySet()) {
                ColumnRef expected = resolveType(columnName(argument.getKey()), tables, schema);
                String found = argument.getValue();
                if (expected == null) {
                    continue;
                }
                checkedParameters++;
                if (!found.equals(expected.type)) {
                    mismatches.add(new Mismatch(storedProcedure.name,
                        argument.getKey(),
                        found,
                        expected));
                }
            }
        }
        assertTrue(checkedParameters > 0, "No parameters were checked");
        assertTrue(mismatches.isEmpty(), () -> render(mismatches));
    }

    /**
     * @return columns of base tables in the schema as
     *         table -> column -> full type with length/precision
     *         modifiers
     */
    private Map<String, Map<String, String>> loadColumnTypes() {
        String sql = "SELECT c.relname, a.attname, format_type(a.atttypid, a.atttypmod)"
                + " FROM pg_attribute a"
                + " JOIN pg_class c ON c.oid = a.attrelid"
                + " WHERE c.relnamespace = 'public'::regnamespace"
                + "   AND c.relkind = 'r'"
                + "   AND a.attnum > 0"
                + "   AND NOT a.attisdropped";
        Map<String, Map<String, String>> schema = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            schema.computeIfAbsent(rs.getString(1).toLowerCase(),
                    table -> new HashMap<>())
                    .put(rs.getString(2).toLowerCase(), rs.getString(3));
        });
        return schema;
    }

    /**
     * Parses the declared parameter types of every function in
     * stored procedures with their length/precision modifiers,
     * and resolves the tables each function body touches so that
     * only checkable parameters are kept.
     */
    private List<StoredProcedure> loadDeclaredProcedures() {
        Path dbscriptsDir = dbscriptsDir();
        List<StoredProcedure> storedProcedures = new ArrayList<>();
        try (Stream<Path> files = Files.list(dbscriptsDir)) {
            files.filter(p -> p.getFileName().toString().endsWith("_sp.sql"))
                    .sorted()
                    .forEach(file -> parseFile(file, storedProcedures));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + dbscriptsDir, e);
        }
        return storedProcedures;
    }

    private void parseFile(Path file, List<StoredProcedure> storedProcedures) {
        String content;
        try {
            content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
        Matcher functions = FUNCTION_PATTERN.matcher(content);
        while (functions.find()) {
            String name = functions.group(1);
            String body = functions.group(4);
            StoredProcedure storedProcedure =
                    new StoredProcedure(name, body);
            for (String declaration : splitParameterList(functions.group(2))) {
                Matcher parameter = PARAMETER_DECLARATION_PATTERN.matcher(declaration);
                if (parameter.matches()) {
                    storedProcedure.addArgument(
                            parameter.group(1),
                            normalizeDeclaredType(parameter.group(2)));
                }
            }
            if (!storedProcedure.arguments.isEmpty()) {
                storedProcedures.add(storedProcedure);
            }
        }
    }

    /**
     * Splits a parameter list on commas that are not inside parentheses, so
     * that modifiers like {@code numeric(18,9)} survive as one declaration.
     */
    private static List<String> splitParameterList(String parameterList) {
        List<String> declarations = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (char c : parameterList.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                declarations.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            declarations.add(current.toString());
        }
        return declarations;
    }

    /**
     * Rewrites a declared parameter type into the exact spelling used
     * for the equivalent column type, so both sides of the comparison can
     * be compared as strings. Types without a known alias are passed
     * through lower-cased.
     */
    private static String normalizeDeclaredType(String declaredType) {
        String type = declaredType.trim().toLowerCase();
        String base = MODIFIER_AT_END.matcher(type).replaceFirst("").trim();
        String modifier = type.equals(base) ? "" : type.substring(base.length());
        String canonicalBase = BASE_TYPE_ALIASES.getOrDefault(base, base);
        return canonicalBase + modifier;
    }

    private static Map<String, String> buildBaseTypeAliases() {
        Map<String, String> aliases = new HashMap<>();
        // character family
        aliases.put("character varying", "character varying");
        aliases.put("varchar", "character varying");
        aliases.put("character", "character");
        aliases.put("char", "character");
        aliases.put("bpchar", "character");
        // integer family
        aliases.put("smallint", "smallint");
        aliases.put("int2", "smallint");
        aliases.put("integer", "integer");
        aliases.put("int", "integer");
        aliases.put("int4", "integer");
        aliases.put("bigint", "bigint");
        aliases.put("int8", "bigint");
        // numeric / float family
        aliases.put("numeric", "numeric");
        aliases.put("decimal", "numeric");
        aliases.put("real", "real");
        aliases.put("float4", "real");
        aliases.put("double precision", "double precision");
        aliases.put("float8", "double precision");
        return aliases;
    }

    /**
     * Strips the v_ parameter prefix.
     */
    private static String columnName(String parameterName) {
        String name = parameterName.toLowerCase();
        return name.startsWith("v_") ? name.substring(2) : name;
    }

    private static Set<String> referencedTables(String definition, Set<String> knownTables) {
        Set<String> tables = new HashSet<>();
        Matcher matcher = TABLE_REF_PATTERN.matcher(stripLineComments(definition));
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
