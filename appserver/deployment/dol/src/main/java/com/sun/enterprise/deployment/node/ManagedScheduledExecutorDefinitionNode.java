/*
 * Copyright (c) 2022, 2024 Eclipse Foundation and/or its affiliates. All rights reserved.
 * Copyright (c) 2024 Payara Foundation and/or its affiliates
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
package com.sun.enterprise.deployment.node;

import com.sun.enterprise.deployment.ManagedScheduledExecutorDefinitionDescriptor;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Map;

import org.w3c.dom.Node;

import static com.sun.enterprise.deployment.xml.ConcurrencyTagNames.MANAGED_SCHEDULED_EXECUTOR_CONTEXT_SERVICE_REF;
import static com.sun.enterprise.deployment.xml.ConcurrencyTagNames.MANAGED_SCHEDULED_EXECUTOR_HUNG_TASK_THRESHOLD;
import static com.sun.enterprise.deployment.xml.ConcurrencyTagNames.MANAGED_SCHEDULED_EXECUTOR_MAX_ASYNC;
import static com.sun.enterprise.deployment.xml.ConcurrencyTagNames.QUALIFIER;
import static com.sun.enterprise.deployment.xml.ConcurrencyTagNames.VIRTUAL;
import static com.sun.enterprise.deployment.xml.TagNames.NAME;
import static com.sun.enterprise.deployment.xml.TagNames.RESOURCE_PROPERTY;

public class ManagedScheduledExecutorDefinitionNode
    extends DeploymentDescriptorNode<ManagedScheduledExecutorDefinitionDescriptor> {

    private static final Logger LOG = System.getLogger(ManagedScheduledExecutorDefinitionNode.class.getName());


    public ManagedScheduledExecutorDefinitionNode() {
        registerElementHandler(new XMLElement(RESOURCE_PROPERTY), ResourcePropertyNode.class,
            "addManagedScheduledExecutorDefinitionDescriptor");
    }


    @Override
    public ManagedScheduledExecutorDefinitionDescriptor createDescriptor() {
        return new ManagedScheduledExecutorDefinitionDescriptor();
    }


    @Override
    protected Map<String, String> getDispatchTable() {
        Map<String,String> map = super.getDispatchTable();
        map.put(NAME, "setName");
        map.put(VIRTUAL, "setVirtual");
        map.put(MANAGED_SCHEDULED_EXECUTOR_MAX_ASYNC, "setMaxAsync");
        map.put(MANAGED_SCHEDULED_EXECUTOR_CONTEXT_SERVICE_REF, "setContext");
        map.put(MANAGED_SCHEDULED_EXECUTOR_HUNG_TASK_THRESHOLD, "setHungTaskThreshold");
        return map;
    }


    @Override
    public void setElementValue(XMLElement element, String value) {
        String qname = element.getQName();
        ManagedScheduledExecutorDefinitionDescriptor descriptor = getDescriptor();
        if (QUALIFIER.equals(qname)) {
            try {
                descriptor.addQualifier(Class.forName(value, false, Thread.currentThread().getContextClassLoader()));
            } catch (ClassNotFoundException e) {
                LOG.log(Level.WARNING, "Ignoring unresolvable qualifier " + value, e);
            }
        } else {
            super.setElementValue(element, value);
        }
    }


    @Override
    public Node writeDescriptor(Node parent, String nodeName, ManagedScheduledExecutorDefinitionDescriptor descriptor) {
        Node node = appendChild(parent, nodeName);
        appendTextChild(node, NAME, descriptor.getName());
        for (Class<?> qualifier : descriptor.getQualifiers()) {
            appendTextChild(node, QUALIFIER, qualifier.getCanonicalName());
        }
        appendTextChild(node, VIRTUAL, descriptor.isVirtual());
        appendTextChild(node, MANAGED_SCHEDULED_EXECUTOR_MAX_ASYNC, descriptor.getMaxAsync());
        appendTextChild(node, MANAGED_SCHEDULED_EXECUTOR_CONTEXT_SERVICE_REF, descriptor.getContext());
        appendTextChild(node, MANAGED_SCHEDULED_EXECUTOR_HUNG_TASK_THRESHOLD, String.valueOf(descriptor.getHungTaskThreshold()));
        return ResourcePropertyNode.write(node, descriptor);
    }
}
