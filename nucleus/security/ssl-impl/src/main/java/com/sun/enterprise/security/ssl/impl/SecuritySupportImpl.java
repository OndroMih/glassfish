/*
 * Copyright (c) 2024 Contributors to the Eclipse Foundation.
 * Copyright (c) 1997, 2021 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.enterprise.security.ssl.impl;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINEST;

import com.sun.enterprise.security.ssl.manager.UnifiedX509KeyManager;
import com.sun.enterprise.security.ssl.manager.UnifiedX509TrustManager;
import com.sun.enterprise.server.pluggable.SecuritySupport;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import org.glassfish.api.admin.ProcessEnvironment;
import org.glassfish.api.admin.ProcessEnvironment.ProcessType;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.internal.api.Globals;
import org.glassfish.logging.annotation.LogMessageInfo;
import org.glassfish.logging.annotation.LogMessagesResourceBundle;
import org.glassfish.logging.annotation.LoggerInfo;
import org.jvnet.hk2.annotations.Service;

/**
 * This implements SecuritySupport used in PluggableFeatureFactory.
 *
 * @author Shing Wai Chan
 */
// TODO: when we have two SecuritySupport implementations,
// we create Habitat we'll select which SecuritySupport implementation to use.
@Service
@Singleton
public class SecuritySupportImpl extends SecuritySupport {
    private static final String DEFAULT_KEYSTORE_PASS = "changeit";
    private static final String DEFAULT_TRUSTSTORE_PASS = "changeit";

    @LogMessagesResourceBundle
    public static final String SHARED_LOGMESSAGE_RESOURCE = "com.sun.enterprise.security.ssl.LogMessages";

    @LoggerInfo(subsystem = "SECURITY - SSL", description = "Security - SSL", publish = true)
    public static final String SEC_SSL_LOGGER = "jakarta.enterprise.system.security.ssl";

    protected static final Logger _logger = Logger.getLogger(SEC_SSL_LOGGER, SHARED_LOGMESSAGE_RESOURCE);

    @LogMessageInfo(message = "The SSL certificate with alias {0} has expired: {1}", level = "SEVERE", cause = "Certificate expired.", action = "Check the expiration date of the certicate.")
    private static final String SSL_CERT_EXPIRED = "NCLS-SECURITY-05054";

    private static boolean initialized;
    protected static final List<KeyStore> keyStores = new ArrayList<>();
    protected static final List<KeyStore> trustStores = new ArrayList<>();
    protected static final List<char[]> keyStorePasswords = new ArrayList<>();
    protected static final List<String> tokenNames = new ArrayList<>();
    private MasterPasswordImpl masterPasswordHelper;
    private static boolean instantiated;
    private final Date initDate = new Date();

    @Inject
    private ServiceLocator serviceLocator;

    @Inject
    private ProcessEnvironment processEnvironment;

    public SecuritySupportImpl() {
        this(true);
    }

    protected SecuritySupportImpl(boolean init) {
        if (init) {
            initJKS();
        }
    }

    private void initJKS() {
        String keyStoreFileName = null;
        String trustStoreFileName = null;

        keyStoreFileName = System.getProperty(keyStoreProp);
        trustStoreFileName = System.getProperty(trustStoreProp);

        char[] keyStorePass = null;
        char[] trustStorePass = null;
        if (!isInstantiated()) {
            if (serviceLocator == null) {
                serviceLocator = Globals.getDefaultBaseServiceLocator();
            }

            if (masterPasswordHelper == null && serviceLocator != null) {
                masterPasswordHelper = serviceLocator.getService(MasterPasswordImpl.class);
            }

            if (masterPasswordHelper != null) {
                keyStorePass = masterPasswordHelper.getMasterPassword();
                trustStorePass = keyStorePass;
            }
        }

        if (processEnvironment == null && serviceLocator != null) {
            processEnvironment = serviceLocator.getService(ProcessEnvironment.class);
        }

        /*
         * If we don't have a keystore password yet check the properties.
         * Always do so for the app client case whether the passwords have been
         * found from master password helper or not.
         */
        if (keyStorePass == null || isACC()) {
            final String keyStorePassOverride = System.getProperty(KEYSTORE_PASS_PROP, DEFAULT_KEYSTORE_PASS);
            if (keyStorePassOverride != null) {
                keyStorePass = keyStorePassOverride.toCharArray();
            }

            final String trustStorePassOverride = System.getProperty(TRUSTSTORE_PASS_PROP, DEFAULT_TRUSTSTORE_PASS);
            if (trustStorePassOverride != null) {
                trustStorePass = trustStorePassOverride.toCharArray();
            }
        }

        if (!initialized) {
            loadStores(
                null, null,
                keyStoreFileName, keyStorePass,
                System.getProperty(KEYSTORE_TYPE_PROP, KeyStore.getDefaultType()),
                trustStoreFileName, trustStorePass,
                System.getProperty(TRUSTSTORE_TYPE_PROP, KeyStore.getDefaultType()));
            Arrays.fill(keyStorePass, ' ');
            Arrays.fill(trustStorePass, ' ');
            initialized = true;
        }
    }

