/*
 * Copyright (c) 2011, 2018 Oracle and/or its affiliates. All rights reserved.
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

package test;

import java.io.IOException;
import java.io.PrintWriter;
            
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
                
@WebServlet(urlPatterns={"/dispatchforward0"}, asyncSupported=true)
public class DispatchForward0 extends HttpServlet {
    public void doGet(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
                    
        String forwardUrl = "/dispatchforward";

        if (!req.getDispatcherType().equals(DispatcherType.ASYNC)) {
            System.out.println("DF0: forwarding " + forwardUrl);
            req.getRequestDispatcher(forwardUrl).forward(req, res);
        } else {        
            System.out.println("DF0: async dispatch type ...");
            PrintWriter writer = res.getWriter();
            writer.write("Hello from DispatchForward0\n");
        } 
    }       
}
