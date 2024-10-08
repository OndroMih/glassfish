/*
 * Copyright (c) 2010, 2018 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.enterprise.v3.admin.cluster;

import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.Node;
import com.sun.enterprise.config.serverbeans.Nodes;
import com.sun.enterprise.config.serverbeans.Server;
import com.sun.enterprise.config.serverbeans.Servers;
import com.sun.enterprise.universal.glassfish.ASenvPropertyReader;
import com.sun.enterprise.util.ExceptionUtil;
import com.sun.enterprise.util.StringUtils;
import com.sun.enterprise.util.SystemPropertyConstants;
import com.sun.enterprise.util.io.InstanceDirs;

import jakarta.inject.Inject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.api.ActionReport;
import org.glassfish.api.I18n;
import org.glassfish.api.Param;
import org.glassfish.api.admin.AdminCommand;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.api.admin.CommandRunner;
import org.glassfish.api.admin.CommandRunner.CommandInvocation;
import org.glassfish.api.admin.ExecuteOn;
import org.glassfish.api.admin.ParameterMap;
import org.glassfish.api.admin.RestEndpoint;
import org.glassfish.api.admin.RestEndpoints;
import org.glassfish.api.admin.RuntimeType;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.hk2.api.IterableProvider;
import org.glassfish.hk2.api.PerLookup;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.internal.api.ServerContext;
import org.jvnet.hk2.annotations.Service;

/**
 * Remote AdminCommand to create an instance.  This command is run only on DAS.
 *  1. Register the instance on DAS
 *  2. Create the file system on the instance node via ssh, node agent, or other
 *  3. Bootstrap a minimal set of config files on the instance for secure admin.
 *
 * @author Jennifer Chou
 */
@Service(name = "create-instance")
@I18n("create.instance")
@PerLookup
@ExecuteOn({RuntimeType.DAS})
@RestEndpoints({
    @RestEndpoint(configBean=Domain.class,
        opType=RestEndpoint.OpType.POST,
        path="create-instance",
        description="Create Instance")
})
public class CreateInstanceCommand implements AdminCommand {
    private static final String NL = System.getProperty("line.separator");
    @Inject
    private CommandRunner cr;
    @Inject
    ServiceLocator habitat;
    @Inject
    IterableProvider<Node> nodeList;
    @Inject
    private Nodes nodes;
    @Inject
    private Servers servers;
    @Inject
    private ServerEnvironment env;
    @Inject
    private ServerContext serverContext;
    @Param(name = "node", alias = "nodeagent")
    String node;
    @Param(name = "config", optional = true)
    @I18n("generic.config")
    String configRef;
    @Param(name = "cluster", optional = true)
    String clusterName;
    @Param(name = "lbenabled", optional = true)
    private Boolean lbEnabled;
    @Param(name = "checkports", optional = true, defaultValue = "true")
    private boolean checkPorts;
    @Param(optional = true, defaultValue = "false")
    private boolean terse;
    @Param(name = "portbase", optional = true)
    private String portBase;
    @Param(name = "systemproperties", optional = true, separator = ':')
    private String systemProperties;
    @Param(name = "instance_name", primary = true)
    private String instance;
    private Logger logger = null; // set in execute and all references occur after that assignment
    private AdminCommandContext ctx;
    private Node theNode = null;
    private String nodeHost = null;
    private String nodeDir = null;
    private String installDir = null;
    private String registerInstanceMessage = null;

