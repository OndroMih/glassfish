/*
 * Copyright (c) 2024 Contributors to the Eclipse Foundation.
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

package com.sun.enterprise.security.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.catalina.HttpRequest;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Realm;
import org.apache.catalina.Request;
import org.apache.catalina.Response;
import org.apache.catalina.Session;
import org.apache.catalina.SessionEvent;
import org.apache.catalina.authenticator.Constants;
import org.apache.catalina.authenticator.SingleSignOn;
import org.apache.catalina.authenticator.SingleSignOnEntry;
import org.glassfish.web.LogFacade;

import static com.sun.logging.LogCleanerUtil.neutralizeForLog;

/**
 * A <strong>Valve</strong> that supports a "single sign on" user experience,
 * where the security identity of a user who successfully authenticates to one
 * web application is propogated to other web applications in the same
 * security domain.
 *
 * @author Jyri Virkki (first implementation)
 * @author Jean-Francois Arcand
 */
public class GlassFishSingleSignOn
    extends SingleSignOn
    /** CR 6411114 (Lifecycle implementation moved to ValveBase)
    implements Lifecycle, SessionListener, Runnable, SingleSignOnMBean {
    */
    // START CR 6411114
    implements Runnable, SingleSignOnMBean {
    // END CR 6411114

    // ----------------------------------------------------- Instance Variables

    /**
     * The log used by this class.
     */
    private static final Logger logger = LogFacade.getLogger();

    /**
     * The background thread.
     */
    private Thread thread = null;

    /**
     * The background thread completion semaphore.
     */
    private boolean threadDone = false;

    /**
     * The interval (in seconds) between checks for expired sessions.
     */
    private int ssoReapInterval = 60;

    /**
     * Max idle time (in seconds) for SSO entries before being elegible
     * for purging.
     * A value less than zero indicates that SSO entries are supposed
     * to never expire.
     */
    private int ssoMaxInactive = 300;

    //-------------------------------------------------------------- Monitoring

    /**
     * Number of cache hits
     */
    private AtomicInteger hitCount = new AtomicInteger(0);

    /**
     * Number of cache misses
     */
    private AtomicInteger missCount = new AtomicInteger(0);

    // ------------------------------------------------------------- Properties


    /**
     * Return expire thread interval (seconds)
     */
    public int getReapInterval() {

        return this.ssoReapInterval;

    }


    /**
     * Set expire thread interval (seconds)
     */
    public void setReapInterval(int t) {

        this.ssoReapInterval = t;

    }


    /**
     * Return max idle time for SSO entries (seconds)
     */
    public int getMaxInactive() {

        return this.ssoMaxInactive;

    }

    /**
     * Set max idle time for SSO entries (seconds)
     */
    public void setMaxInactive(int t) {

        this.ssoMaxInactive = t;

    }


    // ------------------------------------------------------ Lifecycle Methods


    /**
     * Prepare for the beginning of active use of the public methods of this
     * component.  This method should be called after <code>configure()</code>,
     * and before any of the public methods of the component are utilized.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that prevents this component from being used
     */
    @Override
    public void start() throws LifecycleException {

        // START CR 6411114
        if (started)            // Ignore multiple starts
            return;
        // END CR 6411114

        super.start();
        // Start the background reaper thread
        threadStart();

    }


    /**
     * Gracefully terminate the active use of the public methods of this
     * component.  This method should be the last one called on a given
     * instance of this component.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that needs to be reported
     */
    @Override
    public void stop() throws LifecycleException {

        /** CR 6411114
        super.stop();
        */
        // START CR 6411114
        if (!started)       // Ignore stop if not started
            return;
        // END CR 6411114

        // Stop the background reaper thread
        threadStop();
        // START CR 6411114
        super.stop();
        // END CR 6411114
    }


    // ------------------------------------------------ SessionListener Methods


    /**
     * Acknowledge the occurrence of the specified event.
     *
     * @param event SessionEvent that has occurred
     */
    @Override
    public void sessionEvent(SessionEvent event) {

        // We only care about session destroyed events
        if (!Session.SESSION_DESTROYED_EVENT.equals(event.getType()))
            return;

        // Look up the single session id associated with this session (if any)
        Session session = event.getSession();
        //S1AS8 6155481 START
        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, LogFacade.SESSION_DESTROYED, session);
        }
        //S1AS8 6155481 END
        String ssoId = session.getSsoId();
        if (ssoId == null) {
            return;
        }

        // Was the session destroyed as the result of a timeout?
        // If so, we'll just remove the expired session from the
        // SSO.  If the session was logged out, we'll log out
        // of all session associated with the SSO.
        if (session.hasExpired()) {
            removeSession(ssoId, session);
        } else {
            // The session was logged out.
            // Deregister this single session id, invalidating
            // associated sessions
            deregister(ssoId);
        }
    }


    // ---------------------------------------------------------- Valve Methods


    /**
     * Perform single-sign-on support processing for this request.
     *
     * @param request The servlet request we are processing
     * @param response The servlet response we are creating
     * @param context The valve context used to invoke the next valve
     *  in the current processing pipeline
     *
     * @exception IOException if an input/output error occurs
     * @exception ServletException if a servlet error occurs
     */
    /** IASRI 4665318
    public void invoke(Request request, Response response,
                       ValveContext context)
        throws IOException, ServletException {
     */
    // START OF IASRI 4665318
    @Override
    public int invoke(Request request, Response response)
        throws IOException, ServletException {
    // END OF IASRI 4665318

        // If this is not an HTTP request and response, just pass them on
        /* GlassFish 6386229
        if (!(request instanceof HttpRequest) ||
                !(response instanceof HttpResponse)) {
            // START OF IASRI 4665318
            // context.invokeNext(request, response);
            // return;
            return INVOKE_NEXT;
            // END OF IASRI 4665318
        }
        */
        HttpServletRequest hreq = (HttpServletRequest) request.getRequest();
        HttpServletResponse hres =
                        (HttpServletResponse) response.getResponse();
        request.removeNote(Constants.REQ_SSOID_NOTE);
        request.removeNote(Constants.REQ_SSO_VERSION_NOTE);

        // Has a valid user already been authenticated?
        //S1AS8 6155481 START
        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, LogFacade.REQUEST_PROCESSED, neutralizeForLog(hreq.getRequestURI()));
        }
        if (hreq.getUserPrincipal() != null) {
            //S1AS8 6155481 START
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, LogFacade.PRINCIPAL_ALREADY_AUTHENTICATED, neutralizeForLog(hreq.getUserPrincipal().getName()));
            }
            // START OF IASRI 4665318
            // context.invokeNext(request, response);
            // return;
            return INVOKE_NEXT;
            // END OF IASRI 4665318
        }

        // Check for the single sign on cookie
        //S1AS8 6155481 START
        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, LogFacade.CHECKING_SSO_COOKIE);
        }
        Cookie cookies[] = hreq.getCookies();
        if (cookies == null) {
            return INVOKE_NEXT;
        }
        Cookie cookie = null;
        Cookie versionCookie = null;
        for (Cookie c : cookies) {
            if (Constants.SINGLE_SIGN_ON_COOKIE.equals(c.getName())) {
                cookie = c;
            } else if (Constants.SINGLE_SIGN_ON_VERSION_COOKIE.equals(c.getName())) {
                versionCookie = c;
            }

            if (cookie != null && versionCookie != null) {
                break;
            }
        }
        if (cookie == null) {
            //S1AS8 6155481 START
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, LogFacade.SSO_COOKIE_NOT_PRESENT);
            }
            //S1AS8 6155481 END
            // START OF IASRI 4665318
            // context.invokeNext(request, response);
            // return;
            return INVOKE_NEXT;
            // END OF IASRI 4665318
        }

        // Get the realm associated with the app of this request.
        // If there is no realm available, do not process SSO.
        Realm realm = request.getContext().getRealm();
        if (realm == null) {
            //S1AS8 6155481 START
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, LogFacade.NO_REALM_CONFIGURED);
            }
            //S1AS8 6155481 END
            // START OF IASRI 4665318
            // context.invokeNext(request, response);
            // return;
            return INVOKE_NEXT;
            // END OF IASRI 4665318
        }

        String realmName = realm.getRealmName();
        if (realmName == null) {
            //S1AS8 6155481 START
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, LogFacade.NO_REALM_CONFIGURED);
            }
            //S1AS8 6155481 END
            // START OF IASRI 4665318
            // context.invokeNext(request, response);
            // return;
            return INVOKE_NEXT;
            // END OF IASRI 4665318
        }

        if (debug >= 1) {
            //S1AS8 6155481 START
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, LogFacade.APP_REALM);
            }
         }
        //S1AS8 6155481 END

        // Look up the cached Principal associated with this cookie value
        //S1AS8 6155481 START
        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, LogFacade.CHECKING_CACHED_PRINCIPAL);
        }

        long version = 0;
        if (isVersioningSupported() && versionCookie != null) {
            version = Long.parseLong(versionCookie.getValue());
        }
        SingleSignOnEntry entry = lookup(cookie.getValue(), version, request.getContext().getLoader().getClassLoader());
        if (entry != null) {
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, LogFacade.FOUND_CACHED_PRINCIPAL,
                        new Object[]{entry.getPrincipal().getName(), entry.getAuthType(), entry.getRealmName()});
            }
            //S1AS8 6155481 END

            // only use this SSO identity if it was set in the same realm
            if (entry.getRealmName().equals(realmName)) {
                request.setNote(Constants.REQ_SSOID_NOTE, cookie.getValue());
                ((HttpRequest) request).setAuthType(entry.getAuthType());
                ((HttpRequest) request).setUserPrincipal(entry.getPrincipal());
                // Touch the SSO entry access time
                entry.setLastAccessTime(System.currentTimeMillis());
                if (isVersioningSupported()) {
                    long ver = entry.incrementAndGetVersion();
                    request.setNote(Constants.REQ_SSO_VERSION_NOTE,
                            Long.valueOf(ver));
                }
                // update hit atomic counter
                hitCount.incrementAndGet();
            } else {
                //S1AS8 6155481 START
                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, LogFacade.IGNORING_SSO, realmName);
                }
                // consider this a cache miss, update atomic counter
                missCount.incrementAndGet();
            }
        } else {
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, LogFacade.NO_CACHED_PRINCIPAL_FOUND);
            }
            cookie.setMaxAge(0);
            hres.addCookie(cookie);
            //update miss atomic counter
            missCount.incrementAndGet();
        }
        //S1AS8 6155481 END
        // Invoke the next Valve in our pipeline
        // START OF IASRI 4665318
        // context.invokeNext(request, response);
        // return;
        return INVOKE_NEXT;
        // END OF IASRI 4665318

    }


    // -------------------------------------------------------- Package Methods


    /**
     * Deregister the specified single sign on identifier, and invalidate
     * any associated sessions.
     *
     * @param ssoId Single sign on identifier to deregister
     */
    protected void deregister(String ssoId) {

        //S1AS8 6155481 START
        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, LogFacade.DEREGISTER_SSO);
        }
        //S1AS8 6155481 END
        // Look up and remove the corresponding SingleSignOnEntry
        SingleSignOnEntry sso = null;
        synchronized (cache) {
            sso = cache.remove(ssoId);
        }

        if (sso == null)
            return;

        // Expire any associated sessions
        sso.expireSessions();

        // NOTE:  Clients may still possess the old single sign on cookie,
        // but it will be removed on the next request since it is no longer
        // in the cache
    }


    // ------------------------------------------------------ Protected Methods


    /**
     * Invalidate all SSO cache entries that have expired.
     */
    private void processExpires() {

        if (ssoMaxInactive < 0) {
            // SSO entries are supposed to never expire
            return;
        }

        long tooOld = System.currentTimeMillis() - ssoMaxInactive * 1000L;
        //S1AS8 6155481 START
        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, LogFacade.SSO_EXPIRATION_STARTED, cache.size());
        }
        //S1AS8 6155481 END
        ArrayList<String> removals = new ArrayList<String>(cache.size()/2);

        // build list of removal targets

        // Note that only those SSO entries which are NOT associated with
        // any session are elegible for removal here.
        // Currently no session association ever happens so this covers all
        // SSO entries. However, this should be addressed separately.

        try {
            synchronized (cache) {

                Iterator<String> it = cache.keySet().iterator();
                while (it.hasNext()) {
                    String key = it.next();
                    SingleSignOnEntry sso = cache.get(key);
                    if (sso.isEmpty() && sso.getLastAccessTime() < tooOld) {
                        removals.add(key);
                    }
                }
            }

            int removalCount = removals.size();
            //S1AS8 6155481 START
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, LogFacade.SSO_CACHE_EXPIRE, removalCount);
            }
            //S1AS8 6155481 END
            // deregister any elegible sso entries
            for (int i=0; i < removalCount; i++) {
                //S1AS8 6155481 START
                if (logger.isLoggable(Level.FINE)) {
                    logger.log(Level.FINE, LogFacade.SSO_EXPRIRATION_REMOVING_ENTRY, removals.get(i));
                }
                deregister(removals.get(i));
            }
            //S1AS8 6155481 END
        } catch (Throwable e) { // don't let thread die
            logger.log(Level.WARNING, LogFacade.EXCEPTION_DURING_SSO_EXPIRATION, e);
        }
    }


    /**
     * Sleep for the duration specified by the <code>ssoReapInterval</code>
     * property.
     */
    private void threadSleep() {

        try {
            Thread.sleep(ssoReapInterval * 1000L);
        } catch (InterruptedException e) {
            ;
        }

    }


   /**
     * Start the background thread that will periodically check for
     * SSO timeouts.
     */
    private void threadStart() {

        if (thread != null)
            return;

        threadDone = false;
        String threadName = "SingleSignOnExpiration";
        thread = new Thread(this, threadName);
        thread.setDaemon(true);
        thread.start();

    }


    /**
     * Stop the background thread that is periodically checking for
     * SSO timeouts.
     */
    private void threadStop() {

        if (thread == null)
            return;

        threadDone = true;
        thread.interrupt();
        try {
            thread.join();
        } catch (InterruptedException e) {
            ;
        }

        thread = null;

    }


    // ------------------------------------------------------ Background Thread


    /**
     * The background thread that checks for SSO timeouts and shutdown.
     */
    @Override
    public void run() {

        // Loop until the termination semaphore is set
        while (!threadDone) {
            threadSleep();
            processExpires();
        }

    }

    /**
     * Remove a single Session from a SingleSignOn.  Called when
     * a session is timed out and no longer active.
     *
     * @param ssoId Single sign on identifier from which to remove the session.
     * @param session the session to be removed.
     */
    protected void removeSession(String ssoId, Session session) {

        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, LogFacade.REMOVE_SESSION_FROM_SSO, new Object[]{session.toString(), ssoId});
        }

        // Get a reference to the SingleSignOn
        SingleSignOnEntry entry = lookup(ssoId);
        if (entry == null)
            return;

        // Remove the inactive session from SingleSignOnEntry
        entry.removeSession(session);

        // If there are not sessions left in the SingleSignOnEntry,
        // deregister the entry.
        if (entry.isEmpty()) {
            deregister(ssoId);
        }
    }

    //-------------------------------------------------- Monitoring Support

    /**
     * Gets the number of sessions participating in SSO
     *
     * @return Number of sessions participating in SSO
     */
    @Override
    public int getActiveSessionCount() {
        return cache.size();
    }


    /**
     * Gets the number of SSO cache hits
     *
     * @return Number of SSO cache hits
     */
    @Override
    public int getHitCount() {
        return hitCount.intValue();
    }


    /**
     * Gets the number of SSO cache misses
     *
     * @return Number of SSO cache misses
     */
    @Override
    public int getMissCount() {
        return missCount.intValue();
    }

}


