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

import org.eclipse.edc.issuerservice.spi.issuance.attestation.AttestationDefinitionService;
import org.eclipse.edc.issuerservice.spi.issuance.credentialdefinition.CredentialDefinitionService;
import org.eclipse.edc.issuerservice.spi.issuance.model.AttestationDefinition;
import org.eclipse.edc.issuerservice.spi.issuance.model.CredentialDefinition;
import org.eclipse.edc.spi.query.Criterion;
import org.eclipse.edc.spi.result.ServiceResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttestationServiceTest {

    private static final String PARTICIPANT_ID = "test-participant";
    private static final String PARTICIPANT_ID_ENCODED = Base64.getUrlEncoder().encodeToString(PARTICIPANT_ID.getBytes());
    private static final String TABLE_NAME = "test_table";
    private static final String ID_COLUMN = "user_id";

    @Mock
    private CredentialDefinitionService credentialDefinitionService;

    @Mock
    private AttestationDefinitionService attestationDefinitionService;

    private AttestationService attestationService;
    private Criterion criterionParticipant;

    @BeforeEach
    void setUp() {
        attestationService = new AttestationService(credentialDefinitionService, attestationDefinitionService);
        criterionParticipant = new Criterion("participantContextId", "=", PARTICIPANT_ID);
    }

    // ========== Tests for getUsedAttestationIds() ==========

    @Test
    void getUsedAttestationIds_whenCredentialDefinitionsExist_returnsAttestationIds() {
        // Arrange
        var credentialDef1 = CredentialDefinition.Builder.newInstance()
                .id("cred-1")
                .participantContextId(PARTICIPANT_ID)
                .credentialType("TestCredential")
                .attestations(List.of("attestation-1", "attestation-2"))
                .mappings(List.of())
                .validity(3600L)
                .format("jwt_vc")
                .jsonSchema("{}")
                .build();

        var credentialDef2 = CredentialDefinition.Builder.newInstance()
                .id("cred-2")
                .participantContextId(PARTICIPANT_ID)
                .credentialType("TestCredential")
                .attestations(List.of("attestation-3"))
                .mappings(List.of())
                .validity(3600L)
                .format("jwt_vc")
                .jsonSchema("{}")
                .build();

        when(credentialDefinitionService.queryCredentialDefinitions(any()))
                .thenReturn(ServiceResult.success(List.of(credentialDef1, credentialDef2)));

        // Act
        var result = attestationService.getUsedAttestationIds(criterionParticipant);

        // Assert
        assertThat(result).hasSize(3);
        assertThat(result).containsExactlyInAnyOrder("attestation-1", "attestation-2", "attestation-3");
    }

    @Test
    void getUsedAttestationIds_whenNoCredentialDefinitions_returnsEmptyList() {
        // Arrange
        when(credentialDefinitionService.queryCredentialDefinitions(any()))
                .thenReturn(ServiceResult.success(List.of()));

        // Act
        var result = attestationService.getUsedAttestationIds(criterionParticipant);

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void getUsedAttestationIds_whenQueryFails_returnsEmptyList() {
        // Arrange
        when(credentialDefinitionService.queryCredentialDefinitions(any()))
                .thenReturn(ServiceResult.badRequest("Query failed"));

        // Act
        var result = attestationService.getUsedAttestationIds(criterionParticipant);

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void getUsedAttestationIds_whenMultipleCredentialDefinitionsShareAttestation_returnsAllIds() {
        // Arrange
        var credentialDef1 = CredentialDefinition.Builder.newInstance()
                .id("cred-1")
                .participantContextId(PARTICIPANT_ID)
                .credentialType("TestCredential")
                .attestations(List.of("attestation-1"))
                .mappings(List.of())
                .validity(3600L)
                .format("jwt_vc")
                .jsonSchema("{}")
                .build();

        var credentialDef2 = CredentialDefinition.Builder.newInstance()
                .id("cred-2")
                .participantContextId(PARTICIPANT_ID)
                .credentialType("TestCredential")
                .attestations(List.of("attestation-1"))
                .mappings(List.of())
                .validity(3600L)
                .format("jwt_vc")
                .jsonSchema("{}")
                .build();

        when(credentialDefinitionService.queryCredentialDefinitions(any()))
                .thenReturn(ServiceResult.success(List.of(credentialDef1, credentialDef2)));

        // Act
        var result = attestationService.getUsedAttestationIds(criterionParticipant);

        // Assert
        assertThat(result).hasSize(2);
        assertThat(result).containsExactly("attestation-1", "attestation-1");
    }

    // ========== Tests for getUnusedAttestationIds() ==========

    @Test
    void getUnusedAttestationIds_whenAttestationsExist_returnsUnusedIds() {
        // Arrange
        Map<String, Object> config1 = new HashMap<>();
        config1.put("tableName", "table1");
        config1.put("idColumn", "id1");

        Map<String, Object> config2 = new HashMap<>();
        config2.put("tableName", "table2");
        config2.put("idColumn", "id2");

        Map<String, Object> config3 = new HashMap<>();
        config3.put("tableName", "table3");
        config3.put("idColumn", "id3");

        var attestation1 = AttestationDefinition.Builder.newInstance()
                .id("attestation-1")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config1)
                .build();

        var attestation2 = AttestationDefinition.Builder.newInstance()
                .id("attestation-2")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config2)
                .build();

        var attestation3 = AttestationDefinition.Builder.newInstance()
                .id("attestation-3")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config3)
                .build();

        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of(attestation1, attestation2, attestation3)));

        // Only attestation-1 is used
        var credentialDef = CredentialDefinition.Builder.newInstance()
                .id("cred-1")
                .participantContextId(PARTICIPANT_ID)
                .credentialType("TestCredential")
                .attestations(List.of("attestation-1"))
                .mappings(List.of())
                .validity(3600L)
                .format("jwt_vc")
                .jsonSchema("{}")
                .build();

        when(credentialDefinitionService.queryCredentialDefinitions(any()))
                .thenReturn(ServiceResult.success(List.of(credentialDef)));

        // Act
        var result = attestationService.getUnusedAttestationIds(criterionParticipant);

        // Assert
        assertThat(result).hasSize(2);
        assertThat(result).containsExactlyInAnyOrder("attestation-2", "attestation-3");
    }

    @Test
    void getUnusedAttestationIds_whenAllAttestationsAreUsed_returnsEmptyList() {
        // Arrange
        Map<String, Object> config = new HashMap<>();
        config.put("tableName", "table1");

        var attestation = AttestationDefinition.Builder.newInstance()
                .id("attestation-1")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config)
                .build();

        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of(attestation)));

        var credentialDef = CredentialDefinition.Builder.newInstance()
                .id("cred-1")
                .participantContextId(PARTICIPANT_ID)
                .credentialType("TestCredential")
                .attestations(List.of("attestation-1"))
                .mappings(List.of())
                .validity(3600L)
                .format("jwt_vc")
                .jsonSchema("{}")
                .build();

        when(credentialDefinitionService.queryCredentialDefinitions(any()))
                .thenReturn(ServiceResult.success(List.of(credentialDef)));

        // Act
        var result = attestationService.getUnusedAttestationIds(criterionParticipant);

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void getUnusedAttestationIds_whenCriterionIsNull_returnsEmptyList() {
        // Act
        var result = attestationService.getUnusedAttestationIds(null);

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void getUnusedAttestationIds_whenAttestationQueryFails_returnsEmptyList() {
        // Arrange
        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.badRequest("Query failed"));

        // Act
        var result = attestationService.getUnusedAttestationIds(criterionParticipant);

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void getUnusedAttestationIds_whenNoAttestationsExist_returnsEmptyList() {
        // Arrange
        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of()));
        when(credentialDefinitionService.queryCredentialDefinitions(any()))
                .thenReturn(ServiceResult.success(List.of()));

        // Act
        var result = attestationService.getUnusedAttestationIds(criterionParticipant);

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void getUnusedAttestationIds_filtersOutUsedAttestations() {
        // Arrange
        Map<String, Object> config1 = new HashMap<>();
        config1.put("tableName", "table1");

        Map<String, Object> config2 = new HashMap<>();
        config2.put("tableName", "table2");

        Map<String, Object> config3 = new HashMap<>();
        config3.put("tableName", "table3");

        var attestation1 = AttestationDefinition.Builder.newInstance()
                .id("attestation-1")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config1)
                .build();

        var attestation2 = AttestationDefinition.Builder.newInstance()
                .id("attestation-2")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config2)
                .build();

        var attestation3 = AttestationDefinition.Builder.newInstance()
                .id("attestation-3")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config3)
                .build();

        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of(attestation1, attestation2, attestation3)));

        // attestation-1 and attestation-3 are used
        var credentialDef = CredentialDefinition.Builder.newInstance()
                .id("cred-1")
                .participantContextId(PARTICIPANT_ID)
                .credentialType("TestCredential")
                .attestations(List.of("attestation-1", "attestation-3"))
                .mappings(List.of())
                .validity(3600L)
                .format("jwt_vc")
                .jsonSchema("{}")
                .build();

        when(credentialDefinitionService.queryCredentialDefinitions(any()))
                .thenReturn(ServiceResult.success(List.of(credentialDef)));

        // Act
        var result = attestationService.getUnusedAttestationIds(criterionParticipant);

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result).containsExactly("attestation-2");
    }

    // ========== Tests for getIdColumn() ==========

    @Test
    void getIdColumn_whenAttestationExists_returnsIdColumn() {
        // Arrange
        Map<String, Object> config = new HashMap<>();
        config.put("tableName", TABLE_NAME);
        config.put("idColumn", ID_COLUMN);

        var attestation = AttestationDefinition.Builder.newInstance()
                .id("attestation-1")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config)
                .build();

        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of(attestation)));

        // Act
        var result = attestationService.getIdColumn(TABLE_NAME, PARTICIPANT_ID_ENCODED);

        // Assert
        assertThat(result).isEqualTo(ID_COLUMN);
    }

    @Test
    void getIdColumn_whenTableNameIsUpperCase_convertsToLowerCase() {
        // Arrange
        Map<String, Object> config = new HashMap<>();
        config.put("tableName", TABLE_NAME.toLowerCase());
        config.put("idColumn", ID_COLUMN);

        var attestation = AttestationDefinition.Builder.newInstance()
                .id("attestation-1")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config)
                .build();

        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of(attestation)));

        // Act
        var result = attestationService.getIdColumn(TABLE_NAME.toUpperCase(), PARTICIPANT_ID_ENCODED);

        // Assert
        assertThat(result).isEqualTo(ID_COLUMN);
    }

    @Test
    void getIdColumn_whenMultipleAttestationsExist_returnsMatchingIdColumn() {
        // Arrange
        Map<String, Object> config1 = new HashMap<>();
        config1.put("tableName", "other_table");
        config1.put("idColumn", "other_id");

        Map<String, Object> config2 = new HashMap<>();
        config2.put("tableName", TABLE_NAME);
        config2.put("idColumn", ID_COLUMN);

        var attestation1 = AttestationDefinition.Builder.newInstance()
                .id("attestation-1")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config1)
                .build();

        var attestation2 = AttestationDefinition.Builder.newInstance()
                .id("attestation-2")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config2)
                .build();

        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of(attestation1, attestation2)));

        // Act
        var result = attestationService.getIdColumn(TABLE_NAME, PARTICIPANT_ID_ENCODED);

        // Assert
        assertThat(result).isEqualTo(ID_COLUMN);
    }

    @Test
    void getIdColumn_whenNoMatchingTableName_returnsNull() {
        // Arrange
        Map<String, Object> config = new HashMap<>();
        config.put("tableName", "other_table");
        config.put("idColumn", "other_id");

        var attestation = AttestationDefinition.Builder.newInstance()
                .id("attestation-1")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config)
                .build();

        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of(attestation)));

        // Act
        var result = attestationService.getIdColumn(TABLE_NAME, PARTICIPANT_ID_ENCODED);

        // Assert
        assertThat(result).isNull();
    }

    @Test
    void getIdColumn_whenQueryFails_returnsNull() {
        // Arrange
        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.badRequest("Query failed"));

        // Act
        var result = attestationService.getIdColumn(TABLE_NAME, PARTICIPANT_ID_ENCODED);

        // Assert
        assertThat(result).isNull();
    }

    @Test
    void getIdColumn_whenNoAttestationsExist_returnsNull() {
        // Arrange
        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of()));

        // Act
        var result = attestationService.getIdColumn(TABLE_NAME, PARTICIPANT_ID_ENCODED);

        // Assert
        assertThat(result).isNull();
    }

    @Test
    void getIdColumn_decodesBase64ParticipantId() {
        // Arrange
        Map<String, Object> config = new HashMap<>();
        config.put("tableName", TABLE_NAME);
        config.put("idColumn", ID_COLUMN);

        var attestation = AttestationDefinition.Builder.newInstance()
                .id("attestation-1")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config)
                .build();

        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of(attestation)));

        // Act
        var result = attestationService.getIdColumn(TABLE_NAME, PARTICIPANT_ID_ENCODED);

        // Assert
        assertThat(result).isEqualTo(ID_COLUMN);
        verify(attestationDefinitionService).queryAttestations(any());
    }

    // ========== Tests for hasNoAttestationForTable() ==========

    @Test
    void hasNoAttestationForTable_whenAttestationExists_returnsFalse() {
        // Arrange
        Map<String, Object> config = new HashMap<>();
        config.put("tableName", TABLE_NAME);
        config.put("idColumn", ID_COLUMN);

        var attestation = AttestationDefinition.Builder.newInstance()
                .id("attestation-1")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config)
                .build();

        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of(attestation)));

        // Act
        var result = attestationService.hasNoAttestationForTable(TABLE_NAME, PARTICIPANT_ID);

        // Assert
        assertThat(result).isFalse();
    }

    @Test
    void hasNoAttestationForTable_whenNoAttestationExists_returnsTrue() {
        // Arrange
        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of()));

        // Act
        var result = attestationService.hasNoAttestationForTable(TABLE_NAME, PARTICIPANT_ID);

        // Assert
        assertThat(result).isTrue();
    }

    @Test
    void hasNoAttestationForTable_whenTableNameIsNull_returnsTrue() {
        // Act
        var result = attestationService.hasNoAttestationForTable(null, PARTICIPANT_ID);

        // Assert
        assertThat(result).isTrue();
    }

    @Test
    void hasNoAttestationForTable_whenTableNameIsEmpty_returnsTrue() {
        // Act
        var result = attestationService.hasNoAttestationForTable("", PARTICIPANT_ID);

        // Assert
        assertThat(result).isTrue();
    }

    @Test
    void hasNoAttestationForTable_whenParticipantIdIsNull_returnsTrue() {
        // Act
        var result = attestationService.hasNoAttestationForTable(TABLE_NAME, null);

        // Assert
        assertThat(result).isTrue();
    }

    @Test
    void hasNoAttestationForTable_whenParticipantIdIsEmpty_returnsTrue() {
        // Act
        var result = attestationService.hasNoAttestationForTable(TABLE_NAME, "");

        // Assert
        assertThat(result).isTrue();
    }

    @Test
    void hasNoAttestationForTable_whenQueryFails_returnsTrue() {
        // Arrange
        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.badRequest("Query failed"));

        // Act
        var result = attestationService.hasNoAttestationForTable(TABLE_NAME, PARTICIPANT_ID);

        // Assert
        assertThat(result).isTrue();
    }

    @Test
    void hasNoAttestationForTable_whenTableNameIsUpperCase_convertsToLowerCase() {
        // Arrange
        Map<String, Object> config = new HashMap<>();
        config.put("tableName", TABLE_NAME.toLowerCase());

        var attestation = AttestationDefinition.Builder.newInstance()
                .id("attestation-1")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config)
                .build();

        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of(attestation)));

        // Act
        var result = attestationService.hasNoAttestationForTable(TABLE_NAME.toUpperCase(), PARTICIPANT_ID);

        // Assert
        assertThat(result).isFalse();
    }

    @Test
    void hasNoAttestationForTable_whenMultipleAttestationsExistButNoMatch_returnsTrue() {
        // Arrange
        Map<String, Object> config1 = new HashMap<>();
        config1.put("tableName", "other_table1");

        Map<String, Object> config2 = new HashMap<>();
        config2.put("tableName", "other_table2");

        var attestation1 = AttestationDefinition.Builder.newInstance()
                .id("attestation-1")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config1)
                .build();

        var attestation2 = AttestationDefinition.Builder.newInstance()
                .id("attestation-2")
                .participantContextId(PARTICIPANT_ID)
                .attestationType("database")
                .configuration(config2)
                .build();

        when(attestationDefinitionService.queryAttestations(any()))
                .thenReturn(ServiceResult.success(List.of(attestation1, attestation2)));

        // Act
        var result = attestationService.hasNoAttestationForTable(TABLE_NAME, PARTICIPANT_ID);

        // Assert
        assertThat(result).isTrue();
    }
}
