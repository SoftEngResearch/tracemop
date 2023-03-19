package com.runtimeverification.rvmonitor.java.rt.util;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.h2.tools.Csv;

public class TraceDBDuck implements TraceDB {
    private Connection connection;

    private String duckOptions = "";

    private String dbDir = "/tmp/tracedb";

    private String jdbcURL = "jdbc:duckdb:" + dbDir;
    private String jdbcUsername = "tdb";

    private String jdbcPassword = "";

    public TraceDBDuck() {
        updateSystemProperty();
        this.connection = getConnection();
    }

    public TraceDBDuck(String dbFilePath) {
        this.jdbcURL = "jdbc:duckdb:" + dbFilePath;
        this.dbDir = dbFilePath;
        updateSystemProperty();
        this.connection = getConnection();
    }

    public Connection getConnection() {
        if (connection != null) {
            return connection;
        }
        try {
            connection = DriverManager.getConnection(jdbcURL, jdbcUsername, jdbcPassword);
        } catch (SQLException e) {
            printSQLException(e);
        }
        return connection;
    }

    protected void printSQLException(SQLException ex) {
        for (Throwable e : ex) {
            if (e instanceof SQLException) {
                e.printStackTrace(System.err);
                System.err.println("SQLState: " + ((SQLException) e).getSQLState());
                System.err.println("Error Code: " + ((SQLException) e).getErrorCode());
                System.err.println("Message: " + e.getMessage());
                Throwable t = ex.getCause();
                while (t != null) {
                    System.out.println("Cause: " + t);
                    t = t.getCause();
                }
            }
        }
    }

    @Override
    public void put(String monitorID, String trace, int length) {
        final String INSERT_TRACE_SQL = "INSERT INTO traces (monitorID, trace, length ) VALUES (?, ?, ?);";
        try (PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_TRACE_SQL)) {
            preparedStatement.setString(1, monitorID);
            preparedStatement.setString(2, trace);
            preparedStatement.setInt(3, length);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            printSQLException(e);
        }
    }

    /**
     * Update system properties to include dbDir information so that JUnit Listeners that work on the DB have access.
     */
    private void updateSystemProperty() {
        System.setProperty("dbFilePath", this.dbDir);
        System.err.println("[TraceDBDuck] Set dbFilePath to: " + System.getProperty("dbFilePath"));
    }

    @Override
    public void update(String monitorID, String trace, int length) {
        final String UPDATE_TRACE_SQL = "update traces set trace = ?, length = ? where monitorID = ?;";
        try(PreparedStatement preparedStatement = getConnection().prepareStatement(UPDATE_TRACE_SQL)){
            preparedStatement.setString(1, trace);
            preparedStatement.setInt(2, length);
            preparedStatement.setString(3, monitorID);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }


    @Override
    public void createTable() {
        final String createTableSQL = "create table traces (monitorID  varchar(150) primary key, trace string, length int);";
        try (Statement statement = getConnection().createStatement()) {
            statement.execute(createTableSQL);
        } catch (SQLException e) {
            printSQLException(e);
        }
    }

    @Override
    public int uniqueTraces() {
        String query = "select count(distinct(trace)) from traces";
        int count = -1;
        try (Statement statement = getConnection().createStatement()) {
            ResultSet rs = statement.executeQuery(query);
            if (rs.next()) {
                count = rs.getInt(1);
            }
        } catch (SQLException e) {
            printSQLException(e);
        }
        return count;
    }

    @Override
    public int size() {
        String query = "select count(*) from traces";
        int count = -1;
        try(Statement statement =  getConnection().createStatement()){
            ResultSet rs = statement.executeQuery(query);
            if (rs.next()) {
                count = rs.getInt(1);
            }
        } catch (SQLException e) {
            printSQLException(e);
        }
        return count;
    }

    @Override
    public List<Integer> getTraceLengths() {
        String query = "select length from traces";
        List<Integer> lengths =  new ArrayList<>();
        try (Statement statement = getConnection().createStatement()) {
            ResultSet rs =  statement.executeQuery(query);
            while (rs.next()) {
                lengths.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            printSQLException(e);
        }
        return lengths;
    }

    @Override
    public Map<String, Integer> getTraceFrequencies() {
        String query = "select count(*), trace from traces group by trace";
        Map<String, Integer> traceFrequency = new HashMap<>();
        try(Statement statement = getConnection().createStatement()) {
            ResultSet rs = statement.executeQuery(query);
            while (rs.next()) {
                traceFrequency.put(rs.getString(2), rs.getInt(1));
            }
        } catch (SQLException e) {
            printSQLException(e);
        }
        return traceFrequency;
    }

    @Override
    public void dump() {
        String csvDir = this.dbDir + File.pathSeparator + "monitor-table.csv";
        String tableName = "traces";
        final String SELECT_QUERY = "select * from " + tableName;
        try(PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_QUERY)){
            ResultSet rs = preparedStatement.executeQuery();
            new Csv().write(csvDir, rs, null);
        } catch (SQLException e) {
            printSQLException(e);
        }
    }
}
