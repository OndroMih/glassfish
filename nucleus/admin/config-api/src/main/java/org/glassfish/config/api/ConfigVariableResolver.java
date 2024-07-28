/*
 * Copyright (c) 2024 Contributors to the Eclipse Foundation
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
package org.glassfish.config.api;

import java.util.Optional;
import org.jvnet.hk2.annotations.Contract;

/**
 *
 * Interface for pluggable variable resolvers.
 * <p>
 * Resolvers are provided as an HK2 service that implements this interface.
 * The the first resolver that resolves the variable with a non-empty {@link Optional} will be used to resolve the variable.
 * If no resolvers avaiable, a fallback mechanism will be used (e.g. system property).
 */
@Contract
public interface ConfigVariableResolver {
    Optional<String> resolve(String variableName);
}
