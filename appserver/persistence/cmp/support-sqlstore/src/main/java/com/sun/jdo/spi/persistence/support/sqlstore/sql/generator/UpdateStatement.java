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

/*
 * UpdateStatement.java
 *
 * Created on October 3, 2001
  *
 */

package com.sun.jdo.spi.persistence.support.sqlstore.sql.generator;

import com.sun.jdo.spi.persistence.support.sqlstore.SQLStateManager;
import com.sun.jdo.spi.persistence.support.sqlstore.Transaction;
import com.sun.jdo.spi.persistence.support.sqlstore.database.DBVendorType;
import com.sun.jdo.spi.persistence.support.sqlstore.model.ForeignFieldDesc;
import com.sun.jdo.spi.persistence.support.sqlstore.model.LocalFieldDesc;
import com.sun.jdo.spi.persistence.support.sqlstore.sql.UpdateObjectDescImpl;
import com.sun.jdo.spi.persistence.support.sqlstore.sql.constraint.ConstraintValue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.netbeans.modules.dbschema.ColumnElement;

/**
 * This class is used to generate update/insert/delete statements.
 */
public class UpdateStatement extends Statement implements Cloneable {

    public int minAffectedRows;

    private Map dbStatementCache = new HashMap();

    /** The UpdateQueryplan */
    UpdateQueryPlan plan;

    /** List of ColumnRef for the where clause used during batch. */
    private List columnRefsForWhereClause;

    /** List of version columns */
    private List versionColumns;

    /** Flag indicating whether we use batch. */
    private boolean batch = false;

    /** Insert values for INSERT statements. */
    private StringBuffer values;

    /** */
    private boolean isConstraintAdded;

    /** Name of the USE_BATCH property. */
    public static final String UPDATE_VERSION_COL_PROPERTY =
        "com.sun.jdo.spi.persistence.support.sqlstore.sql.generator.UPDATE_VERSION_COL"; // NOI18N

    /**
     * Property to swich on/off updating of version col.
     * Note, the default is true, meaning we try to update version col if the
     * property is not specified.
     */
    private static final boolean UPDATE_VERSION_COL = Boolean.valueOf(
        System.getProperty(UPDATE_VERSION_COL_PROPERTY, "true")).booleanValue(); // NOI18N


    public UpdateStatement(DBVendorType vendorType, UpdateQueryPlan plan, boolean batch) {
        super(vendorType);
        this.plan = plan;
        columnRefsForWhereClause = new ArrayList();
        this.batch = batch;
        minAffectedRows = 1;
    }

    public void addColumn(ColumnElement columnElement, Object value) {
        addColumnRef(new ColumnRef(columnElement, value));
    }

    /**
     * Batch helper method. Adds the columnElement to the list of
     * ColumnRefs for the where clause and then calls addConstraint.
     */
    protected void addConstraint(ColumnElement columnElement,
                                 LocalFieldDesc lf, Object value) {
        columnRefsForWhereClause.add(new ColumnRef(columnElement, value));
        addConstraint(lf, value);
    }

    /** Calculates the index of the where clause ColumnRefs */
    private void calculateWhereClauseColumnRefIndexes() {
        // calculate where clause column ref indexes
        // NOTE, the sqlstore processes the constraints in reverse order,
        // so start with the last index and decrement
        int nextIndex = columns.size() + columnRefsForWhereClause.size();
        for (Iterator i = columnRefsForWhereClause.iterator(); i.hasNext(); ) {
            ColumnRef columnRef = (ColumnRef)i.next();
            columnRef.setIndex(nextIndex--);
        }
    }

    public boolean isConstraintAdded() {
        return isConstraintAdded;
    }

    public void markConstraintAdded() {
        isConstraintAdded = true;
    }

    /** @inheritDoc */
    public QueryPlan getQueryPlan() {
         return plan;
    }

    /**
     * @inheritDoc
     */
    protected void generateStatementText() {

        statementText = new StringBuffer();

        StringBuffer columnList = generateColumnText();
        StringBuffer constraint = processConstraints();
        String tableName = ((QueryTable) tableList.get(0)).getTableDesc().getName();

        // Create the query filling in the column list, table name, etc.
        switch (action) {
            case QueryPlan.ACT_UPDATE:
                statementText.append("update ");// NOI18N
                appendQuotedText(statementText, tableName);
                statementText.append(" set ").append(columnList).append(" where ").append(constraint); // NOI18N
                break;

            case QueryPlan.ACT_DELETE:
                statementText.append("delete from ");// NOI18N
                appendQuotedText(statementText, tableName);
                statementText.append(" where ").append(constraint); // NOI18N
                break;

            case QueryPlan.ACT_INSERT:
                statementText.append("insert into ");// NOI18N
                appendQuotedText(statementText, tableName);
                statementText.append("(").append(columnList).// NOI18N
                        append(") values ").append("(").append(values).append(")"); // NOI18N
                break;
        }

        calculateWhereClauseColumnRefIndexes();
    }

