/*
 * Copyright (c) 2024 Contributors to the Eclipse Foundation.
 * Copyright (c) 2007, 2018 Oracle and/or its affiliates. All rights reserved.
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.glassfish.grizzly.config.ssl;

import java.net.Socket;

import javax.net.ssl.SSLEngine;

import org.glassfish.grizzly.ssl.SSLSupport;
import org.jvnet.hk2.annotations.Contract;

/**
 * SSLImplementation:
 *
 * Abstract factory and base class for all SSL implementations.
 *
 * @author EKR
 */
@Contract
public interface SSLImplementation {

    String getImplementationName();

    ServerSocketFactory getServerSocketFactory();

    SSLSupport getSSLSupport(Socket sock);

    SSLSupport getSSLSupport(SSLEngine sslEngine);
}
