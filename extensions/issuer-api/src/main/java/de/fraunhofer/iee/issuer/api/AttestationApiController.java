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
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.edc.issuerservice.spi.issuance.attestation.AttestationDefinitionService;
import org.eclipse.edc.issuerservice.spi.issuance.model.AttestationDefinition;
import org.eclipse.edc.spi.result.Result;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Path("/v1alpha/participants/{participantContextId}/database-attestations")
public class AttestationApiController {

    private final AttestationDefinitionService attestationDefinitionService;
    private final AttestationDataService attestationDataService;

    public AttestationApiController(AttestationDefinitionService attestationDefinitionService,
                                    AttestationDataService attestationDataService) {
        this.attestationDefinitionService = attestationDefinitionService;
        this.attestationDataService = attestationDataService;
    }

    @POST
    public Response setAttestation(@PathParam("participantContextId") String participantContextId,
                                     CreateAttestationRequestDto request) {
        // Decode Base64-encoded participantContextId
        var decodedParticipantContextId = new String(
                Base64.getUrlDecoder().decode(participantContextId),
                StandardCharsets.UTF_8
        );

        // Create AttestationDefinition
        var attestationDefinition = AttestationDefinition.Builder.newInstance()
                .participantContextId(decodedParticipantContextId)
                .attestationType(request.attestationType())
                .id(request.id())
                .configuration(request.configuration())
                .build();

        // Store attestation definition
        var createResult = attestationDefinitionService.createAttestation(attestationDefinition);

        if (createResult.failed()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "success", false,
                            "message", createResult.getFailureDetail()
                    ))
                    .build();
        }

        // If attestationType is "database", create the table
        if ("database".equalsIgnoreCase(request.attestationType())) {
            var tableCreationResult = createDatabaseTable(request.configuration());
            if (tableCreationResult.failed()) {
                // Rollback: delete the attestation definition
                attestationDefinitionService.deleteAttestation(request.id());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity(Map.of(
                                "success", false,
                                "message", "Table creation failed: " + tableCreationResult.getFailureDetail()
                        ))
                        .build();
            }
        } else {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "success", false,
                            "message", "only 'database' as attestation type supported"
                    ))
                    .build();
        }

        return Response.status(Response.Status.OK)
                .entity(Map.of(
                        "success", true,
                        "message", "Attestation created successfully"
                ))
                .build();
    }

    private Result<String> createDatabaseTable(Map<String, Object> configuration) {
        // Extract tableName and columns from configuration
        var tableName = (String) configuration.get("tableName");
        if (tableName == null || tableName.isEmpty()) {
            return Result.failure("tableName is required in configuration");
        }

        var dataSourceName = (String) configuration.get("dataSourceName");
        if (dataSourceName == null || !dataSourceName.equals("default")) {
            return Result.failure("only default as dataSourceName supported");
        }

        var idColumn = (String) configuration.get("idColumn");
        if (idColumn == null || idColumn.isEmpty()) {
            return Result.failure("idColumn is required in configuration");
        }

        List<Map<String, Object>> columnsList = new ArrayList<>();

        columnsList.add(Map.of(
            "name", "id",
            "data_type", "UUID",
            "nullable", false,
            "primary_key", true,
            "default", "gen_random_uuid()"
        ));

        columnsList.add(Map.of(
                "name", idColumn,
                "data_type", "varchar",
                "nullable", false,
                "primary_key", false
        ));

        // Call AttestationDataService to create the table
        return attestationDataService.createTable(tableName, columnsList);
    }
}
