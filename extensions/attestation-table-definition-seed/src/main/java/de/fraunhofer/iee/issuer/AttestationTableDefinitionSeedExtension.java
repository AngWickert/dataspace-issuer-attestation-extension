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

package de.fraunhofer.iee.issuer;

import org.eclipse.edc.issuerservice.spi.issuance.attestation.AttestationDefinitionService;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.query.Criterion;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import java.util.ArrayList;
import java.util.List;

@Extension(value = "Attestation Table Definition Seed Extension")
public class AttestationTableDefinitionSeedExtension implements ServiceExtension {

    @Inject
    private AttestationDefinitionService attestationDefinitionService;

    private Monitor monitor;

    @Override
    public void initialize(ServiceExtensionContext context) {
        this.monitor = context.getMonitor();
        this.monitor.info("Attestation Table Definition Seed Extension wird initialisiert!");
    }

    @Override
    public void start() {
        this.monitor.info("Hallo aus der Attestation Table Definition Seed Extension!");
        this.getAttestations();

        List<String> tableNames = this.getAttestionTables("super-user");
        this.monitor.info("Tabellen:");
        for (String tableName : tableNames) {
            this.monitor.info(tableName);
        }
    }

    public List<String> getAttestionTables(String participantContextId) {
        var criterionParticipant = new Criterion("participantContextId", "=", participantContextId);
        var criterionType = new Criterion("attestationType", "=", "database");
        var querySpec = QuerySpec.Builder.newInstance()
                .filter(criterionParticipant)
                .filter(criterionType)
                .build();

        var queryAttestations = this.attestationDefinitionService.queryAttestations(querySpec);
        var result = new ArrayList<String>();

        if (queryAttestations.succeeded()) {
            var attestations = queryAttestations.getContent();

            for (var attestation : attestations) {
                var config = attestation.getConfiguration();
                for (var entry : config.entrySet()) {
                    if (entry.getKey().equals("tableName")) {
                        result.add((String) entry.getValue());
                    }
                }
            }
        } else {
            this.monitor.warning("Fehler beim Abrufen der Attestations: " + queryAttestations.getFailureDetail());
        }

        return result;
    }

    public void getAttestations() {
        var querySpec = QuerySpec.Builder.newInstance().build();
        var result = this.attestationDefinitionService.queryAttestations(querySpec);

        if (result.succeeded()) {
            var attestations = result.getContent();
            this.monitor.info("Anzahl der Attestations: " + attestations.size());

            for (var attestation : attestations) {
                this.monitor.info("=== Attestation: " + attestation.getId() + " ===");
                this.monitor.info("  Typ: " + attestation.getAttestationType());
                this.monitor.info("  Participant Context ID: " + attestation.getParticipantContextId());

                var config = attestation.getConfiguration();
                this.monitor.info("  Konfiguration:");
                for (var entry : config.entrySet()) {
                    this.monitor.info("    " + entry.getKey() + " = " + entry.getValue());
                }
            }
        } else {
            this.monitor.warning("Fehler beim Abrufen der Attestations: " + result.getFailureDetail());
        }
    }
}
