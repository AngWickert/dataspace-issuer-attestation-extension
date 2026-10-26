/*
 *  Copyright (c) 2025 Fraunhofer Institute for Energy Economics and Energy System Technology (IEE)
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       Fraunhofer IEE - initial API and implementation
 *
 */

package de.fraunhofer.iee.issuer.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.fraunhofer.iee.issuer.api.model.ColumnDto;
import org.eclipse.edc.issuerservice.spi.issuance.model.MappingDefinition;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.transaction.datasource.spi.DataSourceRegistry;
import org.eclipse.edc.transaction.spi.TransactionContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;

public class DatabaseService {

    private final DataSourceRegistry dataSourceRegistry;
    private final String dataSourceName;
    private final TransactionContext transactionContext;
    private final ObjectMapper objectMapper;
    private final Monitor monitor;

    public DatabaseService(DataSourceRegistry dataSourceRegistry,
                           String dataSourceName,
                           TransactionContext transactionContext,
                           Monitor monitor) {
        this.dataSourceRegistry = dataSourceRegistry;
        this.dataSourceName = dataSourceName;
        this.transactionContext = transactionContext;
        this.objectMapper = new ObjectMapper();
        this.monitor = monitor;
    }

    public Result<List<Map<String, Object>>> queryDataAttestations(String tableName) {
        // PostgreSQL converts unquoted identifiers to lowercase
        final String normalizedTableName = tableName.toLowerCase();

        // Validate table exists, prevents SQL Injection
        var validationResult = validateTableAndGetColumns(normalizedTableName);
        if (validationResult.failed()) {
            return Result.failure(validationResult.getFailureDetail());
        }

        return transactionContext.execute(() -> {
            DataSource dataSource = dataSourceRegistry.resolve(dataSourceName);
            if (dataSource == null) {
                return Result.failure("DataSource not found: " + dataSourceName);
            }

            try (var connection = dataSource.getConnection();
                    var statement = connection.createStatement();
                    // Identifiers (table/column names) cannot use PreparedStatement placeholders in PostgreSQL.
                    // tableName validated via validateTableAndGetColumns
                    var resultSet = statement.executeQuery("SELECT * FROM " + normalizedTableName)) {

                var results = new ArrayList<Map<String, Object>>();
                while (resultSet.next()) {
                    results.add(mapGenericResult(resultSet));
                }
                return Result.success(results);

            } catch (SQLException e) {
                monitor.warning("SQL error querying table " + normalizedTableName + ": " + e.getMessage());
                return Result.failure("Failed to query table");
            }
        });
    }

    public Result<List<String>> validateTableAndGetColumns(String tableName) {
        // PostgreSQL converts unquoted identifiers to lowercase
        final String normalizedTableName = tableName.toLowerCase();

        return transactionContext.execute(() -> {
            DataSource dataSource = dataSourceRegistry.resolve(dataSourceName);
            if (dataSource == null) {
                return Result.failure("DataSource not found: " + dataSourceName);
            }

            try (var connection = dataSource.getConnection()) {
                var metaData = connection.getMetaData();
                var columns = metaData.getColumns(null, null, normalizedTableName, null);

                var allowedColumns = new ArrayList<String>();
                while (columns.next()) {
                    var columnName = columns.getString("COLUMN_NAME");
                    allowedColumns.add(columnName);
                }

                if (allowedColumns.isEmpty()) {
                    return Result.failure("Table does not exist: " + normalizedTableName);
                }

                return Result.success(allowedColumns);

            } catch (SQLException e) {
                return Result.failure("Failed to validate table " + normalizedTableName + ": " + e.getMessage());
            }
        });
    }