    @Override
    public void execute(AdminCommandContext context) {
        ActionReport report = context.getActionReport();
        ctx = context;
        logger = context.getLogger();

        if (!env.isDas()) {
            String msg = Strings.get("notAllowed");
            logger.warning(msg);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(msg);
            return;
        }

        // Make sure Node is valid
        theNode = nodes.getNode(node);
        if (theNode == null) {
            String msg = Strings.get("noSuchNode", node);
            logger.warning(msg);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(msg);
            return;
        }

        if (lbEnabled != null && clusterName == null) {
            String msg = Strings.get("lbenabledNotForStandaloneInstance");
            logger.warning(msg);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(msg);
            return;
        }

        nodeHost = theNode.getNodeHost();
        nodeDir = theNode.getNodeDirAbsolute();
        installDir = theNode.getInstallDir();

        if (theNode.isLocal()){
            validateInstanceDirUnique(report, context);
            if (report.getActionExitCode() != ActionReport.ExitCode.SUCCESS
                    && report.getActionExitCode() != ActionReport.ExitCode.WARNING) {
                // If we couldn't update domain.xml then stop!
                return;
            }
        }

        // First, update domain.xml by calling _register-instance
        CommandInvocation ci = cr.getCommandInvocation("_register-instance", report, context.getSubject());
        ParameterMap map = new ParameterMap();
        map.add("node", node);
        map.add("config", configRef);
        map.add("cluster", clusterName);
        if (lbEnabled != null) {
            map.add("lbenabled", lbEnabled.toString());
        }
        if (!checkPorts) {
            map.add("checkports", "false");
        }
        if (StringUtils.ok(portBase)) {
            map.add("portbase", portBase);
        }
        map.add("systemproperties", systemProperties);
        map.add("DEFAULT", instance);
        ci.parameters(map);
        ci.execute();


        if (report.getActionExitCode() != ActionReport.ExitCode.SUCCESS
                && report.getActionExitCode() != ActionReport.ExitCode.WARNING) {
            // If we couldn't update domain.xml then stop!
            return;
        }

        registerInstanceMessage = report.getMessage();

        // if nodehost is localhost and installdir is null and config node, update config node
        // so installdir is product root. see register-instance above
        if (theNode.isLocal() && installDir == null) {
            ci = cr.getCommandInvocation("_update-node", report, context.getSubject());
            map = new ParameterMap();
            map.add("installdir", "${com.sun.aas.productRoot}");
            map.add("type", "CONFIG");
            map.add("DEFAULT", theNode.getName());
            ci.parameters(map);
            ci.execute();


            if (report.getActionExitCode() != ActionReport.ExitCode.SUCCESS
                    && report.getActionExitCode() != ActionReport.ExitCode.WARNING) {
                // If we couldn't update domain.xml then stop!
                return;
            }
        }

        if (!validateDasOptions(context)) {
            report.setActionExitCode(ActionReport.ExitCode.WARNING);
            return;
        }

        // Then go create the instance filesystem on the node
        createInstanceFilesystem(context);
    }

    private void validateInstanceDirUnique(ActionReport report, AdminCommandContext context) {
        CommandInvocation listInstances = cr.getCommandInvocation("list-instances", report, context.getSubject());
        ParameterMap map = new ParameterMap();
        map.add("whichTarget", theNode.getName());
        listInstances.parameters(map);
        listInstances.execute();
        Properties pro = listInstances.report().getExtraProperties();
        if (pro != null){
            List<HashMap> instanceList = (List<HashMap>) pro.get("instanceList");
            if (instanceList == null)
                return;
            for (HashMap instanceMap : instanceList) {
                final File nodeDirFile = (nodeDir != null
                        ? new File(nodeDir)
                        : defaultLocalNodeDirFile());
                File instanceDir = new File(new File(nodeDirFile.toString(), theNode.getName()), instance);
                String instanceName = (String)instanceMap.get("name");
                File instanceListDir = new File(new File(nodeDirFile.toString(), theNode.getName()), instance);
                if (instance.equalsIgnoreCase(instanceName) && instanceDir.equals(instanceListDir)) {
                    String msg = Strings.get("Instance.duplicateInstanceDir", instance, instanceName);
                    logger.warning(msg);
                    report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                    report.setMessage(msg);
                    return;
                }
            }
        }
    }

    /**
     * Returns the directory for the selected instance that is on the local
     * system.
     * @param instanceName name of the instance
     * @return File for the local file system location of the instance directory
     * @throws IOException
     */
    private File getLocalInstanceDir() throws IOException {
        /*
         * Pass the node directory parent and the node directory name explicitly
         * or else InstanceDirs will not work as we want if there are multiple
         * nodes registered on this node.
         *
         * If the configuration recorded an explicit directory for the node,
         * then use it.  Otherwise, use the default node directory of
         * ${installDir}/glassfish/nodes/${nodeName}.
         */
        final File nodeDirFile = (nodeDir != null
                ? new File(nodeDir)
                : defaultLocalNodeDirFile());
        InstanceDirs instanceDirs = new InstanceDirs(nodeDirFile.toString(), theNode.getName(), instance);
        return instanceDirs.getInstanceDir();
    }

