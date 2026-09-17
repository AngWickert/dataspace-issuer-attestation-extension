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

import org.eclipse.edc.identityhub.spi.webcontext.IdentityHubApiContext;
import org.eclipse.edc.issuerservice.spi.issuance.attestation.AttestationDefinitionService;
import org.eclipse.edc.issuerservice.spi.issuance.credentialdefinition.CredentialDefinitionService;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.transaction.datasource.spi.DataSourceRegistry;
import org.eclipse.edc.transaction.spi.TransactionContext;
import org.eclipse.edc.web.spi.WebService;

@Extension(value = "Attestation API Extension")
public class AttestationApiExtension implements ServiceExtension {

    public static final String NAME = "Attestation API Extension";

    @Setting(description = "The datasource to be used for attestation data", defaultValue = DataSourceRegistry.DEFAULT_DATASOURCE, key = "edc.sql.store.attestation.datasource")
    private String dataSourceName;

    @Inject
    private WebService webService;

    @Inject
    private AttestationDefinitionService attestationDefinitionService;

    @Inject CredentialDefinitionService credentialDefinitionService;

    @Inject
    private DataSourceRegistry dataSourceRegistry;

    @Inject
    private TransactionContext transactionContext;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var attestationDataService = new AttestationDataService(
                dataSourceRegistry,
                dataSourceName,
                transactionContext
        );

        var attestationService = new AttestationService(credentialDefinitionService, attestationDefinitionService);

        var controller = new AttestationApiController(attestationDefinitionService, attestationDataService, attestationService);
        webService.registerResource(IdentityHubApiContext.ISSUERADMIN, controller);
        context.getMonitor().info("Attestation API registered at: /api/admin/v1alpha/participants/{participantContextId}/database-attestations");
    }
}
