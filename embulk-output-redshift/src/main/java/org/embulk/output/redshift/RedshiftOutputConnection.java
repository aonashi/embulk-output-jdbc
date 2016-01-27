package org.embulk.output.redshift;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.slf4j.Logger;
import org.embulk.spi.Exec;
import org.embulk.output.jdbc.JdbcOutputConnection;
import org.embulk.output.jdbc.JdbcColumn;
import org.embulk.output.jdbc.JdbcSchema;

public class RedshiftOutputConnection
        extends JdbcOutputConnection
{
    private final Logger logger = Exec.getLogger(RedshiftOutputConnection.class);

    public RedshiftOutputConnection(Connection connection, String schemaName, boolean autoCommit)
            throws SQLException
    {
        super(connection, schemaName);
        connection.setAutoCommit(autoCommit);
    }

    // Redshift does not support DROP TABLE IF EXISTS.
    // Here runs DROP TABLE and ignores errors.
    @Override
    public void dropTableIfExists(String tableName) throws SQLException
    {
        Statement stmt = connection.createStatement();
        try {
            String sql = String.format("DROP TABLE IF EXISTS %s", quoteIdentifierString(tableName));
            executeUpdate(stmt, sql);
            commitIfNecessary(connection);
        } catch (SQLException ex) {
            // ignore errors.
            // TODO here should ignore only 'table "XXX" does not exist' errors.
            SQLException ignored = safeRollback(connection, ex);
        } finally {
            stmt.close();
        }
    }

    // Redshift does not support DROP TABLE IF EXISTS.
    // Dropping part runs DROP TABLE and ignores errors.
    @Override
    public void replaceTable(String fromTable, JdbcSchema schema, String toTable) throws SQLException
    {
        Statement stmt = connection.createStatement();
        try {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("DROP TABLE ");
                quoteIdentifierString(sb, toTable);
                String sql = sb.toString();
                executeUpdate(stmt, sql);
            } catch (SQLException ex) {
                // ignore errors.
                // TODO here should ignore only 'table "XXX" does not exist' errors.
                // rollback or comimt is required to recover failed transaction
                SQLException ignored = safeRollback(connection, ex);
            }

            {
                StringBuilder sb = new StringBuilder();
                sb.append("ALTER TABLE ");
                quoteIdentifierString(sb, fromTable);
                sb.append(" RENAME TO ");
                quoteIdentifierString(sb, toTable);
                String sql = sb.toString();
                executeUpdate(stmt, sql);
            }

            commitIfNecessary(connection);
        } catch (SQLException ex) {
            throw safeRollback(connection, ex);
        } finally {
            stmt.close();
        }
    }

    @Override
    protected String buildColumnTypeName(JdbcColumn c)
    {
        // Redshift does not support TEXT type.
        switch(c.getSimpleTypeName()) {
        case "CLOB":
            return "VARCHAR(65535)";
        case "TEXT":
            return "VARCHAR(65535)";
        case "BLOB":
            return "BYTEA";
        default:
            return super.buildColumnTypeName(c);
        }
    }

    public String buildCopySQLBeforeFrom(String tableName, JdbcSchema tableSchema)
    {
        StringBuilder sb = new StringBuilder();

        sb.append("COPY ");
        quoteIdentifierString(sb, tableName);
        sb.append(" (");
        for(int i=0; i < tableSchema.getCount(); i++) {
            if(i != 0) { sb.append(", "); }
            quoteIdentifierString(sb, tableSchema.getColumnName(i));
        }
        sb.append(")");

        return sb.toString();
    }

    public void runCopy(String sql) throws SQLException
    {
        Statement stmt = connection.createStatement();
        try {
            stmt.executeUpdate(sql);
        } finally {
            stmt.close();
        }
    }

    @Override
    protected String buildCollectMergeSql(List<String> fromTables, JdbcSchema schema, String toTable, List<String> mergeKeys) throws SQLException
    {
        StringBuilder sb = new StringBuilder();

        for (String fromTable : fromTables) {
            // update
            sb.append("UPDATE ");
            quoteIdentifierString(sb, toTable);
            sb.append(" SET ");
            for (int i = 0; i < schema.getCount(); i++) {
                if (i != 0) { sb.append(", "); }
                quoteIdentifierString(sb, schema.getColumnName(i));
                sb.append(" = ");
                quoteIdentifierString(sb, fromTable);
                sb.append(".");
                quoteIdentifierString(sb, schema.getColumnName(i));
            }
            sb.append(" FROM ");
            quoteIdentifierString(sb, fromTable);
            sb.append(" WHERE ");
            for (int i = 0; i < mergeKeys.size(); i++) {
                if (i != 0) { sb.append(" AND "); }
                quoteIdentifierString(sb, toTable);
                sb.append(".");
                quoteIdentifierString(sb, mergeKeys.get(i));
                sb.append(" = ");
                quoteIdentifierString(sb, fromTable);
                sb.append(".");
                quoteIdentifierString(sb, mergeKeys.get(i));
            }
            sb.append(";");

            // insert
            sb.append("INSERT INTO ");
            quoteIdentifierString(sb, toTable);
            sb.append(" SELECT ");
            quoteIdentifierString(sb, fromTable);
            sb.append(".* FROM ");
            quoteIdentifierString(sb, fromTable);
            sb.append(" LEFT JOIN ");
            quoteIdentifierString(sb, toTable);
            sb.append(" ON ");
            for (int i = 0; i < mergeKeys.size(); i++) {
                if (i != 0) { sb.append(" AND "); }
                quoteIdentifierString(sb, fromTable);
                sb.append(".");
                quoteIdentifierString(sb, mergeKeys.get(i));
                sb.append(" = ");
                quoteIdentifierString(sb, toTable);
                sb.append(".");
                quoteIdentifierString(sb, mergeKeys.get(i));
            }
            sb.append(" WHERE ");
            quoteIdentifierString(sb, toTable);
            sb.append(".");
            quoteIdentifierString(sb, mergeKeys.get(0));
            sb.append(" IS NULL;");
        }
        return sb.toString();
    }
}
