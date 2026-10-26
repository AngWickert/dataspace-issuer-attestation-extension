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

import de.fraunhofer.iee.issuer.api.model.ColumnDto;
import de.fraunhofer.iee.issuer.api.model.CreateCredentialDefinitionsRequestDto;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.issuerservice.spi.issuance.credentialdefinition.CredentialDefinitionService;
import org.eclipse.edc.issuerservice.spi.issuance.model.MappingDefinition;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.spi.result.ServiceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CredentialDefinitionsApiControllerTest {

    private static final String PARTICIPANT_ID = "test-participant";
    private static final String PARTICIPANT_ID_ENCODED = Base64.getUrlEncoder().encodeToString(PARTICIPANT_ID.getBytes());
    private static final String TABLE_NAME = "test_table";
    private static final String CREDENTIAL_TYPE = "VerifiableCredential";
    private static final String ID = "test-credential-def";
    private static final String FORMAT = "jwt_vc";
    private static final String ID_COLUMN = "user_id";

    @Mock
    private CredentialDefinitionService credentialDefinitionService;

    @Mock
    private DatabaseService databaseService;

    @Mock
    private AttestationService attestationService;

    private CredentialDefinitionsApiController controller;

    @BeforeEach
    void setUp() {
        controller = new CredentialDefinitionsApiController(credentialDefinitionService, databaseService, attestationService);
    }

    // ========== Tests for setCredentialDefinitions() ==========

    @Test
    void setCredentialDefinitions_whenSuccess_returnsOk() {
        // Arrange
        var mappings = List.of(
                new MappingDefinition("firstName", "credentialSubject.name", true)
        );
        var columns = List.of(new ColumnDto("name", "VARCHAR", null, false, false));
        var request = new CreateCredentialDefinitionsRequestDto(
                "attestation-1",
                CREDENTIAL_TYPE,
                ID,
                mappings,
                3600L,
                FORMAT,
                TABLE_NAME,
                columns
        );

        when(databaseService.isValidValue(CREDENTIAL_TYPE)).thenReturn(true);
        when(databaseService.isValidIdentifier(ID)).thenReturn(true);
        when(databaseService.isValidValue(FORMAT)).thenReturn(true);
        when(attestationService.getIdColumn(TABLE_NAME, PARTICIPANT_ID_ENCODED)).thenReturn(ID_COLUMN);
        when(databaseService.hasMatchingColumnNames(mappings, columns, ID_COLUMN)).thenReturn(true);
        when(databaseService.hasValidMappingProperties(mappings)).thenReturn(true);
        when(credentialDefinitionService.createCredentialDefinition(any())).thenReturn(ServiceResult.success());
        when(databaseService.removeIdColumn(any(), anyString())).thenReturn(columns);
        when(databaseService.alterTable(TABLE_NAME, columns)).thenReturn(Result.success("Table altered"));

        // Act
        var response = controller.setCredentialDefinitions(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
        var entity = (Map<String, Object>) response.getEntity();
        assertThat(entity.get("success")).isEqualTo(true);
        assertThat(entity.get("message")).isEqualTo("CredentialDefinitions created successfully");
    }

    @Test
    void setCredentialDefinitions_whenRequestIsNull_returnsBadRequest() {
        // Act
        var response = controller.setCredentialDefinitions(PARTICIPANT_ID_ENCODED, null);

        // Assert
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        var entity = (Map<String, Object>) response.getEntity();
        assertThat(entity.get("success")).isEqualTo(false);
        assertThat(entity.get("message")).asString().contains("attestation is required");
    }

    @Test
    void setCredentialDefinitions_whenAttestationIsEmpty_returnsBadRequest() {
        // Arrange
        var request = new CreateCredentialDefinitionsRequestDto(
                "",
                CREDENTIAL_TYPE,
                ID,
                List.of(),
                3600L,
                FORMAT,
                TABLE_NAME,
                List.of()
        );

        // Act
        var response = controller.setCredentialDefinitions(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        var entity = (Map<String, Object>) response.getEntity();
        assertThat(entity.get("success")).isEqualTo(false);
    }

    @Test
    void setCredentialDefinitions_whenCredentialTypeIsInvalid_returnsBadRequest() {
        // Arrange
        var request = new CreateCredentialDefinitionsRequestDto(
                "attestation-1",
                "InvalidType<>",
                ID,
                List.of(),
                3600L,
                FORMAT,
                TABLE_NAME,
                List.of()
        );

        when(databaseService.isValidValue("InvalidType<>")).thenReturn(false);

        // Act
        var response = controller.setCredentialDefinitions(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
    }

    @Test
    void setCredentialDefinitions_whenIdIsInvalid_returnsBadRequest() {
        // Arrange
        var request = new CreateCredentialDefinitionsRequestDto(
                "attestation-1",
                CREDENTIAL_TYPE,
                "invalid-id!",
                List.of(),
                3600L,
                FORMAT,
                TABLE_NAME,
                List.of()
        );

        when(databaseService.isValidValue(CREDENTIAL_TYPE)).thenReturn(true);
        when(databaseService.isValidIdentifier("invalid-id!")).thenReturn(false);

        // Act
        var response = controller.setCredentialDefinitions(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
    }

    @Test
    void setCredentialDefinitions_whenMappingsAreEmpty_returnsBadRequest() {
        // Arrange
        var request = new CreateCredentialDefinitionsRequestDto(
                "attestation-1",
                CREDENTIAL_TYPE,
                ID,
                List.of(),
                3600L,
                FORMAT,
                TABLE_NAME,
                List.of()
        );

        when(databaseService.isValidValue(CREDENTIAL_TYPE)).thenReturn(true);
        when(databaseService.isValidIdentifier(ID)).thenReturn(true);

        // Act
        var response = controller.setCredentialDefinitions(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
    }

    @Test
    void setCredentialDefinitions_whenMappingsContainIdInput_returnsBadRequest() {
        // Arrange
        var mappings = List.of(
                new MappingDefinition("id", "credentialSubject.userId", true)
        );
        var request = new CreateCredentialDefinitionsRequestDto(
                "attestation-1",
                CREDENTIAL_TYPE,
                ID,
                mappings,
                3600L,
                FORMAT,
                TABLE_NAME,
                List.of()
        );

        when(databaseService.isValidValue(CREDENTIAL_TYPE)).thenReturn(true);
        when(databaseService.isValidIdentifier(ID)).thenReturn(true);

        // Act
        var response = controller.setCredentialDefinitions(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        var entity = (Map<String, Object>) response.getEntity();
        assertThat(entity.get("message")).asString().contains("input with value 'id' is not allowed");
    }

    @Test
    void setCredentialDefinitions_whenFormatIsInvalid_returnsBadRequest() {
        // Arrange
        var mappings = List.of(new MappingDefinition("name", "credentialSubject.userName", true));
        var request = new CreateCredentialDefinitionsRequestDto(
                "attestation-1",
                CREDENTIAL_TYPE,
                ID,
                mappings,
                3600L,
                "invalid<>",
                TABLE_NAME,
                List.of()
        );

        when(databaseService.isValidValue(CREDENTIAL_TYPE)).thenReturn(true);
        when(databaseService.isValidIdentifier(ID)).thenReturn(true);
        when(databaseService.isValidValue("invalid<>")).thenReturn(false);

        // Act
        var response = controller.setCredentialDefinitions(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
    }

    @Test
    void setCredentialDefinitions_whenIdColumnNotFound_returnsBadRequest() {
        // Arrange
        var mappings = List.of(new MappingDefinition("name", "credentialSubject.userName", true));
        var request = new CreateCredentialDefinitionsRequestDto(
                "attestation-1",
                CREDENTIAL_TYPE,
                ID,
                mappings,
                3600L,
                FORMAT,
                TABLE_NAME,
                List.of()
        );

        when(databaseService.isValidValue(CREDENTIAL_TYPE)).thenReturn(true);
        when(databaseService.isValidIdentifier(ID)).thenReturn(true);
        when(databaseService.isValidValue(FORMAT)).thenReturn(true);
        when(attestationService.getIdColumn(TABLE_NAME, PARTICIPANT_ID_ENCODED)).thenReturn(null);

        // Act
        var response = controller.setCredentialDefinitions(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        var entity = (Map<String, Object>) response.getEntity();
        assertThat(entity.get("message")).asString().contains("can't find id column");
    }

    @Test
    void setCredentialDefinitions_whenMappingsAndColumnsDoNotMatch_returnsBadRequest() {
        // Arrange
        var mappings = List.of(new MappingDefinition("firstName", "credentialSubject.name", true));
        var columns = List.of(new ColumnDto("wrongColumn", "VARCHAR", null, false, false));
        var request = new CreateCredentialDefinitionsRequestDto(
                "attestation-1",
                CREDENTIAL_TYPE,
                ID,
                mappings,
                3600L,
                FORMAT,
                TABLE_NAME,
                columns
        );

        when(databaseService.isValidValue(CREDENTIAL_TYPE)).thenReturn(true);
        when(databaseService.isValidIdentifier(ID)).thenReturn(true);
        when(databaseService.isValidValue(FORMAT)).thenReturn(true);
        when(attestationService.getIdColumn(TABLE_NAME, PARTICIPANT_ID_ENCODED)).thenReturn(ID_COLUMN);
        when(databaseService.hasMatchingColumnNames(mappings, columns, ID_COLUMN)).thenReturn(false);

        // Act
        var response = controller.setCredentialDefinitions(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        var entity = (Map<String, Object>) response.getEntity();
        assertThat(entity.get("message")).asString().contains("mappings and columns do not match");
    }

    @Test
    void setCredentialDefinitions_whenMappingPropertiesAreInvalid_returnsBadRequest() {
        // Arrange
        var mappings = List.of(new MappingDefinition("firstName", "credentialSubject.name", true));
        var columns = List.of(new ColumnDto("name", "VARCHAR", null, false, false));
        var request = new CreateCredentialDefinitionsRequestDto(
                "attestation-1",
                CREDENTIAL_TYPE,
                ID,
                mappings,
                3600L,
                FORMAT,
                TABLE_NAME,
                columns
        );

        when(databaseService.isValidValue(CREDENTIAL_TYPE)).thenReturn(true);
        when(databaseService.isValidIdentifier(ID)).thenReturn(true);
        when(databaseService.isValidValue(FORMAT)).thenReturn(true);
        when(attestationService.getIdColumn(TABLE_NAME, PARTICIPANT_ID_ENCODED)).thenReturn(ID_COLUMN);
        when(databaseService.hasMatchingColumnNames(mappings, columns, ID_COLUMN)).thenReturn(true);
        when(databaseService.hasValidMappingProperties(mappings)).thenReturn(false);

        // Act
        var response = controller.setCredentialDefinitions(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        var entity = (Map<String, Object>) response.getEntity();
        assertThat(entity.get("message")).asString().contains("invalid mapping properties");
    }

    @Test
    void setCredentialDefinitions_whenCredentialServiceFails_returnsBadRequest() {
        // Arrange
        var mappings = List.of(new MappingDefinition("firstName", "credentialSubject.name", true));
        var columns = List.of(new ColumnDto("name", "VARCHAR", null, false, false));
        var request = new CreateCredentialDefinitionsRequestDto(
                "attestation-1",
                CREDENTIAL_TYPE,
                ID,
                mappings,
                3600L,
                FORMAT,
                TABLE_NAME,
                columns
        );

        when(databaseService.isValidValue(CREDENTIAL_TYPE)).thenReturn(true);
        when(databaseService.isValidIdentifier(ID)).thenReturn(true);
        when(databaseService.isValidValue(FORMAT)).thenReturn(true);
        when(attestationService.getIdColumn(TABLE_NAME, PARTICIPANT_ID_ENCODED)).thenReturn(ID_COLUMN);
        when(databaseService.hasMatchingColumnNames(mappings, columns, ID_COLUMN)).thenReturn(true);
        when(databaseService.hasValidMappingProperties(mappings)).thenReturn(true);
        when(credentialDefinitionService.createCredentialDefinition(any()))
                .thenReturn(ServiceResult.badRequest("Credential definition already exists"));

        // Act
        var response = controller.setCredentialDefinitions(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
        var entity = (Map<String, Object>) response.getEntity();
        assertThat(entity.get("success")).isEqualTo(false);
        assertThat(entity.get("message")).asString().contains("already exists");
    }

    @Test
    void setCredentialDefinitions_whenTableCreationFails_rollsBackAndReturnsError() {
        // Arrange
        var mappings = List.of(new MappingDefinition("firstName", "credentialSubject.name", true));
        var columns = List.of(new ColumnDto("name", "VARCHAR", null, false, false));
        var request = new CreateCredentialDefinitionsRequestDto(
                "attestation-1",
                CREDENTIAL_TYPE,
                ID,
                mappings,
                3600L,
                FORMAT,
                TABLE_NAME,
                columns
        );

        when(databaseService.isValidValue(CREDENTIAL_TYPE)).thenReturn(true);
        when(databaseService.isValidIdentifier(ID)).thenReturn(true);
        when(databaseService.isValidValue(FORMAT)).thenReturn(true);
        when(attestationService.getIdColumn(TABLE_NAME, PARTICIPANT_ID_ENCODED)).thenReturn(ID_COLUMN);
        when(databaseService.hasMatchingColumnNames(mappings, columns, ID_COLUMN)).thenReturn(true);
        when(databaseService.hasValidMappingProperties(mappings)).thenReturn(true);
        when(credentialDefinitionService.createCredentialDefinition(any())).thenReturn(ServiceResult.success());
        when(databaseService.removeIdColumn(any(), anyString())).thenReturn(columns);
        when(databaseService.alterTable(TABLE_NAME, columns)).thenReturn(Result.failure("Database error"));
        when(credentialDefinitionService.deleteCredentialDefinition(ID)).thenReturn(ServiceResult.success());

        // Act
        var response = controller.setCredentialDefinitions(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        var entity = (Map<String, Object>) response.getEntity();
        assertThat(entity.get("success")).isEqualTo(false);
        assertThat(entity.get("message")).asString().contains("Table creation failed");

        // Verify rollback
        verify(credentialDefinitionService).deleteCredentialDefinition(ID);
    }

    @Test
    void setCredentialDefinitions_createsCredentialDefinitionWithCorrectFields() {
        // Arrange
        var mappings = List.of(new MappingDefinition("firstName", "credentialSubject.name", true));
        var columns = List.of(new ColumnDto("name", "VARCHAR", null, false, false));
        var validity = 3600L;
        var request = new CreateCredentialDefinitionsRequestDto(
                "attestation-1",
                CREDENTIAL_TYPE,
                ID,
                mappings,
                validity,
                FORMAT,
                TABLE_NAME,
                columns
        );

        when(databaseService.isValidValue(CREDENTIAL_TYPE)).thenReturn(true);
        when(databaseService.isValidIdentifier(ID)).thenReturn(true);
        when(databaseService.isValidValue(FORMAT)).thenReturn(true);
        when(attestationService.getIdColumn(TABLE_NAME, PARTICIPANT_ID_ENCODED)).thenReturn(ID_COLUMN);
        when(databaseService.hasMatchingColumnNames(mappings, columns, ID_COLUMN)).thenReturn(true);
        when(databaseService.hasValidMappingProperties(mappings)).thenReturn(true);
        when(credentialDefinitionService.createCredentialDefinition(any())).thenReturn(ServiceResult.success());
        when(databaseService.removeIdColumn(any(), anyString())).thenReturn(columns);
        when(databaseService.alterTable(TABLE_NAME, columns)).thenReturn(Result.success("Table altered"));

        var captor = ArgumentCaptor.forClass(org.eclipse.edc.issuerservice.spi.issuance.model.CredentialDefinition.class);

        // Act
        controller.setCredentialDefinitions(PARTICIPANT_ID_ENCODED, request);

        // Assert
        verify(credentialDefinitionService).createCredentialDefinition(captor.capture());
        var capturedDefinition = captor.getValue();

        assertThat(capturedDefinition.getParticipantContextId()).isEqualTo(PARTICIPANT_ID);
        assertThat(capturedDefinition.getCredentialType()).isEqualTo(CREDENTIAL_TYPE);
        assertThat(capturedDefinition.getId()).isEqualTo(ID);
        assertThat(capturedDefinition.getMappings()).isEqualTo(mappings);
        assertThat(capturedDefinition.getValidity()).isEqualTo(validity);
        assertThat(capturedDefinition.getFormat()).isEqualTo(FORMAT);
        assertThat(capturedDefinition.getJsonSchema()).isEqualTo("{}");
    }

    @Test
    void setCredentialDefinitions_decodesBase64ParticipantId() {
        // Arrange
        var mappings = List.of(new MappingDefinition("firstName", "credentialSubject.name", true));
        var columns = List.of(new ColumnDto("name", "VARCHAR", null, false, false));
        var request = new CreateCredentialDefinitionsRequestDto(
                "attestation-1",
                CREDENTIAL_TYPE,
                ID,
                mappings,
                3600L,
                FORMAT,
                TABLE_NAME,
                columns
        );

        when(databaseService.isValidValue(CREDENTIAL_TYPE)).thenReturn(true);
        when(databaseService.isValidIdentifier(ID)).thenReturn(true);
        when(databaseService.isValidValue(FORMAT)).thenReturn(true);
        when(attestationService.getIdColumn(TABLE_NAME, PARTICIPANT_ID_ENCODED)).thenReturn(ID_COLUMN);
        when(databaseService.hasMatchingColumnNames(mappings, columns, ID_COLUMN)).thenReturn(true);
        when(databaseService.hasValidMappingProperties(mappings)).thenReturn(true);
        when(credentialDefinitionService.createCredentialDefinition(any())).thenReturn(ServiceResult.success());
        when(databaseService.removeIdColumn(any(), anyString())).thenReturn(columns);
        when(databaseService.alterTable(TABLE_NAME, columns)).thenReturn(Result.success("Table altered"));

        var captor = ArgumentCaptor.forClass(org.eclipse.edc.issuerservice.spi.issuance.model.CredentialDefinition.class);

        // Act
        controller.setCredentialDefinitions(PARTICIPANT_ID_ENCODED, request);

        // Assert
        verify(credentialDefinitionService).createCredentialDefinition(captor.capture());
        assertThat(captor.getValue().getParticipantContextId()).isEqualTo(PARTICIPANT_ID);
    }

    @Test
    void setCredentialDefinitions_removesIdColumnTwiceFromColumns() {
        // Arrange
        var mappings = List.of(new MappingDefinition("firstName", "credentialSubject.name", true));
        Collection<ColumnDto> columns = List.of(
                new ColumnDto("name", "VARCHAR", null, false, false),
                new ColumnDto("user_id", "VARCHAR", null, false, false),
                new ColumnDto("id", "VARCHAR", null, false, false)
        );
        var request = new CreateCredentialDefinitionsRequestDto(
                "attestation-1",
                CREDENTIAL_TYPE,
                ID,
                mappings,
                3600L,
                FORMAT,
                TABLE_NAME,
                columns
        );

        when(databaseService.isValidValue(CREDENTIAL_TYPE)).thenReturn(true);
        when(databaseService.isValidIdentifier(ID)).thenReturn(true);
        when(databaseService.isValidValue(FORMAT)).thenReturn(true);
        when(attestationService.getIdColumn(TABLE_NAME, PARTICIPANT_ID_ENCODED)).thenReturn(ID_COLUMN);
        when(databaseService.hasMatchingColumnNames(mappings, columns, ID_COLUMN)).thenReturn(true);
        when(databaseService.hasValidMappingProperties(mappings)).thenReturn(true);
        when(credentialDefinitionService.createCredentialDefinition(any())).thenReturn(ServiceResult.success());

        // Mock the chain: removeIdColumn called twice
        Collection<ColumnDto> columnsAfterFirstRemoval = List.of(
                new ColumnDto("name", "VARCHAR", null, false, false),
                new ColumnDto("id", "VARCHAR", null, false, false)
        );
        Collection<ColumnDto> columnsAfterSecondRemoval = List.of(
                new ColumnDto("name", "VARCHAR", null, false, false)
        );

        when(databaseService.removeIdColumn(columns, ID_COLUMN)).thenReturn(columnsAfterFirstRemoval);
        when(databaseService.removeIdColumn(columnsAfterFirstRemoval, "id")).thenReturn(columnsAfterSecondRemoval);
        when(databaseService.alterTable(TABLE_NAME, columnsAfterSecondRemoval)).thenReturn(Result.success("Table altered"));

        // Act
        controller.setCredentialDefinitions(PARTICIPANT_ID_ENCODED, request);

        // Assert
        verify(databaseService).removeIdColumn(columns, ID_COLUMN);
        verify(databaseService).removeIdColumn(columnsAfterFirstRemoval, "id");
        verify(databaseService).alterTable(TABLE_NAME, columnsAfterSecondRemoval);
    }
}