    public Result<String> createTable(String tableName, List<Map<String, Object>> columns) {
        if (columns == null || columns.isEmpty()) {
            return Result.failure("No columns provided");
        }
        if (tableName == null || tableName.isEmpty()) {
            return Result.failure("Table name cannot be empty");
        }

        // PostgreSQL converts unquoted identifiers to lowercase
        final String normalizedTableName = tableName.toLowerCase();

        // Validate table name to prevent SQL Injection
        if (!isValidDbKey(normalizedTableName)) {
            return Result.failure("Invalid table name: " + normalizedTableName);
        }

        return transactionContext.execute(() -> {
            DataSource dataSource = dataSourceRegistry.resolve(dataSourceName);
            if (dataSource == null) {
                return Result.failure("DataSource not found: " + dataSourceName);
            }

            try (var connection = dataSource.getConnection()) {
                var columnDefinitions = new ArrayList<String>();
                var primaryKeys = new ArrayList<String>();

                for (var column : columns) {
                    var name = (String) column.get("name");
                    var dataType = (String) column.get("data_type");
                    var nullable = column.get("nullable") != null ? (Boolean) column.get("nullable") : true;
                    var primaryKey = column.get("primary_key") != null ? (Boolean) column.get("primary_key") : false;
                    var defaultValue = (String) column.get("default");

                    // Validate column name to prevent SQL Injection
                    if (!isValidDbKey(name)) {
                        return Result.failure("Invalid column name: " + name);
                    }

                    // Validate data type to prevent SQL Injection
                    if (!isValidDataType(dataType)) {
                        return Result.failure("Invalid data type: " + dataType);
                    }

                    var columnDef = new StringBuilder(name + " " + dataType);

                    if (!nullable) {
                        columnDef.append(" NOT NULL");
                    }

                    if (defaultValue != null && !defaultValue.isEmpty()) {
                        // Validate default value contains only safe characters/functions
                        if (!isValidDefaultValue(defaultValue)) {
                            return Result.failure("Invalid default value for column " + name + ": " + defaultValue);
                        }
                        columnDef.append(" DEFAULT ").append(defaultValue);
                    }

                    columnDefinitions.add(columnDef.toString());

                    if (primaryKey) {
                        primaryKeys.add(name);
                    }
                }

                if (!primaryKeys.isEmpty()) {
                    columnDefinitions.add("PRIMARY KEY (" + String.join(", ", primaryKeys) + ")");
                }

                // Identifiers (table/column names) cannot use PreparedStatement placeholders in PostgreSQL.
                // tableName validated via isValidDbKey()
                var sql = "CREATE TABLE " + normalizedTableName + " ( " +
                        String.join(", ", columnDefinitions) + ")";

                try (var statement = connection.createStatement()) {
                    statement.executeUpdate(sql);
                }

                monitor.info("User performed CREATE TABLE on " + normalizedTableName);
                return Result.success("Successfully created table: " + normalizedTableName);

            } catch (SQLException e) {
                monitor.warning("SQL error querying table " + normalizedTableName + ": " + e.getMessage());
                return Result.failure("Failed to create table");
            }
        });
    }

