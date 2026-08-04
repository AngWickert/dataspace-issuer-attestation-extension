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

import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.transaction.datasource.spi.DataSourceRegistry;
import org.eclipse.edc.transaction.spi.TransactionContext;

import java.sql.ResultSet;
import java.sql.SQLException;
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

    public AttestationDataService(DataSourceRegistry dataSourceRegistry,
                                  String dataSourceName,
                                  TransactionContext transactionContext) {
        this.dataSourceRegistry = dataSourceRegistry;
        this.dataSourceName = dataSourceName;
        this.transactionContext = transactionContext;
    }

    public Result<List<Map<String, Object>>> queryDataAttestations(String tableName) {
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
            var value = resultSet.getObject(columnName);
            map.put(columnName, value);
        }
        return map;
    }
}