    private File defaultLocalNodeDirFile() {
        final Map<String,String> systemProps =
            Collections.unmodifiableMap(new ASenvPropertyReader().getProps());
        /*
         * The default "nodes" directory we want to use
         * has been set in asenv.conf named as
         * AS_DEF_NODES_PATH
         */
        String nodeDirDefault = systemProps.get(
                SystemPropertyConstants.AGENT_ROOT_PROPERTY);
        return new File(nodeDirDefault);

    }

    private File getDomainInstanceDir() {
        return env.getInstanceRoot();
    }

    /**
     *
     * Delivers bootstrap files for secure admin locally, because the instance
     * is on the same system as the DAS (and therefore on the same system where
     * this command is running).
     *
     * @return 0 if successful, 1 otherwise
     */
    private int bootstrapSecureAdminLocally() {
        final ActionReport report = ctx.getActionReport();

        try {
            final SecureAdminBootstrapHelper bootHelper =
                    SecureAdminBootstrapHelper.getLocalHelper(
                    env.getInstanceRoot(),
                    getLocalInstanceDir());
            bootHelper.bootstrapInstance();
            bootHelper.close();
            return 0;
        }
        catch (IOException ex) {
            return reportFailure(ex, report);
        }
        catch (SecureAdminBootstrapHelper.BootstrapException ex) {
            return reportFailure(ex, report);
        }
    }

    private int reportFailure(final Exception ex, final ActionReport report) {
        String msg = Strings.get("create.instance.local.boot.failed", instance, node, nodeHost);
        logger.log(Level.SEVERE, msg, ex);
        report.setActionExitCode(ActionReport.ExitCode.FAILURE);
        report.setMessage(msg);
        return 1;
    }

    /**
     * Delivers bootstrap files for secure admin remotely, because the instance
     * is NOT on the same system as the DAS.
     *
     * @return 0 if successful; 1 otherwise
     */
    private int bootstrapSecureAdminRemotely() {
        ActionReport report = ctx.getActionReport();
        // nodedir is the root of where all the node dirs will be created.
        // add the name of the node as that is where the instance files should be created
        String thisNodeDir = null;
        if (nodeDir != null)
            thisNodeDir = nodeDir + "/" + node;
        try {
            final SecureAdminBootstrapHelper bootHelper =
                    SecureAdminBootstrapHelper.getRemoteHelper(
                    habitat,
                    getDomainInstanceDir(),
                    thisNodeDir,
                    instance,
                    theNode, logger);
            bootHelper.bootstrapInstance();
            bootHelper.close();
            return 0;
        }
        catch (Exception ex) {
            String exmsg = ex.getMessage();
            if (exmsg == null) {
                // The root cause message is better than no message at all
                exmsg = ExceptionUtil.getRootCause(ex).toString();
            }
            String msg = Strings.get(
                    "create.instance.remote.boot.failed",
                    instance,



                    // DCOMFIX
                    (ex instanceof SecureAdminBootstrapHelper.BootstrapException
                    ? ((SecureAdminBootstrapHelper.BootstrapException) ex).sshSettings() : null),
                    exmsg,




                    nodeHost);
            logger.log(Level.SEVERE, msg, ex);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(msg);
            return 1;
        }
    }

