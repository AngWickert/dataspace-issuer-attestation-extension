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

import de.fraunhofer.iee.issuer.api.model.CreateAttestationRequestDto;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttestationApiControllerTest {

    private static final String PARTICIPANT_ID = "test-participant";
    private static final String PARTICIPANT_ID_ENCODED = Base64.getUrlEncoder().encodeToString(PARTICIPANT_ID.getBytes());

    @Mock
    private AttestationDefinitionService attestationDefinitionService;

    @Mock
    private DatabaseService databaseService;

    @Mock
    private AttestationService attestationService;

    private AttestationApiController controller;

    @BeforeEach
    void setUp() {
        controller = new AttestationApiController(attestationDefinitionService, databaseService, attestationService);
    }

    @Test
    void getAttestations_whenSuccess_returnsAttestations() {
        // Arrange - create AttestationDefinitionObjekt, for our expected result
        var attestation = AttestationDefinition.Builder.newInstance()
                .id("test-attestation")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(Map.of("key", "value"))
                .build();

        // Configure Mock – when this method is called later in getAttestations with any parameter, return the defined result
        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of(attestation)));
        when(attestationService.getUsedAttestationIds(any()))
                .thenReturn(List.of());

        // Act
        var result = controller.getAttestations(PARTICIPANT_ID_ENCODED);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);

        var dto = result.iterator().next();
        assertThat(dto.id()).isEqualTo("test-attestation");
        assertThat(dto.participantContextId()).isEqualTo(PARTICIPANT_ID);
        assertThat(dto.attestationType()).isEqualTo("database");
        assertThat(dto.active()).isFalse(); // Not used by any credential definition
    }

    @Test
    void getAttestations_whenEmpty_returnsEmptyList() {
        // Arrange
        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of()));

        // Act
        var result = controller.getAttestations(PARTICIPANT_ID_ENCODED);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    @Test
    void getAttestations_whenServiceFails_returnsEmptyList() {
        // Arrange
        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.badRequest("Something went wrong"));

        // Act
        var result = controller.getAttestations(PARTICIPANT_ID_ENCODED);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    @Test
    void getAttestations_whenUsedByCredentialDefinition_marksAsActive() {
        // Arrange
        var attestation = AttestationDefinition.Builder.newInstance()
                .id("active-attestation")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(Map.of("key", "value"))
                .build();

        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of(attestation)));
        when(attestationService.getUsedAttestationIds(any()))
                .thenReturn(List.of("active-attestation")); // This attestation is used

        // Act
        var result = controller.getAttestations(PARTICIPANT_ID_ENCODED);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result).hasSize(1);

        var dto = result.iterator().next();
        assertThat(dto.active()).isTrue(); // Marked as active
    }

    @Test
    void getAttestations_decodesBase64ParticipantId() {
        // Arrange
        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of()));

        // Act
        var result = controller.getAttestations(PARTICIPANT_ID_ENCODED);

        // Assert - No exception means Base64 decoding worked
        assertThat(result).isNotNull();
    }

    // ========== Tests for getUnusedAttestationIds() ==========

    @Test
    void getUnusedAttestationIds_whenSuccess_returnsIds() {
        // Arrange
        when(attestationService.getUnusedAttestationIds(any()))
                .thenReturn(List.of("attestation-1", "attestation-2"));

        // Act
        var result = controller.getUnusedAttestationIds(PARTICIPANT_ID_ENCODED);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result).hasSize(2);
        assertThat(result).contains("attestation-1", "attestation-2");
    }

    @Test
    void getUnusedAttestationIds_whenEmpty_returnsEmptyList() {
        // Arrange
        when(attestationService.getUnusedAttestationIds(any()))
                .thenReturn(List.of());

        // Act
        var result = controller.getUnusedAttestationIds(PARTICIPANT_ID_ENCODED);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    @Test
    void getUnusedAttestationIds_whenParticipantIdIsNull_returnsEmptyList() {
        // Act
        var result = controller.getUnusedAttestationIds(null);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    @Test
    void getUnusedAttestationIds_whenParticipantIdIsEmpty_returnsEmptyList() {
        // Act
        var result = controller.getUnusedAttestationIds("");

        // Assert
        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    @Test
    void getUnusedAttestationIds_whenInvalidBase64_returnsEmptyList() {
        // Act
        var result = controller.getUnusedAttestationIds("invalid-base64!");

        // Assert
        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    // ========== Tests for setAttestation() ==========

    @Test
    void setAttestation_whenSuccess_returnsOk() {
        // Arrange
        var request = new CreateAttestationRequestDto(
                "test-id",
                "database",
                Map.of(
                        "tableName", "test_table",
                        "dataSourceName", "default",
                        "idColumn", "user_id"
                )
        );

        when(databaseService.isValidIdentifier(any())).thenReturn(true);
        when(databaseService.isValidDbKey(any())).thenReturn(true);
        when(attestationDefinitionService.createAttestation(any()))
                .thenReturn(ServiceResult.success());
        when(databaseService.createTable(any(), any()))
                .thenReturn(Result.success("Table created"));

        // Act
        var response = controller.setAttestation(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(200);
        var entity = (Map<String, Object>) response.getEntity();
        assertThat(entity.get("success")).isEqualTo(true);
        assertThat(entity.get("message")).isEqualTo("Attestation created successfully");
    }

    @Test
    void setAttestation_whenAttestationTypeIsEmpty_returnsBadRequest() {
        // Arrange
        var request = new CreateAttestationRequestDto(
                "test-id",
                "",
                Map.of("key", "value")
        );

        // Act
        var response = controller.setAttestation(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(400);
        var entity = (Map<String, Object>) response.getEntity();
        assertThat(entity.get("success")).isEqualTo(false);
    }

    @Test
    void setAttestation_whenIdIsEmpty_returnsBadRequest() {
        // Arrange
        var request = new CreateAttestationRequestDto(
                "",
                "database",
                Map.of("key", "value")
        );

        // Act
        var response = controller.setAttestation(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void setAttestation_whenIdIsInvalid_returnsBadRequest() {
        // Arrange
        var request = new CreateAttestationRequestDto(
                "invalid-id!",
                "database",
                Map.of("key", "value")
        );

        when(databaseService.isValidIdentifier(any())).thenReturn(false);

        // Act
        var response = controller.setAttestation(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(400);
        var entity = (Map<String, Object>) response.getEntity();
        assertThat(entity.get("success")).isEqualTo(false);
    }

    @Test
    void setAttestation_whenConfigurationIsEmpty_returnsBadRequest() {
        // Arrange
        var request = new CreateAttestationRequestDto(
                "test-id",
                "database",
                Map.of()
        );

        when(databaseService.isValidIdentifier(any())).thenReturn(true);

        // Act
        var response = controller.setAttestation(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void setAttestation_whenAttestationServiceFails_returnsBadRequest() {
        // Arrange
        var request = new CreateAttestationRequestDto(
                "test-id",
                "database",
                Map.of("key", "value")
        );

        when(databaseService.isValidIdentifier(any())).thenReturn(true);
        when(attestationDefinitionService.createAttestation(any()))
                .thenReturn(ServiceResult.badRequest("Attestation already exists"));

        // Act
        var response = controller.setAttestation(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(400);
        var entity = (Map<String, Object>) response.getEntity();
        assertThat(entity.get("success")).isEqualTo(false);
        assertThat(entity.get("message").toString()).contains("already exists");
    }

    @Test
    void setAttestation_whenTableCreationFails_returnsInternalServerError() {
        // Arrange
        var request = new CreateAttestationRequestDto(
                "test-id",
                "database",
                Map.of(
                        "tableName", "test_table",
                        "dataSourceName", "default",
                        "idColumn", "user_id"
                )
        );

        when(databaseService.isValidIdentifier(any())).thenReturn(true);
        when(databaseService.isValidDbKey(any())).thenReturn(true);
        when(attestationDefinitionService.createAttestation(any()))
                .thenReturn(ServiceResult.success());
        when(databaseService.createTable(any(), any()))
                .thenReturn(Result.failure("Database connection failed"));
        when(attestationDefinitionService.deleteAttestation(any()))
                .thenReturn(ServiceResult.success());

        // Act
        var response = controller.setAttestation(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(500);
        var entity = (Map<String, Object>) response.getEntity();
        assertThat(entity.get("success")).isEqualTo(false);
        assertThat(entity.get("message").toString()).contains("Table creation failed");
    }

    @Test
    void setAttestation_whenAttestationTypeIsNotDatabase_returnsBadRequest() {
        // Arrange
        var request = new CreateAttestationRequestDto(
                "test-id",
                "file", // Not "database"
                Map.of("key", "value")
        );

        when(databaseService.isValidIdentifier(any())).thenReturn(true);
        when(attestationDefinitionService.createAttestation(any()))
                .thenReturn(ServiceResult.success());
        when(attestationDefinitionService.deleteAttestation(any()))
                .thenReturn(ServiceResult.success());

        // Act
        var response = controller.setAttestation(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(400);
        var entity = (Map<String, Object>) response.getEntity();
        assertThat(entity.get("success")).isEqualTo(false);
        assertThat(entity.get("message").toString()).contains("only 'database' as attestation type supported");
    }

    @Test
    void setAttestation_whenTableNameIsMissing_returnsInternalServerError() {
        // Arrange
        var request = new CreateAttestationRequestDto(
                "test-id",
                "database",
                Map.of(
                        // tableName is missing
                        "dataSourceName", "default",
                        "idColumn", "user_id"
                )
        );

        when(databaseService.isValidIdentifier(any())).thenReturn(true);
        when(attestationDefinitionService.createAttestation(any()))
                .thenReturn(ServiceResult.success());
        when(attestationDefinitionService.deleteAttestation(any()))
                .thenReturn(ServiceResult.success());

        // Act
        var response = controller.setAttestation(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(500);
        var entity = (Map<String, Object>) response.getEntity();
        assertThat(entity.get("success")).isEqualTo(false);
        assertThat(entity.get("message").toString()).contains("tableName");
    }

    @Test
    void setAttestation_whenDataSourceNameIsNotDefault_returnsInternalServerError() {
        // Arrange
        var request = new CreateAttestationRequestDto(
                "test-id",
                "database",
                Map.of(
                        "tableName", "test_table",
                        "dataSourceName", "other", // Not "default"
                        "idColumn", "user_id"
                )
        );

        when(databaseService.isValidIdentifier(any())).thenReturn(true);
        when(databaseService.isValidDbKey(any())).thenReturn(true);
        when(attestationDefinitionService.createAttestation(any()))
                .thenReturn(ServiceResult.success());
        when(attestationDefinitionService.deleteAttestation(any()))
                .thenReturn(ServiceResult.success());

        // Act
        var response = controller.setAttestation(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(500);
        var entity = (Map<String, Object>) response.getEntity();
        assertThat(entity.get("success")).isEqualTo(false);
        assertThat(entity.get("message").toString()).contains("dataSourceName");
    }

    @Test
    void setAttestation_whenIdColumnIsMissing_returnsInternalServerError() {
        // Arrange
        var request = new CreateAttestationRequestDto(
                "test-id",
                "database",
                Map.of(
                        "tableName", "test_table",
                        "dataSourceName", "default"
                        // idColumn is missing
                )
        );

        when(databaseService.isValidIdentifier(any())).thenReturn(true);
        when(databaseService.isValidDbKey(any())).thenReturn(true);
        when(attestationDefinitionService.createAttestation(any()))
                .thenReturn(ServiceResult.success());
        when(attestationDefinitionService.deleteAttestation(any()))
                .thenReturn(ServiceResult.success());

        // Act
        var response = controller.setAttestation(PARTICIPANT_ID_ENCODED, request);

        // Assert
        assertThat(response.getStatus()).isEqualTo(500);
        var entity = (Map<String, Object>) response.getEntity();
        assertThat(entity.get("success")).isEqualTo(false);
        assertThat(entity.get("message").toString()).contains("idColumn");
    }
}
