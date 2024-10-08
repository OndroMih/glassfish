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

package com.sun.jts.jtsxa;

import com.sun.jts.CosTransactions.Configuration;
import com.sun.jts.codegen.jtsxa.OTSResource;
import com.sun.jts.codegen.jtsxa.OTSResourceHelper;
import com.sun.jts.codegen.jtsxa.OTSResourcePOA;
import com.sun.jts.jta.TransactionState;
import com.sun.jts.utils.LogFormatter;
import com.sun.logging.LogDomains;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.omg.CORBA.CompletionStatus;
import org.omg.CORBA.Context;
import org.omg.CORBA.ContextList;
import org.omg.CORBA.ExceptionList;
import org.omg.CORBA.INTERNAL;
import org.omg.CORBA.NVList;
import org.omg.CORBA.NamedValue;
import org.omg.CORBA.Request;
import org.omg.CORBA.SystemException;
import org.omg.CORBA.TRANSACTION_ROLLEDBACK;
import org.omg.CORBA.TRANSIENT;
import org.omg.CosTransactions.HeuristicCommit;
import org.omg.CosTransactions.HeuristicHazard;
import org.omg.CosTransactions.HeuristicMixed;
import org.omg.CosTransactions.HeuristicRollback;
import org.omg.CosTransactions.NotPrepared;
import org.omg.CosTransactions.Vote;
import org.omg.CosTransactions.otid_t;
import org.omg.PortableServer.POA;

/**
 * An implementation of org.omg.CosTransactions.Resource to support
 * X/Open XA compliant resource managers.
 */
public class OTSResourceImpl extends OTSResourcePOA implements OTSResource {

    private static POA poa = null;
    private OTSResource thisRef = null;
    private XAResource xaRes = null;
    private Xid xid = null;
    private TransactionState tranState = null;


    /*
        Logger to log transaction messages
    */
    static Logger _logger = LogDomains.getLogger(OTSResourceImpl.class, LogDomains.TRANSACTION_LOGGER);
    /**
     * Construct an XAResource object.
     *
     * @param xid        the global transaction identifier
     * @param xaRes   the XAServer object associated with the resource manager
     */

    public OTSResourceImpl(Xid xid, XAResource xaRes,
            TransactionState tranState) {
        this.xaRes= xaRes;          // Stash away the XAServer object
        this.xid= xid;              // Stash away the Transaction identifier
        this.tranState = tranState;
    }

    /**
     * Commit a transaction.
     *
     * @exception NotPrepared        the resource manager has not been called
     *                               for prepare.
     * @exception HeuristicRollback  a heuristic decision has been made,
     *                               and the resource rolledback.
     * @exception HeuristicHazard    a heuristic decision has been made,
     *                               but the disposition of
     *                               the resource is unknown.
     * @exception HeuristicMixed     a heuristic decision has been made, some
     *                               updates have been commited
     *                               and others rolled back.
     * @exception SystemException    an unindentified error has been reported
     *                               by the resource manager.
     */

    public void commit() throws NotPrepared, HeuristicRollback,
            HeuristicHazard, HeuristicMixed, SystemException {

        //ensureInitialized();

        try {
            xaRes.commit(xid, false);
        } catch (Exception ex) {
            destroy();
            if (!(ex instanceof XAException)) {
                INTERNAL internal =  new INTERNAL(0,CompletionStatus.COMPLETED_MAYBE);
                internal.initCause(ex);
                _logger.log(Level.WARNING, "jts.unexpected_error_occurred_twopc_commit", ex);
                throw internal;
            }

            XAException e = (XAException) ex;
            if (_logger.isLoggable(Level.FINE))
                _logger.log(Level.FINE, "An XAException occurred in twopc commit", e);
            if (e.errorCode == XAException.XA_HEURRB)
                throw new HeuristicRollback(ex.getMessage());
            if (e.errorCode == XAException.XA_HEURHAZ)
                throw new HeuristicHazard(ex.getMessage());
            if (e.errorCode == XAException.XA_HEURMIX)
                throw new HeuristicMixed(ex.getMessage());
            if (e.errorCode == XAException.XA_RBPROTO)
                throw new NotPrepared(ex.getMessage());
            if (e.errorCode == XAException.XA_HEURCOM) {
                // Can't throw HeuristicCommit exception because org.omg.CosTransactions.ResourceOperations#commit
                // doesn't declare it
                HeuristicHazard hh = new HeuristicHazard(ex.getMessage());
                hh.initCause(ex);
                throw hh;
            }
            if ((e.errorCode == XAException.XA_RETRY) ||
                (e.errorCode == XAException.XA_RBTRANSIENT) ||
                (e.errorCode == XAException.XA_RBCOMMFAIL)) {
                throw new TRANSIENT();
            }
            if (e.errorCode >= XAException.XA_RBBASE &&
                e.errorCode <= XAException.XA_RBEND) {
                throw new HeuristicRollback(ex.getMessage());
            }
            INTERNAL internal =  new INTERNAL(0,CompletionStatus.COMPLETED_MAYBE);
            internal.initCause(ex);
            _logger.log(Level.WARNING, "jts.unexpected_error_occurred_twopc_commit", ex);
            throw internal;
        }

        destroy();
        return;
    }

