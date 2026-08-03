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

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import org.eclipse.edc.issuerservice.spi.issuance.attestation.AttestationDefinitionService;
import org.eclipse.edc.spi.query.Criterion;
import org.eclipse.edc.spi.query.QuerySpec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
@Path("/v1alpha/participants/{participantContextId}/attestation-tables")
public class AttestationTableApiController {

    private final AttestationDefinitionService attestationDefinitionService;

    public AttestationTableApiController(AttestationDefinitionService attestationDefinitionService) {
        this.attestationDefinitionService = attestationDefinitionService;
    }

    @GET
    public Collection<String> getTableNames(@PathParam("participantContextId") String participantContextId) {

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
        var result = new ArrayList<String>();

        for (var attestation : attestations) {
            var config = attestation.getConfiguration();
            for (var entry : config.entrySet()) {
                if (entry.getKey().equals("tableName")) {
                    result.add((String) entry.getValue());
                }
            }
        }

        return result;
    }
}
