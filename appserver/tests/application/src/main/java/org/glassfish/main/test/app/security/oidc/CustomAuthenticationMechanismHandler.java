/*
 * Copyright (c) 2026 Eclipse Foundation and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.main.test.app.security.oidc;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import jakarta.security.enterprise.AuthenticationException;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanismHandler;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.security.enterprise.authentication.mechanism.http.OpenIdAuthenticationMechanismDefinition;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static jakarta.interceptor.Interceptor.Priority.APPLICATION;

@OpenIdAuthenticationMechanismDefinition(
        qualifiers = {QualifierA.class},
        providerURI = "${oidcConfigQualifierA.issuerUri}",
        clientId = "${oidcConfigQualifierA.clientId}",
        clientSecret = "${oidcConfigQualifierA.clientSecret}",
        redirectURI = "${baseURL}/oidcredirecturi",
        jwksReadTimeout = 5000, jwksConnectTimeout = 5000)

@OpenIdAuthenticationMechanismDefinition(
        qualifiers = {QualifierB.class},
        providerURI = "${oidcConfigQualifierB.issuerUri}",
        clientId = "${oidcConfigQualifierB.clientId}",
        clientSecret = "${oidcConfigQualifierB.clientSecret}",
        redirectURI = "${baseURL}/oidcredirecturi",
        jwksReadTimeout = 5000, jwksConnectTimeout = 5000)

@Alternative
@Priority(APPLICATION)
@ApplicationScoped
public class CustomAuthenticationMechanismHandler implements HttpAuthenticationMechanismHandler {

    @Inject
    @QualifierA
            @Any
    HttpAuthenticationMechanism qualifierAAuthenticationMechanism;

    @Inject
    @QualifierB
    HttpAuthenticationMechanism qualifierBAuthenticationMechanism;

    @Override
    public AuthenticationStatus validateRequest(HttpServletRequest request, HttpServletResponse response, HttpMessageContext httpMessageContext) throws AuthenticationException {
        String requestURI = request.getRequestURI();

        if (requestURI.endsWith("/qualifierA")) {
            return qualifierAAuthenticationMechanism.validateRequest(request, response, httpMessageContext);
        } else if (requestURI.endsWith("/qualifierB")) {
            return qualifierBAuthenticationMechanism.validateRequest(request, response, httpMessageContext);
        }

        return AuthenticationStatus.NOT_DONE;
    }
}
