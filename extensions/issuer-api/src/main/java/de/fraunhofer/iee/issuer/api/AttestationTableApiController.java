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

import de.fraunhofer.iee.issuer.api.model.AttestationTableDto;
import de.fraunhofer.iee.issuer.api.model.ColumnInfoDto;
import de.fraunhofer.iee.issuer.api.model.InsertDataRequestDto;
import de.fraunhofer.iee.issuer.api.model.DeleteDataRequestDto;
import de.fraunhofer.iee.issuer.api.model.UpdateDataRequestDto;
import de.fraunhofer.iee.issuer.api.model.TableSchemaDto;
import jakarta.ws.rs.*;
import org.eclipse.edc.issuerservice.spi.issuance.attestation.AttestationDefinitionService;
import org.eclipse.edc.spi.query.Criterion;
import org.eclipse.edc.spi.query.QuerySpec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Path("/v1alpha/participants/{participantContextId}/attestation-tables")
public class AttestationTableApiController {

    private final AttestationDefinitionService attestationDefinitionService;
    private final AttestationDataService attestationDataService;
    private final AttestationService attestationService;

    public AttestationTableApiController(AttestationDefinitionService attestationDefinitionService,
                                         AttestationDataService attestationDataService,
                                         AttestationService attestationService) {
        this.attestationDefinitionService = attestationDefinitionService;
        this.attestationDataService = attestationDataService;
        this.attestationService = attestationService;
    }

    @GET
    public Collection<AttestationTableDto> getTables(@PathParam("participantContextId") String participantContextId) {
        // Decode Base64-encoded participantContextId
        var decodedParticipantContextId = new String(
                Base64.getUrlDecoder().decode(participantContextId),
                StandardCharsets.UTF_8
        );

        var criterionParticipant = new Criterion("participantContextId", "=", decodedParticipantContextId);
        var criterionType = new Criterion("attestationType", "=", "database");
        var querySpec = QuerySpec.Builder.newInstance()
                .filter(criterionParticipant)
                .filter(criterionType)
                .build();

        var queryResult = attestationDefinitionService.queryAttestations(querySpec);

        if (queryResult.failed()) {
            return new ArrayList<>();
        }

        var attestations = queryResult.getContent();
        var result = new ArrayList<AttestationTableDto>();

        // Get all used attestation IDs
        var usedAttestationIds = attestationService.getUsedAttestationIds(criterionParticipant);

        for (var attestation : attestations) {
            var config = attestation.getConfiguration();

            String tableName = null;
            String idColumn = null;
            String dataSourceName = null;

            for (var entry : config.entrySet()) {
                switch (entry.getKey()) {
                    case "tableName":
                        tableName = (String) entry.getValue();
                        break;
                    case "idColumn":
                        idColumn = (String) entry.getValue();
                        break;
                    case "dataSourceName":
                        dataSourceName = (String) entry.getValue();
                        break;
                    default:
                        break;
                }
            }

            if (tableName != null) {
                // Get real schema from database
                var schemaResult = attestationDataService.getTableSchema(tableName);
                Map<String, Object> tableParams = new LinkedHashMap<>();
                List<String> primaryKeys = new ArrayList<>();

                if (schemaResult.succeeded()) {
                    var schema = schemaResult.getContent();
                    primaryKeys = schema.primaryKeys();

                    // Convert ColumnInfo to simple name -> type map - preserve order
                    for (var entry : schema.columns().entrySet()) {
                        tableParams.put(entry.getKey(), entry.getValue().dataType());
                    }
                } else {
                    // Fallback if schema retrieval fails
                    tableParams.put("error", "Failed to retrieve schema: " + schemaResult.getFailureDetail());
                }

                // Set AttestationTable to active when it's used in a Credential Definition
                var attestationId = attestation.getId();
                var active = usedAttestationIds.contains(attestationId);

                result.add(new AttestationTableDto(tableName, idColumn, dataSourceName, tableParams, primaryKeys, active));
            }
        }

        return result;
    }

    @GET
    @Path("/table-data")
    public List<Map<String, Object>> getDataAttestations(@PathParam("participantContextId") String participantContextId,
                                                         @QueryParam("tableName") String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            return new ArrayList<>();
        }