    /**
     * Commit a transaction, using one-phase optimization.
     *
     * @exception HeuristicHazard    a heuristic decision has been made,
     *                               but the disposition of
     *                               the resource is unknown.
     * @exception SystemException    an unindentified error has been reported
     *                               by the resource manager.
     */
    public void commit_one_phase() throws HeuristicHazard, SystemException {

        //ensureInitialized();

        try {
            xaRes.commit(xid, true);
        } catch (Exception ex) {
            destroy();
            if (!(ex instanceof XAException)) {
                INTERNAL internal =  new INTERNAL(0,CompletionStatus.COMPLETED_MAYBE);
                internal.initCause(ex);
                _logger.log(Level.WARNING, "jts.unexpected_error_occurred_twopc_commit", ex);
                throw internal;
            }
            XAException e = (XAException) ex;
            if (_logger.isLoggable(Level.FINE))
                _logger.log(Level.FINE, "An XAException occurred in c_o_p", e);
            if (e.errorCode == XAException.XA_HEURRB)
                throw new HeuristicHazard(ex.getMessage());
            if (e.errorCode == XAException.XA_HEURHAZ)
                throw new HeuristicHazard(ex.getMessage());

        /** XA_HEURMIX should translate to HeuristicMixedException
            if (e.errorCode == XAException.XA_HEURMIX)
                throw new HeuristicHazard(ex.getMessage());
            //IASRI START 4722883
            /**
            if (e.errorCode >= XAException.XA_RBBASE &&
                e.errorCode <= XAException.XA_RBEND)
                return;
            if (e.errorCode == XAException.XA_HEURCOM)
                return;
            **/
            if ((e.errorCode == XAException.XA_RETRY) ||
                    (e.errorCode == XAException.XA_RBTRANSIENT) ||
                    (e.errorCode == XAException.XA_RBCOMMFAIL))
                throw new TRANSIENT();

            // Use HeuristicHazard as a temp exception because CosTransactions.idl Resource
            // has commit_one_phase() defined to throw only HeuristicHazard
            if (e.errorCode >= XAException.XA_RBBASE && e.errorCode <= XAException.XA_RBEND ||
                e.errorCode == XAException.XA_HEURMIX || e.errorCode == XAException.XA_HEURCOM) {
                HeuristicHazard hazex = new HeuristicHazard();
                ((Throwable)hazex).initCause((Throwable)ex);
                throw hazex;
            }
            //IASRI END 4722883
            if (e.errorCode == XAException.XAER_RMERR || e.errorCode == XAException.XAER_NOTA) {
                _logger.log(Level.WARNING, "jts.unexpected_error_occurred_twopc_commit", ex);
                throw new TRANSACTION_ROLLEDBACK(0, CompletionStatus.COMPLETED_NO);
            }

            INTERNAL internal =  new INTERNAL(0,CompletionStatus.COMPLETED_MAYBE);
            internal.initCause(ex);
            _logger.log(Level.WARNING, "jts.unexpected_error_occurred_twopc_commit", ex);
            throw internal;
        }

        destroy();
        return;
    }

    /**
     * The resource manager can forget all knowledge of the transaction.
     *
     */
    public void forget() {

        //ensureInitialized();

        // Perform the XA operation.
        try {
            xaRes.forget(xid);
        } catch (XAException e) {
            if (_logger.isLoggable(Level.FINE))
                _logger.log(Level.FINE,"An XAException occurred in forget", e);
            // currently do nothing..
        }

        destroy();
        return;
    }

