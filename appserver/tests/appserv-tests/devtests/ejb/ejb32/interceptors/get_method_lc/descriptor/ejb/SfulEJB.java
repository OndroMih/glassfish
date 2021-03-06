/*
 * Copyright (c) 2017, 2018 Oracle and/or its affiliates. All rights reserved.
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

package com.acme;

import javax.interceptor.InvocationContext;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptors;


import javax.ejb.*;
import javax.annotation.*;


@Stateful
public class SfulEJB extends BaseBean {

    @EJB private SlessEJB sless;

    public SfulEJB() {}

    public String hello() {
        System.out.println("In SfulEJB:hello()");
        verifyB_AC("SfulEJB");
        verifyB_PC("SfulEJB");
	return sless.sayHello();
    }

    @Remove
    public void remove() {
        System.out.println("In SfulEJB:remove()");
    }

    @PostConstruct
    private void init0() {
	System.out.println("**SfulEJB PostConstruct");
        verifyMethod(null);
    }

}
