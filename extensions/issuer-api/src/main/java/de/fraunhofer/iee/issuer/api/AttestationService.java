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

import org.eclipse.edc.issuerservice.spi.issuance.credentialdefinition.CredentialDefinitionService;
import org.eclipse.edc.issuerservice.spi.issuance.attestation.AttestationDefinitionService;
import org.eclipse.edc.spi.query.Criterion;
import org.eclipse.edc.spi.query.QuerySpec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;

public class AttestationService {

    private final CredentialDefinitionService credentialDefinitionService;
    private final AttestationDefinitionService attestationDefinitionService;

    public AttestationService(CredentialDefinitionService credentialDefinitionService, AttestationDefinitionService attestationDefinitionService) {
        this.credentialDefinitionService = credentialDefinitionService;
        this.attestationDefinitionService = attestationDefinitionService;
    }

    public List<String> getUsedAttestationIds(Criterion criterionParticipant) {
        List<String> result = new ArrayList<>();

        var querySpec = QuerySpec.Builder.newInstance()
                .filter(criterionParticipant)
                .build();

        var queryResult = credentialDefinitionService.queryCredentialDefinitions(querySpec);

        if (queryResult.succeeded()) {

            var defs = queryResult.getContent();
            for (var def : defs) {
                var attestations = def.getAttestations();
                result.addAll(attestations);
            }
        }
        return result;
    }

    public List<String> getUnusedAttestationIds(Criterion criterionParticipant) {
        List<String> result = new ArrayList<>();

        // Validate input
        if (criterionParticipant == null) {
            return result;
        }

        var criterionType = new Criterion("attestationType", "=", "database");
        var querySpec = QuerySpec.Builder.newInstance()
                .filter(criterionParticipant)
                .filter(criterionType)
                .build();

        var queryResult = attestationDefinitionService.queryAttestations(querySpec);

        if (queryResult.succeeded()) {
            var usedAttestations = new HashSet<>(this.getUsedAttestationIds(criterionParticipant));
            var defs = queryResult.getContent();

            if (defs != null) {
                for (var def : defs) {
                    var attestation = def.getId();
                    if (attestation != null && !usedAttestations.contains(attestation)) {
                        result.add(attestation);
                    }
                }
            }
        }
        return result;
    }

    public String getIDColumn(String tableName, String participantContextId) {
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

        if (queryResult.succeeded()) {
            for (var attestation : queryResult.getContent()) {
                var configuration = attestation.getConfiguration();
                var att_tableName = (String) configuration.get("tableName");

                if (att_tableName != null && att_tableName.equals(tableName)) {
                    return (String) configuration.get("idColumn");
                }
            }
        }

        return null;
    }
}