    /**
     * Prepare a transaction.
     *
     * <p>This is the first phase of the two-phase commit protocol.
     *
     * @exception HeuristicHazard    a heuristic decision has been made,
     *                               but the disposition of
     *                               the resource is unknown.
     * @exception HeuristicMixed     a heuristic decision has been made,
     *                               some updates have been commited
     *                               and others rolled back.
     */
    public Vote prepare() throws HeuristicHazard, HeuristicMixed {

        //ensureInitialized();

        int rc = XAException.XAER_PROTO;

        // Perform the XA operation.

        try {
            rc = xaRes.prepare(xid); // xa_prepare()
        } catch (XAException e) {
            if (_logger.isLoggable(Level.FINE))
                _logger.log(Level.FINE,"An XAException occurred in prepare", e);
            // currently do nothing..
            if (e.errorCode == XAException.XAER_RMFAIL ||
                e.errorCode == XAException.XAER_RMERR) {
                throw new RuntimeException(e);
            } else if (e.errorCode == XAException.XAER_PROTO ||
                e.errorCode == XAException.XAER_INVAL) {
                throw new INTERNAL(e.getMessage(), 0, CompletionStatus.COMPLETED_MAYBE);
            }
        }

        // Convert to Vote
        if (rc == XAResource.XA_OK) {
            return Vote.VoteCommit;
        }

        if (rc == XAResource.XA_RDONLY) {
            destroy();
            return Vote.VoteReadOnly;
        }

        if (rc == Configuration.LAO_PREPARE_OK) {
            destroy();
            return null;
        }


        destroy();
        return Vote.VoteRollback; // Any other return code is rollback
    }

    /**
     * Rollback a transaction.
     *
     * @exception HeuristicCommit    a heuristic decision has been made,
     *                               and the resource committed.
     * @exception HeuristicHazard    a heuristic decision has been made,
     *                               but the disposition of
     *                               the resource is unknown.
     * @exception HeuristicMixed     a heuristic decision has been made,
     *                               some updates have been commited
     *                               and others rolled back.
     * @exception SystemException    an unindentified error has been reported
     *                               by the resource manager
     */
    public  void rollback() throws HeuristicCommit, HeuristicMixed,
            HeuristicHazard, SystemException {

        //ensureInitialized();

        try {
            if (tranState == null) {
                // this block will be entered during recovery processing.
                // there is no tranState object available during recovery.
                xaRes.rollback(xid);
            } else {
                // need to worry about asynchronous rollback
                tranState.rollback(xaRes);
            }
        } catch (Exception ex) {
            destroy();
            if (!(ex instanceof XAException)) {
                INTERNAL internal =  new INTERNAL(0,CompletionStatus.COMPLETED_MAYBE);
                internal.initCause(ex);
                _logger.log(Level.WARNING, "jts.unexpected_error_occurred_twopc_rollback", ex);
                throw internal;
            }

            XAException e = (XAException) ex;
            if (_logger.isLoggable(Level.FINE))
                _logger.log(Level.FINE, "An XAException occurred in rollback", e);
            if (e.errorCode == XAException.XA_HEURCOM)
                throw new HeuristicCommit(ex.getMessage());
            if (e.errorCode == XAException.XA_HEURHAZ)
                throw new HeuristicHazard(ex.getMessage());
            if (e.errorCode == XAException.XA_HEURMIX)
                throw new HeuristicMixed(ex.getMessage());
            if ((e.errorCode == XAException.XA_RETRY) ||
                (e.errorCode == XAException.XA_RBTRANSIENT) ||
                (e.errorCode == XAException.XA_RBCOMMFAIL)) {
                throw new TRANSIENT();
            }
            if (e.errorCode == XAException.XAER_RMERR ||
                e.errorCode == XAException.XA_RBROLLBACK ||
                e.errorCode == XAException.XAER_NOTA ||
                e.errorCode == XAException.XAER_RMFAIL) {
                _logger.log(Level.WARNING, "jts.unexpected_error_occurred_twopc_rollback", ex);
                throw new TRANSACTION_ROLLEDBACK(0, CompletionStatus.COMPLETED_MAYBE);
            }
            if (e.errorCode == XAException.XA_HEURRB) {
                // Can't throw HeuristicRollback exception because org.omg.CosTransactions.ResourceOperations#rollback
                // doesn't declare it
                HeuristicHazard hh = new HeuristicHazard(ex.getMessage());
                hh.initCause(ex);
                throw hh;
            }
            INTERNAL internal =  new INTERNAL(0,CompletionStatus.COMPLETED_MAYBE);
            internal.initCause(ex);
            _logger.log(Level.WARNING, "jts.unexpected_error_occurred_twopc_rollback", ex);
            throw internal;
        }

        destroy();
        return;
    }

    /**
     * Return the global transaction identifier.
     *
     * @return the global transaction identifier as defined
     *         by org.omg.CosTransactions.otid_t
     *
     */
    public otid_t getGlobalTID() {

        byte[] gtrid = xid.getGlobalTransactionId();
        byte[] otidData = new byte[gtrid.length];
        System.arraycopy(gtrid,0,otidData,0,gtrid.length);
        otid_t otid = new otid_t(xid.getFormatId(),0,otidData);

        return otid;
    }