        var dataResult = attestationDataService.queryDataAttestations(tableName);

        if (dataResult.failed()) {
            return new ArrayList<>();
        }

        return dataResult.getContent();
    }

    @POST
    @Path("table-data")
    public Map<String, Object> setDataAttestations(@PathParam("participantContextId") String participantContextId,
                                                   InsertDataRequestDto request) {
        if (request == null || request.tableName() == null || request.tableName().isEmpty()) {
            return Map.of(
                    "success", false,
                    "message", "tableName is required"
            );
        }

        if (request.data() == null || request.data().isEmpty()) {
            return Map.of(
                    "success", false,
                    "message", "data is required"
            );
        }

        var result = attestationDataService.setDataAttestations(request.tableName(), request.data());

        if (result.succeeded()) {
            return Map.of(
                    "success", true,
                    "message", result.getContent()
            );
        } else {
            return Map.of(
                    "success", false,
                    "message", result.getFailureDetail()
            );
        }
    }

    @POST
    @Path("table-data/delete")
    public Map<String, Object> deleteDataAttestations(@PathParam("participantContextId") String participantContextId,
                                                      DeleteDataRequestDto request) {
        if (request == null || request.tableName() == null || request.tableName().isEmpty()) {
            return Map.of(
                    "success", false,
                    "message", "tableName is required"
            );
        }

        if (request.ids() == null || request.ids().isEmpty()) {
            return Map.of(
                    "success", false,
                    "message", "ids are required"
            );
        }

        var result = attestationDataService.deleteDataAttestations(request.tableName(), request.ids());

        if (result.succeeded()) {
            return Map.of(
                    "success", true,
                    "message", result.getContent()
            );
        } else {
            return Map.of(
                    "success", false,
                    "message", result.getFailureDetail()
            );
        }
    }

    @PUT
    @Path("/table-data")
    public Map<String, Object> setDataAttestations(@PathParam("participantContextId") String participantContextId, UpdateDataRequestDto request) {

        if (request == null || request.tableName() == null || request.tableName().isEmpty()) {
            return Map.of(
                    "success", false,
                    "message", "tableName is required"
            );
        }

        if (request.ids() == null || request.ids().isEmpty()) {
            return Map.of(
                    "success", false,
                    "message", "ids are required"
            );
        }

        if (request.data() == null || request.data().isEmpty()) {
            return Map.of(
                    "success", false,
                    "message", "data is required"
            );
        }

        var result = attestationDataService.updateDataAttestations(request.tableName(), request.ids(), request.data());

        if (result.succeeded()) {
            return Map.of(
                    "success", true,
                    "message", result.getContent()
            );
        } else {
            return Map.of(
                    "success", false,
                    "message", result.getFailureDetail()
            );
        }


    }


    @GET
    @Path("/table-schema")
    public TableSchemaDto getTableSchema(@PathParam("participantContextId") String participantContextId,
                                         @QueryParam("tableName") String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            var errorColumns = Map.of("error", new ColumnInfoDto("VARCHAR", false, "tableName query parameter is required"));
            return new TableSchemaDto("error", null, errorColumns, List.of());
        }

        var result = attestationDataService.getTableSchema(tableName);
        if (result.failed()) {
            var errorColumns = Map.of("error", new ColumnInfoDto("VARCHAR", false, result.getFailureDetail()));
            return new TableSchemaDto(tableName, null, errorColumns, List.of());
        }

        var idColumn = attestationService.getIDColumn(tableName, participantContextId);
        if (idColumn == null) {
            var errorColumns = Map.of("error", new ColumnInfoDto("VARCHAR", false, "idColumn not found"));
            return new TableSchemaDto(tableName, null, errorColumns, List.of());
        }

        var schemaResult = result.getContent();

        // Convert ColumnInfo to ColumnInfoDto - preserve order with LinkedHashMap
        var columnDtos = schemaResult.columns().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new ColumnInfoDto(
                                entry.getValue().dataType(),
                                entry.getValue().isNullable(),
                                entry.getValue().defaultValue()
                        ),
                        (v1, v2) -> v1,
                        LinkedHashMap::new
                ));

        return new TableSchemaDto(tableName, idColumn, columnDtos, schemaResult.primaryKeys());
    }
}
