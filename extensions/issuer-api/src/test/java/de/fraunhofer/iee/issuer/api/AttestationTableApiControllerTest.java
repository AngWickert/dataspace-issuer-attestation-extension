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

import de.fraunhofer.iee.issuer.api.model.DeleteDataRequestDto;
import de.fraunhofer.iee.issuer.api.model.InsertDataRequestDto;
import de.fraunhofer.iee.issuer.api.model.UpdateDataRequestDto;
import org.eclipse.edc.issuerservice.spi.issuance.attestation.AttestationDefinitionService;
import org.eclipse.edc.issuerservice.spi.issuance.model.AttestationDefinition;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.result.ServiceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttestationTableApiControllerTest {

    private static final String PARTICIPANT_ID = "test-participant";
    private static final String PARTICIPANT_ID_ENCODED = Base64.getUrlEncoder().encodeToString(PARTICIPANT_ID.getBytes());
    private static final String TABLE_NAME = "test_table";

    @Mock
    private AttestationDefinitionService attestationDefinitionService;

    @Mock
    private DatabaseService databaseService;

    @Mock
    private AttestationService attestationService;

    private AttestationTableApiController controller;

    @BeforeEach
    void setUp() {
        controller = new AttestationTableApiController(attestationDefinitionService, databaseService, attestationService);
    }

    // ========== Tests for getTables() ==========

    @Test
    void getTables_whenSuccess_returnsTables() {
        // Arrange
        Map<String, Object> config = new HashMap<>();
        config.put("tableName", TABLE_NAME);
        config.put("idColumn", "user_id");
        config.put("dataSourceName", "default");

        var attestation = AttestationDefinition.Builder.newInstance()
                .id("test-attestation")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config)
                .build();

        var columns = new LinkedHashMap<String, DatabaseService.ColumnInfo>();
        columns.put("user_id", new DatabaseService.ColumnInfo("VARCHAR", false, null));
        columns.put("name", new DatabaseService.ColumnInfo("VARCHAR", false, null));

        var schema = new DatabaseService.TableSchemaResult(columns, List.of("user_id"));

        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of(attestation)));
        when(attestationService.getUsedAttestationIds(any()))
                .thenReturn(List.of());
        when(databaseService.getTableSchema(TABLE_NAME))
                .thenReturn(Result.success(schema));

        // Act
        var result = controller.getTables(PARTICIPANT_ID_ENCODED);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);

        var dto = result.iterator().next();
        assertThat(dto.tableName()).isEqualTo(TABLE_NAME);
        assertThat(dto.idColumn()).isEqualTo("user_id");
        assertThat(dto.dataSourceName()).isEqualTo("default");
        assertThat(dto.active()).isFalse();
        assertThat(dto.primaryKeys()).containsExactly("user_id");
        assertThat(dto.tableParams()).containsKeys("user_id", "name");
    }

    @Test
    void getTables_whenUsedByCredentialDefinition_marksAsActive() {
        // Arrange
        Map<String, Object> config = new HashMap<>();
        config.put("tableName", TABLE_NAME);
        config.put("idColumn", "user_id");
        config.put("dataSourceName", "default");

        var attestation = AttestationDefinition.Builder.newInstance()
                .id("active-attestation")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config)
                .build();

        var columns = new LinkedHashMap<String, DatabaseService.ColumnInfo>();
        columns.put("user_id", new DatabaseService.ColumnInfo("VARCHAR", false, null));
        var schema = new DatabaseService.TableSchemaResult(columns, List.of("user_id"));

        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of(attestation)));
        when(attestationService.getUsedAttestationIds(any()))
                .thenReturn(List.of("active-attestation"));
        when(databaseService.getTableSchema(TABLE_NAME))
                .thenReturn(Result.success(schema));

        // Act
        var result = controller.getTables(PARTICIPANT_ID_ENCODED);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);
        assertThat(result.iterator().next().active()).isTrue();
    }

    @Test
    void getTables_whenServiceFails_returnsEmptyList() {
        // Arrange
        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.badRequest("Query failed"));

        // Act
        var result = controller.getTables(PARTICIPANT_ID_ENCODED);

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void getTables_whenSchemaRetrievalFails_includesErrorInTableParams() {
        // Arrange
        Map<String, Object> config = new HashMap<>();
        config.put("tableName", TABLE_NAME);
        config.put("idColumn", "user_id");
        config.put("dataSourceName", "default");

        var attestation = AttestationDefinition.Builder.newInstance()
                .id("test-attestation")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config)
                .build();

        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of(attestation)));
        when(attestationService.getUsedAttestationIds(any()))
                .thenReturn(List.of());
        when(databaseService.getTableSchema(TABLE_NAME))
                .thenReturn(Result.failure("Schema retrieval failed"));

        // Act
        var result = controller.getTables(PARTICIPANT_ID_ENCODED);

        // Assert
        assertThat(result).hasSize(1);
        var dto = result.iterator().next();
        assertThat(dto.tableParams()).containsKey("error");
        assertThat(dto.tableParams().get("error")).asString().contains("Schema retrieval failed");
    }

    @Test
    void getTables_whenTableNameIsMissing_skipsAttestation() {
        // Arrange
        Map<String, Object> config = new HashMap<>();
        config.put("idColumn", "user_id");
        config.put("dataSourceName", "default");
        // tableName is missing

        var attestation = AttestationDefinition.Builder.newInstance()
                .id("test-attestation")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config)
                .build();

        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of(attestation)));
        when(attestationService.getUsedAttestationIds(any()))
                .thenReturn(List.of());

        // Act
        var result = controller.getTables(PARTICIPANT_ID_ENCODED);

        // Assert
        assertThat(result).isEmpty();
        verify(databaseService, never()).getTableSchema(anyString());
    }

    // ========== Tests for getDataAttestations() ==========

    @Test
    void getDataAttestations_whenSuccess_returnsData() {
        // Arrange
        List<Map<String, Object>> data = List.of(
                Map.of("user_id", "1", "name", "John"),
                Map.of("user_id", "2", "name", "Jane")
        );

        when(attestationService.hasNoAttestationForTable(TABLE_NAME, PARTICIPANT_ID))
                .thenReturn(false);
        when(databaseService.queryDataAttestations(TABLE_NAME))
                .thenReturn(Result.success(data));

        // Act
        var result = controller.getDataAttestations(PARTICIPANT_ID_ENCODED, TABLE_NAME);

        // Assert
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsEntry("user_id", "1");
        assertThat(result.get(0)).containsEntry("name", "John");
    }

    @Test
    void getDataAttestations_whenTableNameIsNull_returnsEmptyList() {
        // Act
        var result = controller.getDataAttestations(PARTICIPANT_ID_ENCODED, null);

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void getDataAttestations_whenTableNameIsEmpty_returnsEmptyList() {
        // Act
        var result = controller.getDataAttestations(PARTICIPANT_ID_ENCODED, "");

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void getDataAttestations_whenNoAttestationForTable_returnsEmptyList() {
        // Arrange
        when(attestationService.hasNoAttestationForTable(TABLE_NAME, PARTICIPANT_ID))
                .thenReturn(true);

        // Act
        var result = controller.getDataAttestations(PARTICIPANT_ID_ENCODED, TABLE_NAME);

        // Assert
        assertThat(result).isEmpty();
        verify(databaseService, never()).queryDataAttestations(anyString());
    }

    @Test
    void getDataAttestations_whenQueryFails_returnsEmptyList() {
        // Arrange
        when(attestationService.hasNoAttestationForTable(TABLE_NAME, PARTICIPANT_ID))
                .thenReturn(false);
        when(databaseService.queryDataAttestations(TABLE_NAME))
                .thenReturn(Result.failure("Query failed"));

        // Act
        var result = controller.getDataAttestations(PARTICIPANT_ID_ENCODED, TABLE_NAME);

        // Assert
        assertThat(result).isEmpty();
    }

    // ========== Tests for insertDataAttestations() ==========

    @Test
    void insertDataAttestations_whenSuccess_returnsSuccess() {
        // Arrange
        Map<String, Object> data = Map.of("name", "John", "age", 30);
        var request = new InsertDataRequestDto(TABLE_NAME, data);

        when(attestationService.hasNoAttestationForTable(TABLE_NAME, PARTICIPANT_ID))
                .thenReturn(false);
        when(databaseService.setDataAttestations(TABLE_NAME, data))
                .thenReturn(Result.success("Data inserted successfully"));

        // Act
        var result = controller.insertDataAttestations(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(result).containsEntry("success", true);
        assertThat(result).containsEntry("message", "Data inserted successfully");
    }

    @Test
    void insertDataAttestations_whenRequestIsNull_returnsFailure() {
        // Act
        var result = controller.insertDataAttestations(PARTICIPANT_ID_ENCODED, null);

        // Assert
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("message")).asString().contains("tableName is required");
    }

    @Test
    void insertDataAttestations_whenTableNameIsNull_returnsFailure() {
        // Arrange
        var request = new InsertDataRequestDto(null, Map.of("key", "value"));

        // Act
        var result = controller.insertDataAttestations(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("message")).asString().contains("tableName is required");
    }

    @Test
    void insertDataAttestations_whenDataIsNull_returnsFailure() {
        // Arrange
        var request = new InsertDataRequestDto(TABLE_NAME, null);

        // Act
        var result = controller.insertDataAttestations(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("message")).asString().contains("data is required");
    }

    @Test
    void insertDataAttestations_whenNoAttestationForTable_returnsFailure() {
        // Arrange
        var request = new InsertDataRequestDto(TABLE_NAME, Map.of("key", "value"));

        when(attestationService.hasNoAttestationForTable(TABLE_NAME, PARTICIPANT_ID))
                .thenReturn(true);

        // Act
        var result = controller.insertDataAttestations(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("message")).asString().contains("No attestation found for table");
    }

    @Test
    void insertDataAttestations_whenDatabaseServiceFails_returnsFailure() {
        // Arrange
        Map<String, Object> data = Map.of("name", "John");
        var request = new InsertDataRequestDto(TABLE_NAME, data);

        when(attestationService.hasNoAttestationForTable(TABLE_NAME, PARTICIPANT_ID))
                .thenReturn(false);
        when(databaseService.setDataAttestations(TABLE_NAME, data))
                .thenReturn(Result.failure("Database error"));

        // Act
        var result = controller.insertDataAttestations(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(result).containsEntry("success", false);
        assertThat(result).containsEntry("message", "Database error");
    }

    // ========== Tests for deleteDataAttestations() ==========

    @Test
    void deleteDataAttestations_whenSuccess_returnsSuccess() {
        // Arrange
        Map<String, String> ids = Map.of("user_id", "1");
        var request = new DeleteDataRequestDto(TABLE_NAME, ids);

        when(attestationService.hasNoAttestationForTable(TABLE_NAME, PARTICIPANT_ID))
                .thenReturn(false);
        when(databaseService.deleteDataAttestations(TABLE_NAME, ids))
                .thenReturn(Result.success("Data deleted successfully"));

        // Act
        var result = controller.deleteDataAttestations(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(result).containsEntry("success", true);
        assertThat(result).containsEntry("message", "Data deleted successfully");
    }

    @Test
    void deleteDataAttestations_whenRequestIsNull_returnsFailure() {
        // Act
        var result = controller.deleteDataAttestations(PARTICIPANT_ID_ENCODED, null);

        // Assert
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("message")).asString().contains("tableName is required");
    }

    @Test
    void deleteDataAttestations_whenTableNameIsNull_returnsFailure() {
        // Arrange
        var request = new DeleteDataRequestDto(null, Map.of("user_id", "1"));

        // Act
        var result = controller.deleteDataAttestations(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("message")).asString().contains("tableName is required");
    }

    @Test
    void deleteDataAttestations_whenIdsAreNull_returnsFailure() {
        // Arrange
        var request = new DeleteDataRequestDto(TABLE_NAME, null);

        // Act
        var result = controller.deleteDataAttestations(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("message")).asString().contains("ids are required");
    }

    @Test
    void deleteDataAttestations_whenIdsAreEmpty_returnsFailure() {
        // Arrange
        var request = new DeleteDataRequestDto(TABLE_NAME, Map.of());

        // Act
        var result = controller.deleteDataAttestations(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("message")).asString().contains("ids are required");
    }

    @Test
    void deleteDataAttestations_whenNoAttestationForTable_returnsFailure() {
        // Arrange
        var request = new DeleteDataRequestDto(TABLE_NAME, Map.of("user_id", "1"));

        when(attestationService.hasNoAttestationForTable(TABLE_NAME, PARTICIPANT_ID))
                .thenReturn(true);

        // Act
        var result = controller.deleteDataAttestations(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("message")).asString().contains("No attestation found for table");
    }

    @Test
    void deleteDataAttestations_whenDatabaseServiceFails_returnsFailure() {
        // Arrange
        Map<String, String> ids = Map.of("user_id", "1");
        var request = new DeleteDataRequestDto(TABLE_NAME, ids);

        when(attestationService.hasNoAttestationForTable(TABLE_NAME, PARTICIPANT_ID))
                .thenReturn(false);
        when(databaseService.deleteDataAttestations(TABLE_NAME, ids))
                .thenReturn(Result.failure("Database error"));

        // Act
        var result = controller.deleteDataAttestations(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(result).containsEntry("success", false);
        assertThat(result).containsEntry("message", "Database error");
    }

    // ========== Tests for updateDataAttestations() ==========

    @Test
    void updateDataAttestations_whenSuccess_returnsSuccess() {
        // Arrange
        Map<String, String> ids = Map.of("user_id", "1");
        Map<String, Object> data = Map.of("name", "Jane");
        var request = new UpdateDataRequestDto(TABLE_NAME, ids, data);

        when(attestationService.hasNoAttestationForTable(TABLE_NAME, PARTICIPANT_ID))
                .thenReturn(false);
        when(databaseService.updateDataAttestations(TABLE_NAME, ids, data))
                .thenReturn(Result.success("Data updated successfully"));

        // Act
        var result = controller.updateDataAttestations(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(result).containsEntry("success", true);
        assertThat(result).containsEntry("message", "Data updated successfully");
    }

    @Test
    void updateDataAttestations_whenRequestIsNull_returnsFailure() {
        // Act
        var result = controller.updateDataAttestations(PARTICIPANT_ID_ENCODED, null);

        // Assert
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("message")).asString().contains("tableName is required");
    }

    @Test
    void updateDataAttestations_whenTableNameIsNull_returnsFailure() {
        // Arrange
        var request = new UpdateDataRequestDto(null, Map.of("user_id", "1"), Map.of("key", "value"));

        // Act
        var result = controller.updateDataAttestations(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("message")).asString().contains("tableName is required");
    }

    @Test
    void updateDataAttestations_whenIdsAreNull_returnsFailure() {
        // Arrange
        var request = new UpdateDataRequestDto(TABLE_NAME, null, Map.of("key", "value"));

        // Act
        var result = controller.updateDataAttestations(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("message")).asString().contains("ids are required");
    }

    @Test
    void updateDataAttestations_whenDataIsNull_returnsFailure() {
        // Arrange
        var request = new UpdateDataRequestDto(TABLE_NAME, Map.of("user_id", "1"), null);

        // Act
        var result = controller.updateDataAttestations(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("message")).asString().contains("data is required");
    }

    @Test
    void updateDataAttestations_whenNoAttestationForTable_returnsFailure() {
        // Arrange
        var request = new UpdateDataRequestDto(TABLE_NAME, Map.of("user_id", "1"), Map.of("key", "value"));

        when(attestationService.hasNoAttestationForTable(TABLE_NAME, PARTICIPANT_ID))
                .thenReturn(true);

        // Act
        var result = controller.updateDataAttestations(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(result).containsEntry("success", false);
        assertThat(result.get("message")).asString().contains("No attestation found for table");
    }

    @Test
    void updateDataAttestations_whenDatabaseServiceFails_returnsFailure() {
        // Arrange
        Map<String, String> ids = Map.of("user_id", "1");
        Map<String, Object> data = Map.of("name", "Jane");
        var request = new UpdateDataRequestDto(TABLE_NAME, ids, data);

        when(attestationService.hasNoAttestationForTable(TABLE_NAME, PARTICIPANT_ID))
                .thenReturn(false);
        when(databaseService.updateDataAttestations(TABLE_NAME, ids, data))
                .thenReturn(Result.failure("Database error"));

        // Act
        var result = controller.updateDataAttestations(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(result).containsEntry("success", false);
        assertThat(result).containsEntry("message", "Database error");
    }

    // ========== Tests for getTableSchema() ==========

    @Test
    void getTableSchema_whenSuccess_returnsSchema() {
        // Arrange
        var columns = new LinkedHashMap<String, DatabaseService.ColumnInfo>();
        columns.put("user_id", new DatabaseService.ColumnInfo("VARCHAR", false, null));
        columns.put("name", new DatabaseService.ColumnInfo("VARCHAR", true, "default_name"));
        var schema = new DatabaseService.TableSchemaResult(columns, List.of("user_id"));

        when(databaseService.getTableSchema(TABLE_NAME))
                .thenReturn(Result.success(schema));
        when(attestationService.getIdColumn(TABLE_NAME, PARTICIPANT_ID_ENCODED))
                .thenReturn("user_id");

        // Act
        var result = controller.getTableSchema(PARTICIPANT_ID_ENCODED, TABLE_NAME);

        // Assert
        assertThat(result.tableName()).isEqualTo(TABLE_NAME);
        assertThat(result.idColumn()).isEqualTo("user_id");
        assertThat(result.columns()).hasSize(2);
        assertThat(result.columns().get("user_id").dataType()).isEqualTo("VARCHAR");
        assertThat(result.columns().get("name").isNullable()).isTrue();
        assertThat(result.primaryKeys()).containsExactly("user_id");
    }

    @Test
    void getTableSchema_whenTableNameIsNull_returnsError() {
        // Act
        var result = controller.getTableSchema(PARTICIPANT_ID_ENCODED, null);

        // Assert
        assertThat(result.tableName()).isEqualTo("error");
        assertThat(result.columns()).containsKey("error");
        assertThat(result.columns().get("error").defaultValue()).contains("tableName query parameter is required");
    }

    @Test
    void getTableSchema_whenTableNameIsEmpty_returnsError() {
        // Act
        var result = controller.getTableSchema(PARTICIPANT_ID_ENCODED, "");

        // Assert
        assertThat(result.tableName()).isEqualTo("error");
        assertThat(result.columns()).containsKey("error");
    }

    @Test
    void getTableSchema_whenDatabaseServiceFails_returnsError() {
        // Arrange
        when(databaseService.getTableSchema(TABLE_NAME))
                .thenReturn(Result.failure("Schema retrieval failed"));

        // Act
        var result = controller.getTableSchema(PARTICIPANT_ID_ENCODED, TABLE_NAME);

        // Assert
        assertThat(result.tableName()).isEqualTo(TABLE_NAME);
        assertThat(result.columns()).containsKey("error");
        assertThat(result.columns().get("error").defaultValue()).contains("Schema retrieval failed");
    }

    @Test
    void getTableSchema_whenIdColumnNotFound_returnsError() {
        // Arrange
        var columns = new LinkedHashMap<String, DatabaseService.ColumnInfo>();
        columns.put("user_id", new DatabaseService.ColumnInfo("VARCHAR", false, null));
        var schema = new DatabaseService.TableSchemaResult(columns, List.of("user_id"));

        when(databaseService.getTableSchema(TABLE_NAME))
                .thenReturn(Result.success(schema));
        when(attestationService.getIdColumn(TABLE_NAME, PARTICIPANT_ID_ENCODED))
                .thenReturn(null);

        // Act
        var result = controller.getTableSchema(PARTICIPANT_ID_ENCODED, TABLE_NAME);

        // Assert
        assertThat(result.tableName()).isEqualTo(TABLE_NAME);
        assertThat(result.columns()).containsKey("error");
        assertThat(result.columns().get("error").defaultValue()).contains("idColumn not found");
    }
}