    private void createInstanceFilesystem(AdminCommandContext context) {
        ActionReport report = ctx.getActionReport();
        report.setActionExitCode(ActionReport.ExitCode.SUCCESS);

        NodeUtils nodeUtils = new NodeUtils(habitat, logger);
        Server dasServer =
                servers.getServer(SystemPropertyConstants.DAS_SERVER_NAME);
        String dasHost = dasServer.getAdminHost();
        String dasPort = Integer.toString(dasServer.getAdminPort());

        ArrayList<String> command = new ArrayList<String>();
        String humanCommand = null;

        if (!theNode.isLocal()) {
            // Only specify the DAS host if the node is remote. See issue 13993
            command.add("--host");
            command.add(dasHost);
        }

        command.add("--port");
        command.add(dasPort);

        command.add("_create-instance-filesystem");

        if (nodeDir != null) {
            command.add("--nodedir");
            command.add(StringUtils.quotePathIfNecessary(nodeDir));
        }

        command.add("--node");
        command.add(node);

        command.add(instance);

        humanCommand = makeCommandHuman(command);
        if (userManagedNodeType()) {
            String msg = Strings.get("create.instance.config",
                    instance, humanCommand);
            msg = StringUtils.cat(NL, registerInstanceMessage, msg);
            report.setMessage(msg);
            return;
        }

        // First error message displayed if we fail
        String firstErrorMessage = Strings.get("create.instance.filesystem.failed",
                instance, node, nodeHost);

        StringBuilder output = new StringBuilder();

        // Run the command on the node and handle errors.
        nodeUtils.runAdminCommandOnNode(theNode, command, ctx, firstErrorMessage,
                humanCommand, output);

        if (report.getActionExitCode() != ActionReport.ExitCode.SUCCESS) {
            // something went wrong with the nonlocal command don't continue but set status to warning
            // because config was updated correctly or we would not be here.
            report.setActionExitCode(ActionReport.ExitCode.WARNING);
            return;
        }

        // If it was successful say so and display the command output
        String msg = Strings.get("create.instance.success",
                instance, nodeHost);
        if (!terse) {
            msg = StringUtils.cat(NL,
                    output.toString().trim(), registerInstanceMessage, msg);
        }
        report.setMessage(msg);

        // Bootstrap secure admin files
        if (theNode.isLocal()) {
            bootstrapSecureAdminLocally();
        }
        else {
            bootstrapSecureAdminRemotely();
        }
        if (report.getActionExitCode() != ActionReport.ExitCode.SUCCESS) {

            // something went wrong with the nonlocal command don't continue but set status to warning
            // because config was updated correctly or we would not be here.
            report.setActionExitCode(ActionReport.ExitCode.WARNING);
        }
    }

    /**
     * This ensures we don't step on another domain's node files on a remote
     * instance. See bug GLASSFISH-14985.
     */
    private boolean validateDasOptions(AdminCommandContext context) {
        boolean isDasOptionsValid = true;
        if (theNode.isLocal() || (!theNode.isLocal() && theNode.getType().equals("SSH"))) {
            ActionReport report = ctx.getActionReport();
            report.setActionExitCode(ActionReport.ExitCode.SUCCESS);

            NodeUtils nodeUtils = new NodeUtils(habitat, logger);
            Server dasServer =
                    servers.getServer(SystemPropertyConstants.DAS_SERVER_NAME);
            String dasHost = dasServer.getAdminHost();
            String dasPort = Integer.toString(dasServer.getAdminPort());

            ArrayList<String> command = new ArrayList<String>();

            if (!theNode.isLocal()) {
                // Only specify the DAS host if the node is remote. See issue 13993
                command.add("--host");
                command.add(dasHost);
            }

            command.add("--port");
            command.add(dasPort);

            command.add("_validate-das-options");

            if (nodeDir != null) {
                command.add("--nodedir");
                command.add(nodeDir); //XXX escape spaces?
            }

            command.add("--node");
            command.add(node);

            command.add(instance);

            // Run the command on the node
            nodeUtils.runAdminCommandOnNode(theNode, command, ctx, "", null, null);

            if (report.getActionExitCode() != ActionReport.ExitCode.SUCCESS) {
                report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                isDasOptionsValid = false;
            }
        }
        return isDasOptionsValid;
    }

    private String makeCommandHuman(List<String> command) {
        StringBuilder fullCommand = new StringBuilder();

        fullCommand.append("lib");
        fullCommand.append(System.getProperty("file.separator"));
        fullCommand.append("nadmin ");

        for (String s : command) {
            if (s.equals("_create-instance-filesystem")) {
                // We tell the user to run create-local-instance, not the
                // hidden command
                fullCommand.append(" ");
                fullCommand.append("create-local-instance");
            }
            else {
                fullCommand.append(" ");
                fullCommand.append(s);
            }
        }

        return fullCommand.toString();
    }

    // verbose but very readable...
    private boolean userManagedNodeType() {
        if(theNode.isLocal())
            return false;

        if(theNode.getType().equals("SSH"))
            return false;

        return true;
    }
}