    /**
     * Returns the CORBA Object which represents this object.
     *
     * @return  The CORBA object.
     *
     */
    public OTSResource getCORBAObjReference() {

        if (thisRef == null) {
            if (Configuration.getORB() == null) {
                return this;
            }

            if (poa == null) {
                poa = Configuration.getPOA("transient"/*#Frozen*/);
            }

            try {
                poa.activate_object(this);
                thisRef = OTSResourceHelper.
                            narrow(poa.servant_to_reference(this));
                //thisRef = (com.sun.jts.jtsxa.OTSResource)this;
            } catch (Exception exc) {
                _logger.log(Level.SEVERE,"jts.create_xaresource_object_error", exc);
                String msg = LogFormatter.getLocalizedMessage(_logger,
                            "jts.create_xaresource_object_error");
                throw  new org.omg.CORBA.INTERNAL(msg);
            }
        }

        return thisRef;
    }

    public final String toString() {
        return "OTSResource : XAResource " + xaRes + " XID " + xid;
    }


    /**
     * Destroy the OTSResourceImpl object.
     *
     * @param  servant the object to be destroyed.
     *
     */
    private void destroy() {

        if (poa != null && thisRef != null) {
            try {
                poa.deactivate_object(poa.reference_to_id(thisRef));
                thisRef = null;
            } catch (Exception exc) {
                _logger.log(Level.WARNING,"jts.object_destroy_error","OTSResource");
            }
        }
    }

    /**
     * Ensure that the Native XA interface is initialized.
     *
     * Invoke the nativeXA initializer to ensure that this resource has had
     * any necessary initialization performed on this thread.
     * This will be necessary in the server environment where we
     * have not been invoked via a transactional flow, and
     * we may very well be executing on a new thread. The resource manager may
     * have specific requirements, but it should not matter if initialization
     * is performed twice.
     *
     * We also call down to the native interface informing that the transaction
     * is about to complete. This permits any database specific action to be
     * performed.prior to the actual xa operations marking completion.
     *
    private void ensureInitialized() {
        // COMMENT(Ram J) - we do not support native xa drivers.
        if (this.xaRes instanceof NativeXAResourceImpl) {
            NativeXAResourceImpl xaResImpl = (NativeXAResourceImpl) xaRes;

            xaResImpl.nativeXA.initialize(xaResImpl,
                xaResImpl.xaswitch,
                xaResImpl.open,
                xaResImpl.close,
                xaResImpl.getRMID(),
                false);

            xaResImpl.nativeXA.aboutToComplete(xaResImpl, (XID) xid);
        }
    }
     */

    /*
     * These methods are there to satisy the compiler. At some point
     * when we move towards a tie based model, the org.omg.Corba.Object
     * interface method implementation below shall be discarded.
     */

    public org.omg.CORBA.Object _duplicate() {
        throw new org.omg.CORBA.NO_IMPLEMENT("This is a locally constrained object.");
    }

    public void _release() {
        throw new org.omg.CORBA.NO_IMPLEMENT("This is a locally constrained object.");
    }

    public boolean _is_a(String repository_id) {
        throw new org.omg.CORBA.NO_IMPLEMENT("This is a locally constrained object.");
    }

    public boolean _is_equivalent(org.omg.CORBA.Object that) {
        throw new org.omg.CORBA.NO_IMPLEMENT("This is a locally constrained object.");
    }

    public boolean _non_existent() {
        throw new org.omg.CORBA.NO_IMPLEMENT("This is a locally constrained object.");
    }

    public int _hash(int maximum) {
        throw new org.omg.CORBA.NO_IMPLEMENT("This is a locally constrained object.");
    }

    public Request _request(String operation) {
        throw new org.omg.CORBA.NO_IMPLEMENT("This is a locally constrained object.");
    }

    public Request _create_request(Context ctx,
                   String operation,
                   NVList arg_list,
                   NamedValue result) {
        throw new org.omg.CORBA.NO_IMPLEMENT("This is a locally constrained object.");
    }

    public Request _create_request(Context ctx,
                   String operation,
                   NVList arg_list,
                   NamedValue result,
                   ExceptionList exceptions,
                   ContextList contexts) {
        throw new org.omg.CORBA.NO_IMPLEMENT("This is a locally constrained object.");
    }

    public org.omg.CORBA.Object _get_interface_def() {
        throw new org.omg.CORBA.NO_IMPLEMENT("This is a locally constrained object.");
    }

    public org.omg.CORBA.Policy _get_policy(int policy_type) {
        throw new org.omg.CORBA.NO_IMPLEMENT("This is a locally constrained object.");
    }

    public org.omg.CORBA.DomainManager[] _get_domain_managers() {
        throw new org.omg.CORBA.NO_IMPLEMENT("This is a locally constrained object.");
    }

    public org.omg.CORBA.Object _set_policy_override(
            org.omg.CORBA.Policy[] policies,
            org.omg.CORBA.SetOverrideType set_add) {
        throw new org.omg.CORBA.NO_IMPLEMENT("This is a locally constrained object.");
    }
}