    private StringBuffer generateColumnText() {
        StringBuffer columnList = new StringBuffer();
        int numValues = -1;

        for (int i = 0; i < columns.size(); i++) {
            ColumnRef c = (ColumnRef) columns.get(i);

            if (columnList.length() > 0) {
                columnList.append(", "); // NOI18N
            }
            switch (action) {
                case QueryPlan.ACT_UPDATE:
                    appendQuotedText(columnList, c.getName());
                    columnList.append("= ?"); // NOI18N
                    break;

                case QueryPlan.ACT_INSERT:
                    appendQuotedText(columnList, c.getName());
                    if (i == 0) {
                        values = new StringBuffer().append(" ?"); // NOI18N
                    } else {
                        values.append(", ?"); // NOI18N
                    }
                    break;
            }

            // Do not create an InputValue in the case of batch update.
            // Method bindInputValues will get the value using the ColumnRef.
            if (!batch &&
                ((action == QueryPlan.ACT_UPDATE) ||
                    (action == QueryPlan.ACT_INSERT))) {
                numValues = numValues + 1;
                InputValue val = new InputValue(c.getValue(), c.getColumnElement());
                inputDesc.values.add(numValues, val);
            }
        }

        appendVersionColumnUpdateClause(columnList);

        return columnList;
    }

    /**
     * Appends clause to update version column. The generated clause will be of
     * the following form
     * <code> versionColumnName = versionColumnNane + 1 </code>
     * @param setClause Text for the set clause of update statement
     */
    private void appendVersionColumnUpdateClause(StringBuffer setClause) {
        if(UPDATE_VERSION_COL) {
            if (versionColumns != null)
            {
                for (int i = 0; i < versionColumns.size(); i++) {
                    ColumnElement columnElement = (ColumnElement) versionColumns.get(i);
                    String columnName = columnElement.getName().getName();
                    setClause.append(", ");
                    appendQuotedText(setClause, columnName);
                    setClause.append(" = ");
                    appendQuotedText(setClause, columnName);
                    setClause.append(" + ");
                    setClause.append("1");
                }
            }
        }
    }


    public void addLocalConstraints(int action, ForeignFieldDesc f, SQLStateManager sm) {
        for (int i = 0; i < f.localFields.size(); i++) {
            LocalFieldDesc lf = (LocalFieldDesc) f.localFields.get(i);

            if (action == QueryPlan.ACT_INSERT) {
                // For inserts into the join table, we get the values we are inserting
                // for the parent object and the added object.
                ColumnElement lc = (ColumnElement) f.assocLocalColumns.get(i);

                addColumn(lc, lf.getValue(sm));
            } else if (action == QueryPlan.ACT_DELETE) {
                LocalFieldDesc alf = (LocalFieldDesc) f.assocLocalFields.get(i);

                // For deletes from the join table, we get the constraint values
                // from the parent object and the remove object.
                addConstraint(alf, lf.getValue(sm));
            }
        }
    }

    public void addForeignConstraints(int action, ForeignFieldDesc f, SQLStateManager sm) {
        for (int i = 0; i < f.foreignFields.size(); i++) {
            LocalFieldDesc ff = (LocalFieldDesc) f.foreignFields.get(i);

            if (action == QueryPlan.ACT_INSERT) {
                // For inserts into the join table, we get the values we are inserting
                // for the parent object and the added object.
                ColumnElement fc = (ColumnElement) f.assocForeignColumns.get(i);

                addColumn(fc, ff.getValue(sm));
            } else if (action == QueryPlan.ACT_DELETE) {
                LocalFieldDesc aff = (LocalFieldDesc) f.assocForeignFields.get(i);

                // For deletes from the join table, we get the constraint values
                // from the parent object and the remove object.
                addConstraint(aff, ff.getValue(sm));
            }
        }
    }

    /**
     * Redefines processConstraintValue in order to skip the creation of
     * an InputValue in the case of batch.
     */
    protected void processConstraintValue(ConstraintValue node, StringBuffer result) {
        result.append("?"); // NOI18N
        if (!batch)
            generateInputValueForConstraintValueNode(node);
    }

    /**
     * Returns the cached db statement for the specified connection.
     * If there is not any statement for this connection in the cache,
     * then a new statement is created.
     * @param tran the transaction
     * @param conn the connection
     * @return the statement
     */
    public DBStatement getDBStatement(Transaction tran, Connection conn)
        throws SQLException
    {
        DBStatement dbStatement = null;

        synchronized (dbStatementCache)
        {
            // dbStatement cachelookup
            dbStatement = (DBStatement)dbStatementCache.get(tran);

            if (dbStatement == null) {
                dbStatement = new DBStatement(conn, getText(),
                                              tran.getUpdateTimeout());
                // put dbStatement in cache
                dbStatementCache.put(tran, dbStatement);
            }
        }

        return dbStatement;
    }