    public Result<String> alterTable(String tableName, Collection<ColumnDto> columns) {
        // PostgreSQL converts unquoted identifiers to lowercase
        final String normalizedTableName = tableName.toLowerCase();

        // SECURITY: Validate table name to prevent SQL Injection
        if (!isValidDbKey(normalizedTableName)) {
            return Result.failure("Invalid table name: " + normalizedTableName);
        }

        StringBuilder sql = new StringBuilder("ALTER TABLE " + normalizedTableName);
        var primaryKeys = new ArrayList<String>();
        var first = true;

        for (var column : columns) {
            var name = column.columnName();
            var dataType = column.dataType();
            var defaultValue = column.defaultValue();
            boolean nullable = Boolean.TRUE.equals(column.isNullable());
            boolean primaryKey = Boolean.TRUE.equals(column.isPrimaryKey());

            // Validate column name to prevent SQL Injection
            if (!isValidDbKey(name)) {
                return Result.failure("Invalid column name: " + name);
            }

            // Validate data type to prevent SQL Injection
            if (!isValidDataType(dataType)) {
                return Result.failure("Invalid data type: " + dataType);
            }

            if (first) {
                sql.append(" ADD COLUMN ").append(name).append(" ").append(dataType);
                first = false;
            } else {
                sql.append(", ADD COLUMN ").append(name).append(" ").append(dataType);
            }

            if (!nullable) {
                sql.append(" NOT NULL");
            }

            if (defaultValue != null && !defaultValue.isEmpty()) {
                // Validate default value contains only safe characters/functions
                if (!isValidDefaultValue(defaultValue)) {
                    return Result.failure("Invalid default value for column " + name + ": " + defaultValue);
                }
                sql.append(" DEFAULT ").append(defaultValue);
            }

            if (primaryKey) {
                primaryKeys.add(name);
            }
        }

        return transactionContext.execute(() -> {
            DataSource dataSource = dataSourceRegistry.resolve(dataSourceName);
            if (dataSource == null) {
                return Result.failure("DataSource not found: " + dataSourceName);
            }

            try (var connection = dataSource.getConnection()) {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate(String.valueOf(sql));
                }

                if (!primaryKeys.isEmpty()) {
                    for (String primaryKey : primaryKeys) {
                        StringBuilder psql = new StringBuilder("ALTER TABLE " + normalizedTableName);
                        psql.append(" ADD CONSTRAINT pk_")
                                .append(primaryKey)
                                .append(" PRIMARY KEY (")
                                .append(primaryKey)
                                .append(")");
                        try (var statement = connection.createStatement()) {
                            statement.executeUpdate(psql.toString());
                        }

                    }
                }

                monitor.info("User performed ALTER TABLE on " + normalizedTableName);
                return Result.success("Successfully altered table: " + normalizedTableName);

            } catch (SQLException e) {
                monitor.warning("SQL error querying table " + normalizedTableName + ": " + e.getMessage());
                return Result.failure("Failed to alter table");
            }
        });
    }

    public boolean isValidIdentifier(String identifier) {
        // Only allow alphanumeric characters and underscores, must start with letter or underscore
        return identifier != null && identifier.matches("^[a-zA-Z_][a-zA-Z0-9_-]*$");
    }

    public boolean isValidDbKey(String dbKey) {
        if (dbKey.length() > 63) { // PostgreSQL max identifier length
            return false;
        }
        return dbKey.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
    }

    private boolean isValidDataType(String dataType) {
        // Whitelist of allowed PostgreSQL data types
        var allowedTypes = List.of(
                "integer", "numeric",
                "varchar", "boolean",
                "date", "timestamptz",
                "uuid", "json"
        );
        return dataType != null && allowedTypes.stream()
                .anyMatch(type -> dataType.toLowerCase().startsWith(type));
    }

    private boolean isValidDefaultValue(String defaultValue) {
        // Allow safe default values:
        // - Function calls like gen_random_uuid(), now(), current_timestamp
        // - Numeric values
        // - String literals in single quotes
        // - Boolean values (true, false)
        // - NULL
        return defaultValue.matches("^(?:" +
                "[a-zA-Z_][a-zA-Z0-9_]*\\(\\)|" +   // Functions like gen_random_uuid()
                "-?\\d+(\\.\\d+)?|" +               // Numeric values
                "'[a-zA-Z0-9_\\s-]*'|" +            // String literals
                "true|false|" +                     // Boolean
                "NULL" +                            // NULL
                ")$");
    }

    public boolean isValidValue(String value) {
        // Allow safe data values for user input (more restrictive than isValidDefaultValue):
        // - Numeric values
        // - String literals
        // - Boolean values (true, false)
        // - NULL
        if (value == null) {
            return true; // null is valid
        }
        return value.matches("^(?:" +
                "-?\\d+(\\.\\d+)?|" +               // Numeric values
                "[a-zA-Z0-9_.:@#$%&*+/-]*|" +     // String literals without spaces
                "true|false|" +                     // Boolean
                "NULL" +                            // NULL
                ")$");
    }

    private Result<String> validateDataType(String columnName, Object value, String dataType) {
        try {
            switch (dataType) {
                case "INTEGER", "INT4", "INT8", "BIGINT", "SMALLINT":
                    if (!(value instanceof Integer) && !(value instanceof Long) && !(value instanceof Short)) {
                        try {
                            Integer.parseInt(value.toString());
                        } catch (NumberFormatException e) {
                            return Result.failure("Column '" + columnName + "' expects INTEGER type, but got: " + value.getClass().getSimpleName());
                        }
                    }
                    break;

                case "NUMERIC", "DECIMAL", "FLOAT4", "FLOAT8", "REAL", "DOUBLE PRECISION":
                    if (!(value instanceof Number)) {
                        try {
                            Double.parseDouble(value.toString());
                        } catch (NumberFormatException e) {
                            return Result.failure("Column '" + columnName + "' expects NUMERIC type, but got: " + value.getClass().getSimpleName());
                        }
                    }
                    break;

                case "VARCHAR", "TEXT", "CHAR", "CHARACTER VARYING":
                    if (!(value instanceof String)) {
                        return Result.failure("Column '" + columnName + "' expects VARCHAR type, but got: " + value.getClass().getSimpleName());
                    }
                    if (!isValidValue(value.toString())) {
                        return Result.failure("Column '" + columnName + "' expects valid value, but got: " + value);
                    }
                    break;

                case "BOOLEAN", "BOOL":
                    if (!(value instanceof Boolean)) {
                        var strValue = value.toString().toLowerCase();
                        if (!strValue.equals("true") && !strValue.equals("false")) {
                            return Result.failure("Column '" + columnName + "' expects BOOLEAN type, but got: " + value.getClass().getSimpleName());
                        }
                    }
                    break;

                case "DATE":
                    if (!(value instanceof java.sql.Date) && !(value instanceof java.time.LocalDate)) {
                        // Try to parse as ISO date string
                        try {
                            java.time.LocalDate.parse(value.toString());
                        } catch (Exception e) {
                            return Result.failure("Column '" + columnName + "' expects DATE type (ISO format: YYYY-MM-DD), but got: " + value);
                        }
                    }
                    break;

                case "TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE", "TIMESTAMP":
                    if (!(value instanceof java.sql.Timestamp) && !(value instanceof java.time.Instant) &&
                            !(value instanceof java.time.ZonedDateTime) && !(value instanceof java.time.LocalDateTime)) {
                        // Try to parse as timestamp string (multiple formats)
                        try {
                            parseTimestamp(value.toString());
                        } catch (Exception e) {
                            return Result.failure("Column '" + columnName + "' expects TIMESTAMP type (formats: 'YYYY-MM-DDTHH:MM:SS', 'YYYY-MM-DD HH:MM:SS'), but got: " + value);
                        }
                    }
                    break;

                case "UUID":
                    if (!(value instanceof java.util.UUID)) {
                        // Try to parse as UUID string
                        try {
                            java.util.UUID.fromString(value.toString());
                        } catch (IllegalArgumentException e) {
                            return Result.failure("Column '" + columnName + "' expects UUID type, but got invalid UUID: " + value);
                        }
                    }
                    break;

                case "JSON", "JSONB":
                    // JSON can be any object, Map, List, or String
                    // Validation happens during serialization in setDataAttestations
                    break;

                default:
                    // Unknown type - log warning but don't fail
                    monitor.warning("Unknown data type '" + dataType + "' for column '" + columnName + "'. Skipping validation.");
                    break;
            }
            return Result.success("Valid");
        } catch (Exception e) {
            return Result.failure("Validation error for column '" + columnName + "': " + e.getMessage());
        }
    }

    private java.sql.Timestamp parseTimestamp(String value) {
        // Try different timestamp formats
        try {
            // Try ISO format with 'T': "2026-10-26T10:44:55" or "2026-10-26T10:44:55Z"
            return java.sql.Timestamp.from(java.time.Instant.parse(value));
        } catch (Exception e1) {
            try {
                // Try SQL format with space: "2026-10-26 10:44:55"
                var formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                var localDateTime = java.time.LocalDateTime.parse(value, formatter);
                return java.sql.Timestamp.valueOf(localDateTime);
            } catch (Exception e2) {
                try {
                    // Try SQL format with microseconds: "2026-10-26 10:44:55.123456"
                    var formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
                    var localDateTime = java.time.LocalDateTime.parse(value, formatter);
                    return java.sql.Timestamp.valueOf(localDateTime);
                } catch (Exception e3) {
                    try {
                        // Try ISO format without timezone: "2026-10-26T10:44:55"
                        var localDateTime = java.time.LocalDateTime.parse(value);
                        return java.sql.Timestamp.valueOf(localDateTime);
                    } catch (Exception e4) {
                        throw new IllegalArgumentException("Unable to parse timestamp: " + value);
                    }
                }
            }
        }
    }

    public Result<String> setDataAttestations(String tableName, Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return Result.failure("No data provided");
        }

        // PostgreSQL converts unquoted identifiers to lowercase
        final String normalizedTableName = tableName.toLowerCase();

        // Get table schema to determine column types
        var schemaResult = getTableSchema(normalizedTableName);
        if (schemaResult.failed()) {
            return Result.failure(schemaResult.getFailureDetail());
        }

        var schema = schemaResult.getContent();
        var allowedColumns = new ArrayList<>(schema.columns().keySet());

        // Check if all data columns are allowed, prevents SQL Injection
        for (var columnName : data.keySet()) {
            if (!allowedColumns.contains(columnName)) {
                return Result.failure("Column '" + columnName + "' does not exist in table " + tableName);
            }
        }

        // Validate data types
        for (var entry : data.entrySet()) {
            var columnName = entry.getKey();
            var value = entry.getValue();
            var columnInfo = schema.columns().get(columnName);
            var dataType = columnInfo.dataType().toUpperCase();

            if (value == null) {
                // Null is valid for nullable columns
                if (!columnInfo.isNullable()) {
                    return Result.failure("Column '" + columnName + "' cannot be null");
                }
                continue;
            }

            // Validate data type
            var validationResult = validateDataType(columnName, value, dataType);
            if (validationResult.failed()) {
                return validationResult;
            }
        }

        return transactionContext.execute(() -> {
            DataSource dataSource = dataSourceRegistry.resolve(dataSourceName);
            if (dataSource == null) {
                return Result.failure("DataSource not found: " + dataSourceName);
            }

            try (var connection = dataSource.getConnection()) {
                var columns = new ArrayList<>(data.keySet());
                var placeholders = String.join(", ", columns.stream()
                        .map(c -> "?")
                        .toList());
                var columnNames = String.join(", ", columns);

                // Identifiers (table/column names) cannot use PreparedStatement placeholders in PostgreSQL.
                // tableName validated via loop over allowedColumns and getTableSchema
                var sql = "INSERT INTO " + normalizedTableName + " (" + columnNames + ") VALUES (" + placeholders + ")";

                // preparedStatement prevents SQL Injection via the values
                try (var preparedStatement = connection.prepareStatement(sql)) {
                    for (int i = 0; i < columns.size(); i++) {
                        var columnName = columns.get(i);
                        var value = data.get(columnName);
                        var columnInfo = schema.columns().get(columnName);
                        var dataType = columnInfo.dataType().toUpperCase();

                        // Parse and set value based on data type
                        if (value == null) {
                            preparedStatement.setObject(i + 1, null);
                        } else if (dataType.equals("JSON") || dataType.equals("JSONB")) {
                            // Handle JSON/JSONB columns - convert objects to JSON string
                            if (!(value instanceof String)) {
                                try {
                                    var jsonString = objectMapper.writeValueAsString(value);
                                    preparedStatement.setObject(i + 1, jsonString, Types.OTHER);
                                } catch (Exception e) {
                                    monitor.warning("Failed to serialize JSON for column '" + columnName + "': " + e.getMessage());
                                    return Result.failure("Failed to serialize JSON for column '" + columnName + "'");
                                }
                            } else {
                                preparedStatement.setObject(i + 1, value, Types.OTHER);
                            }
                        } else if (dataType.equals("INTEGER") || dataType.equals("INT4") || dataType.equals("SMALLINT")) {
                            preparedStatement.setInt(i + 1, value instanceof Integer ? (Integer) value : Integer.parseInt(value.toString()));
                        } else if (dataType.equals("BIGINT") || dataType.equals("INT8")) {
                            preparedStatement.setLong(i + 1, value instanceof Long ? (Long) value : Long.parseLong(value.toString()));
                        } else if (dataType.equals("NUMERIC") || dataType.equals("DECIMAL") || dataType.equals("FLOAT8") || dataType.equals("DOUBLE PRECISION")) {
                            preparedStatement.setDouble(i + 1, value instanceof Double ? (Double) value : Double.parseDouble(value.toString()));
                        } else if (dataType.equals("FLOAT4") || dataType.equals("REAL")) {
                            preparedStatement.setFloat(i + 1, value instanceof Float ? (Float) value : Float.parseFloat(value.toString()));
                        } else if (dataType.equals("BOOLEAN") || dataType.equals("BOOL")) {
                            preparedStatement.setBoolean(i + 1, value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(value.toString()));
                        } else if (dataType.equals("VARCHAR") || dataType.equals("TEXT") || dataType.equals("CHAR") || dataType.equals("CHARACTER VARYING")) {
                            preparedStatement.setString(i + 1, value.toString());
                        } else if (dataType.equals("DATE")) {
                            if (value instanceof java.sql.Date) {
                                preparedStatement.setDate(i + 1, (java.sql.Date) value);
                            } else if (value instanceof java.time.LocalDate) {
                                preparedStatement.setDate(i + 1, java.sql.Date.valueOf((java.time.LocalDate) value));
                            } else {
                                preparedStatement.setDate(i + 1, java.sql.Date.valueOf(java.time.LocalDate.parse(value.toString())));
                            }
                        } else if (dataType.equals("TIMESTAMPTZ") || dataType.equals("TIMESTAMP WITH TIME ZONE") || dataType.equals("TIMESTAMP")) {
                            if (value instanceof java.sql.Timestamp) {
                                preparedStatement.setTimestamp(i + 1, (java.sql.Timestamp) value);
                            } else if (value instanceof java.time.Instant) {
                                preparedStatement.setTimestamp(i + 1, java.sql.Timestamp.from((java.time.Instant) value));
                            } else if (value instanceof java.time.ZonedDateTime) {
                                preparedStatement.setTimestamp(i + 1, java.sql.Timestamp.from(((java.time.ZonedDateTime) value).toInstant()));
                            } else if (value instanceof java.time.LocalDateTime) {
                                preparedStatement.setTimestamp(i + 1, java.sql.Timestamp.valueOf((java.time.LocalDateTime) value));
                            } else {
                                preparedStatement.setTimestamp(i + 1, parseTimestamp(value.toString()));
                            }
                        } else if (dataType.equals("UUID")) {
                            if (value instanceof java.util.UUID) {
                                preparedStatement.setObject(i + 1, value, Types.OTHER);
                            } else {
                                preparedStatement.setObject(i + 1, java.util.UUID.fromString(value.toString()), Types.OTHER);
                            }
                        } else {
                            // Fallback for unknown types
                            preparedStatement.setObject(i + 1, value);
                        }
                    }

                    preparedStatement.executeUpdate();
                }

                return Result.success("Successfully inserted 1 row into " + normalizedTableName);

            } catch (SQLException e) {
                monitor.warning("SQL error querying table " + normalizedTableName + ": " + e.getMessage());
                return Result.failure("Failed to insert data to table");
            }
        });
    }

    public Result<String> updateDataAttestations(String tableName, Map<String, String> ids, Map<String, Object> data) {

        if (ids == null || ids.isEmpty()) {
            return Result.failure("No ids provided");
        }

        if (data == null || data.isEmpty()) {
            return Result.failure("No data provided");
        }

        // PostgreSQL converts unquoted identifiers to lowercase
        final String normalizedTableName = tableName.toLowerCase();

        // Get table schema to determine column types and primary keys
        Result<TableSchemaResult> schemaResult = getTableSchema(normalizedTableName);

        if (schemaResult.failed()) {
            return Result.failure(schemaResult.getFailureDetail());
        }

        var schema = schemaResult.getContent();
        var allowedColumns = new ArrayList<>(schema.columns().keySet());
        var primaryKeys = schema.primaryKeys();

        // Check if all data columns are allowed, prevents SQL Injection
        for (var columnName : data.keySet()) {
            if (!allowedColumns.contains(columnName)) {
                return Result.failure("Column '" + columnName + "' does not exist in table " + tableName);
            }
        }

        // Check if all id columns are primary keys, prevents SQL Injection
        for (var idColumn : ids.keySet()) {
            if (!primaryKeys.contains(idColumn)) {
                return Result.failure("Column '" + idColumn + "' is not a primary key in table " + tableName);
            }
        }

        // Validate data types
        for (var entry : data.entrySet()) {
            var columnName = entry.getKey();
            var value = entry.getValue();
            var columnInfo = schema.columns().get(columnName);
            var dataType = columnInfo.dataType().toUpperCase();

            if (value == null) {
                // Null is valid for nullable columns
                if (!columnInfo.isNullable()) {
                    return Result.failure("Column '" + columnName + "' cannot be null");
                }
                continue;
            }

            // Validate data type
            var validationResult = validateDataType(columnName, value, dataType);
            if (validationResult.failed()) {
                return validationResult;
            }
        }

        // Remove primary keys from data to prevent them from being updated
        var filteredData = new LinkedHashMap<String, Object>();
        for (var entry : data.entrySet()) {
            if (!primaryKeys.contains(entry.getKey())) {
                filteredData.put(entry.getKey(), entry.getValue());
            }
        }

        if (filteredData.isEmpty()) {
            return Result.failure("No updatable columns provided (primary keys cannot be updated)");
        }

        return transactionContext.execute(() -> {
            DataSource dataSource = dataSourceRegistry.resolve(dataSourceName);
            if (dataSource == null) {
                return Result.failure("DataSource not found: " + dataSourceName);
            }

            try (var connection = dataSource.getConnection()) {
                var idColumns = new ArrayList<>(ids.keySet());
                var dataColumns = new ArrayList<>(filteredData.keySet());

                // Build SET clause with commas (not AND)
                StringBuilder setClause = new StringBuilder();
                for (int i = 0; i < dataColumns.size(); i++) {
                    if (i > 0) {
                        setClause.append(", ");
                    }
                    setClause.append(dataColumns.get(i)).append(" = ?");
                }

                // Build WHERE clause
                StringBuilder whereClause = new StringBuilder();
                for (int i = 0; i < idColumns.size(); i++) {
                    if (i > 0) {
                        whereClause.append(" AND ");
                    }
                    whereClause.append(idColumns.get(i)).append(" = ?");
                }

                var sql = "UPDATE " + normalizedTableName + " SET " + setClause + " WHERE " + whereClause;

                // preparedStatement prevents SQL Injection via the values
                try (var preparedStatement = connection.prepareStatement(sql)) {
                    int paramIndex = 1;

                    // Set data values for SET clause
                    for (var columnName : dataColumns) {
                        var value = filteredData.get(columnName);
                        var columnInfo = schema.columns().get(columnName);
                        var dataType = columnInfo.dataType().toUpperCase();

                        // Parse and set value based on data type
                        if (value == null) {
                            preparedStatement.setObject(paramIndex++, null);
                        } else if (dataType.equals("JSON") || dataType.equals("JSONB")) {
                            // Handle JSON/JSONB columns - convert objects to JSON string
                            if (!(value instanceof String)) {
                                try {
                                    var jsonString = objectMapper.writeValueAsString(value);
                                    preparedStatement.setObject(paramIndex++, jsonString, Types.OTHER);
                                } catch (Exception e) {
                                    return Result.failure("Failed to serialize JSON for column '" + columnName + "': " + e.getMessage());
                                }
                            } else {
                                preparedStatement.setObject(paramIndex++, value, Types.OTHER);
                            }
                        } else if (dataType.equals("INTEGER") || dataType.equals("INT4") || dataType.equals("SMALLINT")) {
                            preparedStatement.setInt(paramIndex++, value instanceof Integer ? (Integer) value : Integer.parseInt(value.toString()));
                        } else if (dataType.equals("BIGINT") || dataType.equals("INT8")) {
                            preparedStatement.setLong(paramIndex++, value instanceof Long ? (Long) value : Long.parseLong(value.toString()));
                        } else if (dataType.equals("NUMERIC") || dataType.equals("DECIMAL") || dataType.equals("FLOAT8") || dataType.equals("DOUBLE PRECISION")) {
                            preparedStatement.setDouble(paramIndex++, value instanceof Double ? (Double) value : Double.parseDouble(value.toString()));
                        } else if (dataType.equals("FLOAT4") || dataType.equals("REAL")) {
                            preparedStatement.setFloat(paramIndex++, value instanceof Float ? (Float) value : Float.parseFloat(value.toString()));
                        } else if (dataType.equals("BOOLEAN") || dataType.equals("BOOL")) {
                            preparedStatement.setBoolean(paramIndex++, value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(value.toString()));
                        } else if (dataType.equals("VARCHAR") || dataType.equals("TEXT") || dataType.equals("CHAR") || dataType.equals("CHARACTER VARYING")) {
                            preparedStatement.setString(paramIndex++, value.toString());
                        } else if (dataType.equals("DATE")) {
                            if (value instanceof java.sql.Date) {
                                preparedStatement.setDate(paramIndex++, (java.sql.Date) value);
                            } else if (value instanceof java.time.LocalDate) {
                                preparedStatement.setDate(paramIndex++, java.sql.Date.valueOf((java.time.LocalDate) value));
                            } else {
                                preparedStatement.setDate(paramIndex++, java.sql.Date.valueOf(java.time.LocalDate.parse(value.toString())));
                            }
                        } else if (dataType.equals("TIMESTAMPTZ") || dataType.equals("TIMESTAMP WITH TIME ZONE") || dataType.equals("TIMESTAMP")) {
                            if (value instanceof java.sql.Timestamp) {
                                preparedStatement.setTimestamp(paramIndex++, (java.sql.Timestamp) value);
                            } else if (value instanceof java.time.Instant) {
                                preparedStatement.setTimestamp(paramIndex++, java.sql.Timestamp.from((java.time.Instant) value));
                            } else if (value instanceof java.time.ZonedDateTime) {
                                preparedStatement.setTimestamp(paramIndex++, java.sql.Timestamp.from(((java.time.ZonedDateTime) value).toInstant()));
                            } else if (value instanceof java.time.LocalDateTime) {
                                preparedStatement.setTimestamp(paramIndex++, java.sql.Timestamp.valueOf((java.time.LocalDateTime) value));
                            } else {
                                preparedStatement.setTimestamp(paramIndex++, parseTimestamp(value.toString()));
                            }
                        } else if (dataType.equals("UUID")) {
                            if (value instanceof java.util.UUID) {
                                preparedStatement.setObject(paramIndex++, value, Types.OTHER);
                            } else {
                                preparedStatement.setObject(paramIndex++, java.util.UUID.fromString(value.toString()), Types.OTHER);
                            }
                        } else {
                            // Fallback for unknown types
                            preparedStatement.setObject(paramIndex++, value);
                        }
                    }

                    // Set id values for WHERE clause
                    for (var idColumn : idColumns) {
                        var value = ids.get(idColumn);
                        preparedStatement.setObject(paramIndex++, value);
                    }

                    int updatedRows = preparedStatement.executeUpdate();
                    return Result.success("Successfully updated " + updatedRows + " row(s) in " + normalizedTableName);
                }

            } catch (SQLException e) {
                monitor.warning("SQL error querying table " + normalizedTableName + ": " + e.getMessage());
                return Result.failure("Failed to update data in table");
            }
        });
    }

    public Result<String> deleteDataAttestations(String tableName, Map<String, String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Result.failure("No ids provided");
        }

        // PostgreSQL converts unquoted identifiers to lowercase
        final String normalizedTableName = tableName.toLowerCase();

        // Validate table and get allowed columns, prevents SQL Injection
        var validationResult = validateTableAndGetColumns(normalizedTableName);
        if (validationResult.failed()) {
            return Result.failure(validationResult.getFailureDetail());
        }

        Result<TableSchemaResult> schemaResult = getTableSchema(normalizedTableName);

        if (schemaResult.failed()) {
            return Result.failure(schemaResult.getFailureDetail());
        }

        var schema = schemaResult.getContent();
        var primaryKeys = schema.primaryKeys();

        // Check if all id columns are primary keys, prevents SQL Injection
        for (var idColumn : ids.keySet()) {
            if (!primaryKeys.contains(idColumn)) {
                return Result.failure("Column '" + idColumn + "' is not a primary key in table " + normalizedTableName);
            }
        }

        return transactionContext.execute(() -> {
            DataSource dataSource = dataSourceRegistry.resolve(dataSourceName);
            if (dataSource == null) {
                return Result.failure("DataSource not found: " + dataSourceName);
            }

            try (var connection = dataSource.getConnection()) {
                var idColumns = new ArrayList<>(ids.keySet());

                StringBuilder sql = new StringBuilder("DELETE FROM " + normalizedTableName + " WHERE ");

                for (int i = 0; i < idColumns.size(); i++) {
                    if (i > 0) {
                        sql.append(" AND ");
                    }
                    sql.append(idColumns.get(i)).append(" = ?");
                }

                int deletedRows;
                // preparedStatement prevents SQL Injection via the values
                try (var preparedStatement = connection.prepareStatement(sql.toString())) {
                    for (int i = 0; i < idColumns.size(); i++) {
                        var idColumn = idColumns.get(i);
                        var value = ids.get(idColumn);
                        var columnInfo = schema.columns().get(idColumn);
                        var dataType = columnInfo.dataType().toUpperCase();

                        // Parse ID value based on data type
                        if (value == null) {
                            preparedStatement.setObject(i + 1, null);
                        } else if (dataType.equals("INTEGER") || dataType.equals("INT4") || dataType.equals("SMALLINT")) {
                            preparedStatement.setInt(i + 1, Integer.parseInt(value));
                        } else if (dataType.equals("BIGINT") || dataType.equals("INT8")) {
                            preparedStatement.setLong(i + 1, Long.parseLong(value));
                        } else if (dataType.equals("UUID")) {
                            preparedStatement.setObject(i + 1, java.util.UUID.fromString(value), Types.OTHER);
                        } else if (dataType.equals("VARCHAR") || dataType.equals("TEXT") || dataType.equals("CHAR") || dataType.equals("CHARACTER VARYING")) {
                            preparedStatement.setString(i + 1, value);
                        } else {
                            // Fallback: treat as string
                            preparedStatement.setString(i + 1, value);
                        }
                    }

                    deletedRows = preparedStatement.executeUpdate();
                }

                monitor.info("User performed DELETE from table " + normalizedTableName);
                return Result.success("Successfully deleted " + deletedRows + " row(s) from " + normalizedTableName);

            } catch (SQLException e) {
                monitor.warning("SQL error querying table " + normalizedTableName + ": " + e.getMessage());
                return Result.failure("Failed to delete data from table");
            }
        });


    }

    public Result<TableSchemaResult> getTableSchema(String tableName) {
        // PostgreSQL converts unquoted identifiers to lowercase
        final String normalizedTableName = tableName.toLowerCase();

        return transactionContext.execute(() -> {
            DataSource dataSource = dataSourceRegistry.resolve(dataSourceName);
            if (dataSource == null) {
                return Result.failure("DataSource not found: " + dataSourceName);
            }

            try (var connection = dataSource.getConnection()) {
                var metaData = connection.getMetaData();

                // Get column information - store with ordinal position for sorting
                var columns = metaData.getColumns(null, null, normalizedTableName, null);
                var columnMap = new HashMap<Integer, Map.Entry<String, ColumnInfo>>();

                while (columns.next()) {
                    var columnName = columns.getString("COLUMN_NAME");
                    var dataType = columns.getString("TYPE_NAME");
                    var isNullableString = columns.getString("IS_NULLABLE");
                    var isNullable = "YES".equalsIgnoreCase(isNullableString);
                    var defaultValue = columns.getString("COLUMN_DEF");
                    var ordinalPosition = columns.getInt("ORDINAL_POSITION");

                    columnMap.put(ordinalPosition, Map.entry(columnName, new ColumnInfo(dataType, isNullable, defaultValue)));
                }

                if (columnMap.isEmpty()) {
                    return Result.failure("Table not found or has no columns: " + normalizedTableName);
                }

                // Sort by ordinal position and build schema
                var schema = new LinkedHashMap<String, ColumnInfo>();
                columnMap.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> schema.put(entry.getValue().getKey(), entry.getValue().getValue()));

                // Get primary keys
                var primaryKeys = metaData.getPrimaryKeys(null, null, normalizedTableName);
                var pkList = new ArrayList<String>();
                while (primaryKeys.next()) {
                    var pkColumn = primaryKeys.getString("COLUMN_NAME");
                    pkList.add(pkColumn);
                }

                return Result.success(new TableSchemaResult(schema, pkList));

            } catch (SQLException e) {
                monitor.warning("SQL error querying table " + normalizedTableName + ": " + e.getMessage());
                return Result.failure("Failed to retrieve schema for table " + normalizedTableName);
            }
        });
    }

    public record ColumnInfo(String dataType, boolean isNullable, String defaultValue) {
    }

    public record TableSchemaResult(Map<String, ColumnInfo> columns, List<String> primaryKeys) {
    }

    private Map<String, Object> mapGenericResult(ResultSet resultSet) throws SQLException {
        var map = new HashMap<String, Object>();
        var metaData = resultSet.getMetaData();
        var cols = metaData.getColumnCount();
        for (int i = 1; i <= cols; i++) {
            var columnName = metaData.getColumnName(i);
            var columnType = metaData.getColumnTypeName(i);
            var value = resultSet.getObject(columnName);

            // Handle JSON/JSONB columns - parse string to object
            if (value != null && (columnType.equalsIgnoreCase("JSON") || columnType.equalsIgnoreCase("JSONB"))) {
                try {
                    var jsonString = value.toString();
                    value = objectMapper.readValue(jsonString, Object.class);
                } catch (Exception e) {
                    // If parsing fails, keep the original string value
                }
            }

            map.put(columnName, value);
        }
        return map;
    }

    public boolean hasMatchingColumnNames(Collection<MappingDefinition> mappings, Collection<ColumnDto> columns, String idColumn) {

        Set<String> inputs = mappings.stream()
                .map(MappingDefinition::input)
                .filter(input -> !Objects.equals(input, idColumn))
                .collect(Collectors.toSet());

        Set<String> columnNames = columns.stream()
                .map(ColumnDto::columnName)
                .filter(columnName -> !Objects.equals(columnName, "id") && !Objects.equals(columnName, idColumn))
                .collect(Collectors.toSet());

        return inputs.equals(columnNames);
    }

    public boolean hasValidMappingProperties(Collection<MappingDefinition> mappings) {
        for (MappingDefinition mapping : mappings) {
            if (!this.isValidIdentifier(mapping.input())) {
                return false;
            }

            String output = mapping.output();
            if (output == null || !output.matches("^credentialSubject\\.[a-zA-Z0-9_]+$")) {
                return false;
            }
        }
        return true;
    }

    public Collection<ColumnDto> removeIdColumn(Collection<ColumnDto> columns, String idColumn) {
        if (idColumn == null) {
            return List.copyOf(columns);
        }

        return columns.stream()
                .filter(column -> !idColumn.equals(column.columnName()))
                .toList();
    }

}
