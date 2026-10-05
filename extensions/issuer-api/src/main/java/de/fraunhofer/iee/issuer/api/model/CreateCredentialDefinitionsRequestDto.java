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

package de.fraunhofer.iee.issuer.api.model;
import org.eclipse.edc.issuerservice.spi.issuance.model.MappingDefinition;

import java.util.Collection;

import static java.util.Objects.requireNonNull;

public record CreateCredentialDefinitionsRequestDto(
        String attestation,
        String credentialType,
        String id,
        Collection<MappingDefinition> mappings,
        long validity,
        String format,
        String tableName,
        Collection<ColumnDto> columns

) {
    public CreateCredentialDefinitionsRequestDto {
        requireNonNull(id, "id is required");
        requireNonNull(credentialType, "credentialType is required");
        requireNonNull(attestation, "attestation is required");
        requireNonNull(mappings, "mappings is required");
        requireNonNull(format, "format is required");
        requireNonNull(tableName, "tableName is required");
        requireNonNull(columns, "columns is required");
    }
}
