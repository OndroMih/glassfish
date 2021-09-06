/*
 * Copyright (c) 1997, 2018 Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
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

package org.glassfish.jdbc.config;

import org.glassfish.jdbc.admin.cli.test.JdbcAdminJunit5Extension;
import org.glassfish.tests.utils.DomainXml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the JdbcConnectionPool config bean's defaults.
 * @author Kedar Mhaswade (km@dev.java.net)
 */
@ExtendWith(JdbcAdminJunit5Extension.class)
@DomainXml("JdbcConnectionPoolDefaults.xml")
public class JdbcConnectionPoolDefaultsTest {

    @Inject
    private JdbcConnectionPool onlyOnePool;

    @Test
    public void testFewDefaults() {
        assertEquals("8", onlyOnePool.getSteadyPoolSize());
        assertEquals("32", onlyOnePool.getMaxPoolSize());
        assertEquals("false", onlyOnePool.getMatchConnections());
    }
}
