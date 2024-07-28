/*
 * Copyright (c) 2024 Contributors to Eclipse Foundation.
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
package org.glassfish.microprofile.config;

import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.ConfigProvider;
import org.glassfish.config.api.ConfigVariableResolver;
import org.glassfish.hk2.api.ServiceLocator;
import org.jvnet.hk2.annotations.Service;

@Service
public class MicroProfileConfigVariableResolver implements ConfigVariableResolver {

    @Inject
    ConfigDeployer configDeployer;

    @Inject
    ServiceLocator locator;

    private final boolean MP_VARIABLE_RESOLVER_DISABLED = Boolean.parseBoolean(System.getProperty("org.glassfish.microprofile.variableResolver.disable", "false"));

    @Override
    public Optional<String> resolve(String variableName) {
        if (MP_VARIABLE_RESOLVER_DISABLED) {
            return Optional.empty();
        }
        configDeployer.initializeConfigProviders();
        return ConfigProvider.getConfig()
                .getOptionalValue(variableName, String.class);
    }

}