    /** */
    public boolean exceedsBatchThreshold(Transaction tran)
    {
        synchronized (dbStatementCache)
        {
            DBStatement dbStatement = (DBStatement)dbStatementCache.get(tran);
            return (dbStatement != null) && dbStatement.exceedsBatchThreshold();
        }
    }

    /**
     * Removes the db statement for the specified connection from the cache
     * and closes this statement.
     * @param tran the transaction
     */
    public DBStatement removeDBStatement(Transaction tran)
    {
        synchronized (dbStatementCache)
        {
            DBStatement s = (DBStatement)dbStatementCache.remove(tran);
            return s;
        }
    }

    public void bindInputColumns(DBStatement s,
                                 UpdateObjectDescImpl updateDesc)
            throws SQLException {

        // bind set clause (if necessary)
        for (Iterator i = getColumnRefs().iterator(); i.hasNext(); ) {
            bindInputColumn(s, (ColumnRef)i.next(), updateDesc, false );
        }
        // bind where clause (if necessary)
        for (Iterator i = columnRefsForWhereClause.iterator(); i.hasNext(); ) {
            bindInputColumn(s, (ColumnRef) i.next(), updateDesc,
                    updateDesc.isBeforeImageRequired());
        }

    }

    /**
     * Binds the value in the specified update descriptor corresponding
     * with the specified column reference to the specified statement.
     * @param stmt the statement
     * @param columnRef the column reference
     * @param updateDesc the update descriptor
     * @throws SQLException thrown by setter methods on java.sql.PreparedStatement
     */
    private void bindInputColumn(DBStatement stmt,
                                 ColumnRef columnRef,
                                 UpdateObjectDescImpl updateDesc,
                                 boolean getBeforeValue) throws SQLException {

        Object inputValue = getInputValue(updateDesc, columnRef, getBeforeValue);
        stmt.bindInputColumn(columnRef.getIndex(), inputValue,
                columnRef.getColumnElement(), vendorType);
    }

    /**
     * Get Input values to be bound to this statement.
     * @param updateDesc The update descriptor.
     * @return An Object array containing input values to be bound to this statement.
     */
    private Object[] getInputValues(UpdateObjectDescImpl updateDesc) {
        Object[] inputValues =
                new Object[getColumnRefs().size() + columnRefsForWhereClause.size()];
        for (Iterator i = getColumnRefs().iterator(); i.hasNext(); ) {
            ColumnRef columnRef = (ColumnRef)i.next();
            // columnRef's index are 1 based.
            inputValues[columnRef.getIndex() - 1] = getInputValue(updateDesc, columnRef, false);
        }
        final boolean getBeforeValue = updateDesc.isBeforeImageRequired();
        for (Iterator i = columnRefsForWhereClause.iterator(); i.hasNext(); ) {
            ColumnRef columnRef = (ColumnRef)i.next();
            inputValues[columnRef.getIndex() - 1] = getInputValue(updateDesc, columnRef, getBeforeValue);
        }
        return inputValues;
    }

    /**
     * Gets formatted sql text corrsponding to this statement object. The text
     * also contains values for input to the statement.
     * @param updateDesc the updateDesc.
     * @return formatted sql text corrsponding to this statement object.
     */
    public String getFormattedSQLText(UpdateObjectDescImpl updateDesc) {
        return formatSqlText(getText(), getInputValues(updateDesc));
    }

    /**
     * Gets input value corrsponding to given columnRef from given updateDesc
     * @param updateDesc updateDesc pointing to the input values
     * @param columnRef The columnRef. It always contains
     * the <code>LocalFieldDesc</code> for the field.
     * @param getBeforeValue If true, value returned is fetched from beforeImage
     * if false, the value returned is fetched from afterImage.
     * @return input value corrsponding to given columnRef from given updateDesc.
     */
    private static Object getInputValue(UpdateObjectDescImpl updateDesc,
                                        ColumnRef columnRef,
                                        boolean getBeforeValue) {
        Object value;
        LocalFieldDesc field = (LocalFieldDesc) columnRef.getValue();

        if (field.isVersion()) {
            // Bind the value from the after image for version fields,
            // as they're incremented internally after each flush.
            // Version fields must not be modified from "outside".
            value = updateDesc.getAfterValue(field);
        }  else {
            value = getBeforeValue ? updateDesc.getBeforeValue(field) :
                    updateDesc.getAfterValue(field);
        }

        return value;
    }

    public void addVersionColumn(ColumnElement versionColumn) {
        if (versionColumns == null) {
            versionColumns = new ArrayList();
        }
        versionColumns.add(versionColumn);
    }
}