    private static synchronized boolean isInstantiated() {
        if (!instantiated) {
            instantiated = true;
            return false;
        }

        return true;
    }

    /**
     * This method will load keystore and truststore and add into corresponding list.
     *
     * @param tokenName
     * @param provider
     * @param keyStorePass
     * @param keyStoreFile
     * @param keyStoreType
     * @param trustStorePass
     * @param trustStoreFile
     * @param trustStoreType
     */
    protected synchronized static void loadStores(String tokenName, Provider provider, String keyStoreFile, char[] keyStorePass,
        String keyStoreType, String trustStoreFile, char[] trustStorePass, String trustStoreType) {

        try {
            KeyStore keyStore = loadKS(keyStoreType, provider, keyStoreFile, keyStorePass);
            KeyStore trustStore = loadKS(trustStoreType, provider, trustStoreFile, trustStorePass);
            keyStores.add(keyStore);
            trustStores.add(trustStore);
            keyStorePasswords.add(Arrays.copyOf(keyStorePass, keyStorePass.length));
            tokenNames.add(tokenName);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * This method load keystore with given keystore file and keystore password for a given keystore type and provider. It always
     * return a non-null keystore.
     *
     * @param keyStoreType
     * @param provider
     * @param keyStoreFile
     * @param keyStorePass
     * @retun keystore loaded
     */
    private static KeyStore loadKS(String keyStoreType, Provider provider, String keyStoreFile, char[] keyStorePass) throws Exception {
        KeyStore keyStore = null;
        if (provider != null) {
            keyStore = KeyStore.getInstance(keyStoreType, provider);
        } else {
            keyStore = KeyStore.getInstance(keyStoreType);
        }
        char[] passphrase = keyStorePass;

        FileInputStream istream = null;
        BufferedInputStream bstream = null;
        try {
            if (keyStoreFile != null) {
                if (_logger.isLoggable(FINE)) {
                    _logger.log(FINE, "Loading keystoreFile = {0}, keystorePass = {1}", new Object[] { keyStoreFile, keyStorePass });
                }
                istream = new FileInputStream(keyStoreFile);
                bstream = new BufferedInputStream(istream);
            }

            keyStore.load(bstream, passphrase);
        } finally {
            if (bstream != null) {
                bstream.close();
            }
            if (istream != null) {
                istream.close();
            }
        }

        return keyStore;
    }

    // --- implements SecuritySupport ---

    /**
     * This method returns an array of keystores containing keys and certificates.
     */
    @Override
    public KeyStore[] getKeyStores() {
        return keyStores.toArray(new KeyStore[keyStores.size()]);
    }

    @Override
    public KeyStore loadNullStore(String type, int index) throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
        KeyStore keyStore = KeyStore.getInstance(type);
        keyStore.load(null, keyStorePasswords.get(index));

        return keyStore;
    }

    @Override
    public KeyManager[] getKeyManagers(String algorithm) throws IOException, KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException {
        KeyStore[] kstores = getKeyStores();
        ArrayList<KeyManager> keyManagers = new ArrayList<>();
        for (int i = 0; i < kstores.length; i++) {
            checkCertificateDates(kstores[i]);
            KeyManagerFactory kmf = KeyManagerFactory
                .getInstance((algorithm != null) ? algorithm : KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(kstores[i], keyStorePasswords.get(i));
            KeyManager[] kmgrs = kmf.getKeyManagers();
            if (kmgrs != null) {
                keyManagers.addAll(Arrays.asList(kmgrs));
            }
        }

        KeyManager keyManager = new UnifiedX509KeyManager(keyManagers.toArray(new X509KeyManager[keyManagers.size()]), getTokenNames());
        return new KeyManager[] { keyManager };
    }

    @Override
    public TrustManager[] getTrustManagers(String algorithm) throws IOException, KeyStoreException, NoSuchAlgorithmException {
        KeyStore[] tstores = getTrustStores();
        ArrayList<TrustManager> trustManagers = new ArrayList<>();
        for (KeyStore tstore : tstores) {
            checkCertificateDates(tstore);
            TrustManagerFactory tmf = TrustManagerFactory
                .getInstance((algorithm != null) ? algorithm : TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(tstore);
            TrustManager[] tmgrs = tmf.getTrustManagers();
            if (tmgrs != null) {
                trustManagers.addAll(Arrays.asList(tmgrs));
            }
        }

        TrustManager trustManager;
        if (trustManagers.size() == 1) {
            trustManager = trustManagers.get(0);
        } else {
            trustManager = new UnifiedX509TrustManager(trustManagers.toArray(new X509TrustManager[trustManagers.size()]));
        }

        return new TrustManager[] { trustManager };
    }

    /*
     * Check X509 certificates in a store for expiration.
     */
    private void checkCertificateDates(KeyStore store) throws KeyStoreException {

        Enumeration<String> aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            var alias = aliases.nextElement();
            Certificate cert = store.getCertificate(alias);
            if (cert instanceof X509Certificate) {
                if (((X509Certificate) cert).getNotAfter().before(initDate)) {
                    _logger.log(Level.SEVERE, SSL_CERT_EXPIRED, new Object[] { alias, cert });
                }
            }
        }
    }

    /**
     * This method returns an array of truststores containing certificates.
     */
    @Override
    public KeyStore[] getTrustStores() {
        return trustStores.toArray(new KeyStore[trustStores.size()]);
    }

    @Override
    public boolean verifyMasterPassword(final char[] masterPass) {
        return Arrays.equals(masterPass, keyStorePasswords.get(0));
    }

    /**
     * This method returns an array of token names in order corresponding to array of keystores.
     */
    @Override
    public String[] getTokenNames() {
        return tokenNames.toArray(new String[tokenNames.size()]);
    }

    /**
     * @param token
     * @return a keystore
     */
    @Override
    public KeyStore getKeyStore(String token) {
        int idx = getTokenIndex(token);
        if (idx < 0) {
            return null;
        }

        return keyStores.get(idx);
    }

    /**
     * @param token
     * @return a truststore
     */
    @Override
    public KeyStore getTrustStore(String token) {
        int idx = getTokenIndex(token);
        if (idx < 0) {
            return null;
        }

        return trustStores.get(idx);
    }

    /**
     * @return returned index
     */
    private int getTokenIndex(String token) {
        int idx = -1;
        if (token != null) {
            idx = tokenNames.indexOf(token);
            if (idx < 0 && _logger.isLoggable(FINEST)) {
                _logger.log(FINEST, "token {0} is not found", token);
            }
        }
        return idx;
    }

    @Override
    public void synchronizeKeyFile(Object configContext, String fileRealmName) throws Exception {
    }

    @Override
    public PrivateKey getPrivateKeyForAlias(String alias, int keystoreIndex) throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException {
        Key key = keyStores.get(keystoreIndex).getKey(alias, keyStorePasswords.get(keystoreIndex));
        if (key instanceof PrivateKey) {
            return (PrivateKey) key;
        }

        return null;
    }

    public boolean isACC() {
        return processEnvironment == null ? false : processEnvironment.getProcessType().equals(ProcessType.ACC);
    }

    public boolean isNotServerORACC() {
        return processEnvironment.getProcessType().equals(ProcessType.Other);
    }


}
