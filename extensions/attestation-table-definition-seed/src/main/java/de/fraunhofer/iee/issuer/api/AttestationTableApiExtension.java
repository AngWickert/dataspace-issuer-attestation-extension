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
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.web.spi.WebService;

@Extension(value = "Attestation Table API Extension")
public class AttestationTableApiExtension implements ServiceExtension {

    public static final String NAME = "Attestation Table API Extension";

    @Inject
    private WebService webService;

    @Inject
    private AttestationDefinitionService attestationDefinitionService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        var controller = new AttestationTableApiController(attestationDefinitionService);
        webService.registerResource(IdentityHubApiContext.ISSUERADMIN, controller);
        context.getMonitor().info("Attestation Table API registered at: /api/admin/v1alpha/participants/{participantContextId}/attestation-tables");
    }
}
