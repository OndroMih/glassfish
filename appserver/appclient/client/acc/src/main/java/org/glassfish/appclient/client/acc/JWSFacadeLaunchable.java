/*
 * Copyright (c) 1997, 2018 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.appclient.client.acc;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.jar.Attributes;

import org.glassfish.api.deployment.archive.ReadableArchive;
import org.glassfish.hk2.api.ServiceLocator;

/**
 *
 * @author tjquinn
 */
public class JWSFacadeLaunchable extends FacadeLaunchable {

    public JWSFacadeLaunchable(ServiceLocator habitat, ReadableArchive facadeClientRA, Attributes mainAttrs, ReadableArchive clientRA, String mainClassNameToLaunch) throws IOException {
        super(habitat, facadeClientRA, mainAttrs, clientRA, mainClassNameToLaunch, null);
    }

    public JWSFacadeLaunchable(ServiceLocator habitat, Attributes mainAttrs, ReadableArchive facadeRA) throws IOException, URISyntaxException {
        super(habitat, mainAttrs, facadeRA, null);
    }

    @Override
    public String getAnchorDir() {
        throw new UnsupportedOperationException("getAnchorDir not yet supported during Java Web Start launches");
    }




}
