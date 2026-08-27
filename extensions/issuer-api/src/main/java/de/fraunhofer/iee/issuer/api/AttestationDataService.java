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
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.transaction.datasource.spi.DataSourceRegistry;
import org.eclipse.edc.transaction.spi.TransactionContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

public class AttestationDataService {

    private final DataSourceRegistry dataSourceRegistry;
    private final String dataSourceName;
    private final TransactionContext transactionContext;
    private final ObjectMapper objectMapper;

    public AttestationDataService(DataSourceRegistry dataSourceRegistry,
                                  String dataSourceName,
                                  TransactionContext transactionContext) {
        this.dataSourceRegistry = dataSourceRegistry;
        this.dataSourceName = dataSourceName;
        this.transactionContext = transactionContext;
        this.objectMapper = new ObjectMapper();
    }

    public Result<List<Map<String, Object>>> queryDataAttestations(String tableName) {
        // Validate table exists, prevents SQL Injection
        var validationResult = validateTableAndGetColumns(tableName);
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
                    var resultSet = statement.executeQuery("SELECT * FROM " + tableName)) {

                var results = new ArrayList<Map<String, Object>>();
                while (resultSet.next()) {
                    results.add(mapGenericResult(resultSet));
                }
                return Result.success(results);

            } catch (SQLException e) {
                return Result.failure("Failed to query table " + tableName + ": " + e.getMessage());
            }
        });
    }

    public Result<List<String>> validateTableAndGetColumns(String tableName) {
        return transactionContext.execute(() -> {
            DataSource dataSource = dataSourceRegistry.resolve(dataSourceName);
            if (dataSource == null) {
                return Result.failure("DataSource not found: " + dataSourceName);
            }

            try (var connection = dataSource.getConnection()) {
                var metaData = connection.getMetaData();
                var columns = metaData.getColumns(null, null, tableName, null);

                var allowedColumns = new ArrayList<String>();
                while (columns.next()) {
                    var columnName = columns.getString("COLUMN_NAME");
                    allowedColumns.add(columnName);
                }

                if (allowedColumns.isEmpty()) {
                    return Result.failure("Table does not exist: " + tableName);
                }

                return Result.success(allowedColumns);

            } catch (SQLException e) {
                return Result.failure("Failed to validate table " + tableName + ": " + e.getMessage());
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

        // Validate table name to prevent SQL Injection
        if (!isValidIdentifier(tableName)) {
            return Result.failure("Invalid table name: " + tableName);
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
                    if (!isValidIdentifier(name)) {
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

                var sql = "CREATE TABLE " + tableName + " ( " +
                        String.join(", ", columnDefinitions) + ")";

                try (var statement = connection.createStatement()) {
                    statement.executeUpdate(sql);
                }

                return Result.success("Successfully created table: " + tableName);

            } catch (SQLException e) {
                return Result.failure("Failed to create table " + tableName + ": " + e.getMessage());
            }
        });
    }

    private boolean isValidIdentifier(String identifier) {
        // Only allow alphanumeric characters and underscores, must start with letter or underscore
        return identifier != null && identifier.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
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
                "[a-zA-Z_][a-zA-Z0-9_]*\\(\\)|" +  // Functions like gen_random_uuid()
                "-?\\d+(\\.\\d+)?|" +               // Numeric values
                "'[^']*'|" +                        // String literals
                "true|false|" +                     // Boolean
                "NULL" +                            // NULL
                ")$");
    }

    public Result<String> setDataAttestations(String tableName, Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return Result.failure("No data provided");
        }

        // Get table schema to determine column types
        var schemaResult = getTableSchema(tableName);
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

                var sql = "INSERT INTO " + tableName + " (" + columnNames + ") VALUES (" + placeholders + ")";

                // preparedStatement prevents SQL Injection via the values
                try (var preparedStatement = connection.prepareStatement(sql)) {
                    for (int i = 0; i < columns.size(); i++) {
                        var columnName = columns.get(i);
                        var value = data.get(columnName);
                        var columnInfo = schema.columns().get(columnName);
                        var dataType = columnInfo.dataType().toUpperCase();

                        // Handle JSON/JSONB columns - convert objects to JSON string
                        if ((dataType.equals("JSON") || dataType.equals("JSONB")) &&
                            value != null && !(value instanceof String)) {
                            try {
                                var jsonString = objectMapper.writeValueAsString(value);
                                preparedStatement.setObject(i + 1, jsonString, Types.OTHER);
                            } catch (Exception e) {
                                return Result.failure("Failed to serialize JSON for column '" + columnName + "': " + e.getMessage());
                            }
                        } else {
                            preparedStatement.setObject(i + 1, value);
                        }
                    }

                    preparedStatement.executeUpdate();
                }

                return Result.success("Successfully inserted 1 row into " + tableName);

            } catch (SQLException e) {
                return Result.failure("Failed to insert data into " + tableName + ": " + e.getMessage());
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

        // Get table schema to determine column types and primary keys
        Result<TableSchemaResult> schemaResult = getTableSchema(tableName);

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

                var sql = "UPDATE " + tableName + " SET " + setClause + " WHERE " + whereClause;

                // preparedStatement prevents SQL Injection via the values
                try (var preparedStatement = connection.prepareStatement(sql)) {
                    int paramIndex = 1;

                    // Set data values for SET clause
                    for (var columnName : dataColumns) {
                        var value = filteredData.get(columnName);
                        var columnInfo = schema.columns().get(columnName);
                        var dataType = columnInfo.dataType().toUpperCase();

                        // Handle JSON/JSONB columns - convert objects to JSON string
                        if ((dataType.equals("JSON") || dataType.equals("JSONB")) &&
                                value != null && !(value instanceof String)) {
                            try {
                                var jsonString = objectMapper.writeValueAsString(value);
                                preparedStatement.setObject(paramIndex++, jsonString, Types.OTHER);
                            } catch (Exception e) {
                                return Result.failure("Failed to serialize JSON for column '" + columnName + "': " + e.getMessage());
                            }
                        } else {
                            preparedStatement.setObject(paramIndex++, value);
                        }
                    }

                    // Set id values for WHERE clause
                    for (var idColumn : idColumns) {
                        var value = ids.get(idColumn);
                        preparedStatement.setObject(paramIndex++, value);
                    }

                    int updatedRows = preparedStatement.executeUpdate();
                    return Result.success("Successfully updated " + updatedRows + " row(s) in " + tableName);
                }

            } catch (SQLException e) {
                return Result.failure("Failed to update data in " + tableName + ": " + e.getMessage());
            }
        });
    }

    public Result<String> deleteDataAttestations(String tableName, Map<String, String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Result.failure("No ids provided");
        }

        // Validate table and get allowed columns, prevents SQL Injection
        var validationResult = validateTableAndGetColumns(tableName);
        if (validationResult.failed()) {
            return Result.failure(validationResult.getFailureDetail());
        }

        Result<TableSchemaResult> schemaResult = getTableSchema(tableName);

        if (schemaResult.failed()) {
            return Result.failure(schemaResult.getFailureDetail());
        }

        var primaryKeys = schemaResult.getContent().primaryKeys();

        // Check if all id columns are primary keys, prevents SQL Injection
        for (var idColumn : ids.keySet()) {
            if (!primaryKeys.contains(idColumn)) {
                return Result.failure("Column '" + idColumn + "' is not a primary key in table " + tableName);
            }
        }

        return transactionContext.execute(() -> {
            DataSource dataSource = dataSourceRegistry.resolve(dataSourceName);
            if (dataSource == null) {
                return Result.failure("DataSource not found: " + dataSourceName);
            }

            try (var connection = dataSource.getConnection()) {
                var idColumns = new ArrayList<>(ids.keySet());

                StringBuilder sql = new StringBuilder("DELETE FROM " + tableName + " WHERE ");

                for (int i = 0; i < idColumns.size(); i++) {
                    if (i > 0) {
                        sql.append(" AND ");
                    }
                    sql.append(idColumns.get(i)).append(" = ?");
                }

                int deletedRows = 0;
                // preparedStatement prevents SQL Injection via the values
                try (var preparedStatement = connection.prepareStatement(sql.toString())) {
                    for (int i = 0; i < idColumns.size(); i++) {
                        var value = ids.get(idColumns.get(i));
                        preparedStatement.setObject(i + 1, value);
                    }

                    deletedRows = preparedStatement.executeUpdate();
                }

                return Result.success("Successfully deleted " + deletedRows + " row(s) from " + tableName);

            } catch (SQLException e) {
                return Result.failure("Failed to delete data from " + tableName + ": " + e.getMessage());
            }
        });


    }

    public Result<TableSchemaResult> getTableSchema(String tableName) {
        return transactionContext.execute(() -> {
            DataSource dataSource = dataSourceRegistry.resolve(dataSourceName);
            if (dataSource == null) {
                return Result.failure("DataSource not found: " + dataSourceName);
            }

            try (var connection = dataSource.getConnection()) {
                var metaData = connection.getMetaData();

                // Get column information - store with ordinal position for sorting
                var columns = metaData.getColumns(null, null, tableName, null);
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
                    return Result.failure("Table not found or has no columns: " + tableName);
                }

                // Sort by ordinal position and build schema
                var schema = new LinkedHashMap<String, ColumnInfo>();
                columnMap.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> schema.put(entry.getValue().getKey(), entry.getValue().getValue()));

                // Get primary keys
                var primaryKeys = metaData.getPrimaryKeys(null, null, tableName);
                var pkList = new ArrayList<String>();
                while (primaryKeys.next()) {
                    var pkColumn = primaryKeys.getString("COLUMN_NAME");
                    pkList.add(pkColumn);
                }

                return Result.success(new TableSchemaResult(schema, pkList));

            } catch (SQLException e) {
                return Result.failure("Failed to retrieve schema for table " + tableName + ": " + e.getMessage());
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
}
