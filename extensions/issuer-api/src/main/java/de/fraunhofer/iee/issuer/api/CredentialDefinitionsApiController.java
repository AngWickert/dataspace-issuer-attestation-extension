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
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.issuerservice.spi.issuance.credentialdefinition.CredentialDefinitionService;
import org.eclipse.edc.issuerservice.spi.issuance.model.CredentialDefinition;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.Result;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Path("/v1alpha/participants/{participantContextId}/database-credentialdefinitions")
public class CredentialDefinitionsApiController {

    private final CredentialDefinitionService credentialDefinitionService;
    private final DatabaseService databaseService;
    private final AttestationService attestationService;
    private final Monitor monitor;


    public CredentialDefinitionsApiController(CredentialDefinitionService credentialDefinitionService,
                                              DatabaseService databaseService, AttestationService attestationService,
                                              Monitor monitor) {
        this.credentialDefinitionService = credentialDefinitionService;
        this.databaseService = databaseService;
        this.attestationService = attestationService;
        this.monitor = monitor;
    }

    @POST
    public Response setCredentialDefinitions(@PathParam("participantContextId") String participantContextId,
                                             CreateCredentialDefinitionsRequestDto request) {
        monitor.info("=== Create Credential Definitions Request ===");
        monitor.info(String.format("ParticipantContextId (encoded): %s", participantContextId));

        // Decode Base64-encoded participantContextId
        var decodedParticipantContextId = new String(
                Base64.getUrlDecoder().decode(participantContextId),
                StandardCharsets.UTF_8
        );

        if (request == null || request.attestation() == null || request.attestation().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "success", false,
                            "message", "attestation is required"
                    )).build();
        }

        if (request.credentialType() == null || request.credentialType().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "success", false,
                            "message", "credentialType is required"
                    )).build();
        }

        if (request.id() == null || request.id().isEmpty() || !databaseService.isValidIdentifier(request.id())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "success", false,
                            "message", "valid id is required"
                    )).build();
        }

        if (request.mappings() == null || request.mappings().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "success", false,
                            "message", "mappings is required"
                    )).build();
        }

        if (request.mappings().stream().anyMatch(m -> "id".equals(m.input()))) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "success", false,
                            "message", "input with value 'id' is not allowed in mappings"
                    )).build();
        }

        if (request.format() == null || request.format().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "success", false,
                            "message", "format is required"
                    )).build();
        }

        var idColumn= attestationService.getIDColumn(request.tableName(), participantContextId);
        if (idColumn == null || idColumn.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "success", false,
                            "message", "can't find id column in table " + request.tableName()
                    )).build();
        }

        if (!databaseService.hasMatchingColumnNames(request.mappings(), request.columns(), idColumn)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "success", false,
                            "message", "mappings and columns do not match"
                    )).build();
        }

        // Create CredentialDefinition
        var credentialDefinition = CredentialDefinition.Builder.newInstance()
                .participantContextId(decodedParticipantContextId)
                .attestation(request.attestation())
                .credentialType(request.credentialType())
                .id(request.id())
                .jsonSchema("{}")
                .mappings(request.mappings())
                .validity(request.validity())
                .format(request.format())
                .build();

        // Store credential definitions
        var createResult = credentialDefinitionService.createCredentialDefinition(credentialDefinition);

        if (createResult.failed()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "success", false,
                            "message", createResult.getFailureDetail()
                    ))
                    .build();
        }

        var tableCreationResult = extendCredentialDefinitionsTable(request.tableName(), request.columns(), idColumn);
        if (tableCreationResult.failed()) {
            // Rollback: delete the credential definitions
            credentialDefinitionService.deleteCredentialDefinition(request.id());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of(
                            "success", false,
                            "message", "Table creation failed: " + tableCreationResult.getFailureDetail()
                    ))
                    .build();
        }


        return Response.status(Response.Status.OK)
                .entity(Map.of(
                        "success", true,
                        "message", "CredentialDefinitions created successfully"
                ))
                .build();
    }

    private Result<String> extendCredentialDefinitionsTable(String tableName, Collection<ColumnDto> columns, String idColumn) {

        if (tableName == null || tableName.isEmpty()) {
            return Result.failure("tableName is required");
        }

        if (idColumn == null || idColumn.isEmpty()) {
            return Result.failure("idColumn is required");
        }

        if (columns != null && !columns.isEmpty()) {
            // Call DatabaseService to alter the table
            return databaseService.alterTable(tableName, databaseService.removeIdColumn(databaseService.removeIdColumn(columns, idColumn), "id"));
        }

        return Result.failure("No columns added");
    }


}
