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

plugins {
    `java-library`
}

dependencies {
    implementation(libs.edc.ih.spi)
    implementation(libs.edc.ih.issuance.spi)
    implementation(libs.jakarta.rsApi)
    compileOnly(libs.edc.spi.web)
    compileOnly(libs.edc.transaction.spi)
    compileOnly(libs.edc.transaction.datasource.spi)

    // Test dependencies
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.edc.spi.web)
    testImplementation(libs.edc.transaction.spi)
    testImplementation(libs.edc.transaction.datasource.spi)
    testImplementation(libs.jersey.common)
}

tasks.test {
    useJUnitPlatform()
}
