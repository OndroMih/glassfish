/*
 * Copyright (c) 2007, 2018 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.enterprise.v3.server;

import com.sun.enterprise.module.HK2Module;
import com.sun.enterprise.module.ManifestConstants;
import com.sun.enterprise.module.ModuleDefinition;
import com.sun.enterprise.module.ModulesRegistry;
import com.sun.enterprise.module.Repository;
import com.sun.enterprise.module.ResolveError;
import com.sun.enterprise.module.common_impl.DirectoryBasedRepository;
import com.sun.enterprise.module.common_impl.Tokenizer;

import jakarta.inject.Inject;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.api.deployment.DeploymentContext;
import org.glassfish.api.deployment.archive.ReadableArchive;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.utilities.BuilderHelper;
import org.glassfish.internal.api.ClassLoaderHierarchy;
import org.glassfish.internal.api.ConnectorClassLoaderService;
import org.glassfish.internal.api.DelegatingClassLoader;
import org.jvnet.hk2.annotations.Optional;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.config.TranslationException;
import org.jvnet.hk2.config.VariableResolver;


/**
 * @author Sanjeeb.Sahoo@Sun.COM
 */
@Service
public class ClassLoaderHierarchyImpl implements ClassLoaderHierarchy {
    @Inject APIClassLoaderServiceImpl apiCLS;

    @Inject CommonClassLoaderServiceImpl commonCLS;

    //For distributions where connector module is not available.
    @Inject @Optional ConnectorClassLoaderService connectorCLS;

    @Inject
    AppLibClassLoaderServiceImpl applibCLS;

    @Inject
    ModulesRegistry modulesRegistry;

    @Inject
    Logger logger;

    @Inject
    ServiceLocator habitat;

    SystemVariableResolver resolver = new SystemVariableResolver();

    @Override
    public ClassLoader getAPIClassLoader() {
        return apiCLS.getAPIClassLoader();
    }

    @Override
    public ClassLoader getCommonClassLoader() {
        return commonCLS.getCommonClassLoader();
    }

    @Override
    public String getCommonClassPath() {
        return commonCLS.getCommonClassPath();
    }

    @Override
    public DelegatingClassLoader getConnectorClassLoader(String application) {
        // For distributions where connector module (connector CL) is not available, use empty classloader with parent
        if(connectorCLS != null){
            return connectorCLS.getConnectorClassLoader(application);
        }else{
            return AccessController.doPrivileged(new PrivilegedAction<DelegatingClassLoader>() {
                @Override
                public DelegatingClassLoader run() {
                    return new DelegatingClassLoader(commonCLS.getCommonClassLoader());
                }
            });
        }
    }

    @Override
    public ClassLoader getAppLibClassLoader(String application, List<URI> libURIs) throws MalformedURLException {
        return applibCLS.getAppLibClassLoader(application, libURIs);
    }

    @Override
    public DelegatingClassLoader.ClassFinder getAppLibClassFinder(List<URI> libURIs) throws MalformedURLException {
        return applibCLS.getAppLibClassFinder(libURIs);
    }

    /**
     * Sets up the parent class loader for the application class loader.
     * Application class loader are under the control of the ArchiveHandler since
     * a special archive file format will require a specific class loader.
     *
     * However GlassFish needs to be able to add capabilities to the application
     * like adding APIs accessibility, this is done through its parent class loader
     * which we create and maintain.
     *
     * @param parent the parent class loader
     * @param context deployment context
     * @return class loader capable of loading public APIs identified by the deployers
     * @throws ResolveError if one of the deployer's public API module is not found.
     */
    @Override
    public ClassLoader createApplicationParentCL(ClassLoader parent, DeploymentContext context)
        throws ResolveError {

        final ReadableArchive source = context.getSource();
        List<ModuleDefinition> defs = new ArrayList<>();

        // now let's see if the application is requesting any module imports
        Manifest m=null;
        try {
            m = source.getManifest();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Cannot load application's manifest file :", e.getMessage());
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, e.getMessage(), e);
            }
        }
        if (m!=null) {
            String importedBundles = m.getMainAttributes().getValue(ManifestConstants.BUNDLE_IMPORT_NAME);
            if (importedBundles!=null) {
                for( String token : new Tokenizer(importedBundles,",")) {
                    Collection<HK2Module> modules = modulesRegistry.getModules(token);
                    if (modules.size() ==1) {
                        defs.add(modules.iterator().next().getModuleDefinition());
                    } else {
                        throw new ResolveError("Not able to locate a unique module by name " + token);
                    }
                }
            }

            // Applications can add an additional osgi repos...
            String additionalRepo = m.getMainAttributes().getValue(org.glassfish.api.ManifestConstants.GLASSFISH_REQUIRE_REPOSITORY);
            if (additionalRepo != null) {
                for (String token : new Tokenizer(additionalRepo, ",")) {
                    // Each entry should be name=path
                    int equals = token.indexOf('=');
                    if (equals == -1) {
                        // Missing '='...
                        throw new IllegalArgumentException("\""
                            + org.glassfish.api.ManifestConstants.GLASSFISH_REQUIRE_REPOSITORY
                            + ": " + additionalRepo + "\" is missing an '='.  "
                            + "It must be in the format: name=path[,name=path]...");
                    }
                    String name = token.substring(0, equals);
                    String path = token.substring(++equals);
                    addRepository(name, resolver.translate(path));
                }
            }

            // Applications can also request to be wired to implementors of certain services.
            // That means that any module implementing the requested service will be accessible
            // by the parent class loader of the application.
            String requestedWiring = m.getMainAttributes().getValue(org.glassfish.api.ManifestConstants.GLASSFISH_REQUIRE_SERVICES);
            if (requestedWiring!=null) {
                for (String token : new Tokenizer(requestedWiring, ",")) {
                    for (Object impl : habitat.getAllServices(BuilderHelper.createContractFilter(token))) {
                        HK2Module wiredBundle = modulesRegistry.find(impl.getClass());
                        if (wiredBundle!=null) {
                            defs.add(wiredBundle.getModuleDefinition());
                        }
                    }
                }
            }
        }

        if (defs.isEmpty()) {
            return parent;
        }  else {
            return modulesRegistry.getModulesClassLoader(parent, defs);
        }
    }

    /**
     *        <p> This method installs the admin console OSGi bundle respository so
     *            our plugins can be found.</p>
     */
    private void addRepository(String name, String path) {
        File pathFile = new File(path);
        Repository repo = new DirectoryBasedRepository(
                name, pathFile);
        modulesRegistry.addRepository(repo);
        try {
            repo.initialize();
        } catch (IOException ex) {
            logger.log(Level.SEVERE,
                "Problem initializing additional repository!", ex);
        }
    }

    /**
     *        <p> This class helps resolve ${} variables in Strings.</p>
     */
    private static class SystemVariableResolver extends VariableResolver {
        SystemVariableResolver() {
            super();
        }

        @Override
        protected String getVariableValue(final String varName) throws TranslationException {
            String result = null;

            // first look for a system property
            final Object value = System.getProperty(varName);
            if (value != null) {
                result = "" + value;
            } else {
                result = "${" + varName + "}";
            }
            return result;
        }

        /**
            Return true if the string is a template string of the for ${...}
         */
        public static boolean needsResolving(final String value) {
            return (value != null) && (value.indexOf("${") != -1);
        }

        /**
         *  Resolve the given String.
         */
        public String resolve(final String value) throws TranslationException {
            final String result = translate(value);
            return result;
        }
    }
}
