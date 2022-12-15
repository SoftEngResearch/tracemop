package com.runtimeverification.rvmonitor.java.rt.util;

import javax.sql.rowset.serial.SerialClob;
import java.io.IOException;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

import org.h2.tools.Csv;

public class TraceDB {

    private Connection connection;
    private String jdbcURL = "jdbc:h2:/tmp/tracedb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    private String jdbcUsername = "tdb";
    private String jdbcPassword = "";

    public TraceDB() {
        this.connection = getConnection();
    }

    public TraceDB(String dbFilePath) {
        this.jdbcURL =  "jdbc:h2:" + dbFilePath + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
        this.connection = getConnection();
    }

    int id = 0;

    public void put(String monitorID, String trace, int length) {
        try {
            insert(monitorID, new SerialClob(trace.toCharArray()), length);
        } catch (SQLException e) {
            printSQLException(e);
        }
    }

    private void insert(String monitorID, Clob trace, int length) {
        // first check if trace is already in the trace table and get its traceID
        long traceID = getTraceID(trace);
        System.out.println("AAAA: " + traceID);
        // if trace is not in the trace table, add it and then obtain its traceID
        if (traceID == -1) {
            traceID = size() + 1;
            final String INSERT_TRACE_SQL = "INSERT INTO traces (traceID, trace, length ) VALUES (?, ?, ?);";
            try(PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_TRACE_SQL)) {
                preparedStatement.setLong(1, traceID);
                preparedStatement.setClob(2, trace);
                preparedStatement.setInt(3, length);
                preparedStatement.executeUpdate();
            } catch (SQLException e) {
                printSQLException(e);
            }
        }
        // finally add monitorID and traceID to the monitor table
        final String INSERT_MONITOR_SQL = "INSERT INTO monitors (monitorID, traceID) VALUES (?, ?);";
        try(PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_MONITOR_SQL)) {
            preparedStatement.setString(1, monitorID);
            preparedStatement.setLong(2, traceID);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            printSQLException(e);
        }
    }

    private long getTraceID(Clob trace) {
        long traceID = -1;
        final String TRACEID_QUERY = "select traceID from traces  where trace = ?;";
        try(PreparedStatement statement = getConnection().prepareStatement(TRACEID_QUERY)) {
            statement.setClob(1, trace);
            ResultSet rs = statement.executeQuery();
            long size = 0;
            if (rs != null) {
                rs.last();
                size = rs.getRow();
                if (size > 0) {
                    traceID = size;
                }
            }
        } catch (SQLException e) {
            printSQLException(e);
        }
        return traceID;
    }

    public void update(String monitorID, String trace, int length) {
        final String UPDATE_TRACE_SQL = "update traces set trace = ?, length = ? where monitorID = ?;";
        try(PreparedStatement preparedStatement = getConnection().prepareStatement(UPDATE_TRACE_SQL)){
            preparedStatement.setClob(1, new SerialClob(trace.toCharArray()));
            preparedStatement.setInt(2, length);
            preparedStatement.setString(3, monitorID);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public int size() {
        int count = -1;
        final String COUNT_QUERY = "select count(*) from traces";
        try(Statement statement =  getConnection().createStatement()){
            ResultSet rs = statement.executeQuery(COUNT_QUERY);
            if (rs.next()) {
                count = rs.getInt(1);
            }
        } catch (SQLException e) {
            printSQLException(e);
        }
        return count;
    }

    public int uniqueTraces() {
        int count = -1;
        final String TRACE_QUERY = "select count(distinct(trace)) from traces";
        try (Statement statement = getConnection().createStatement()) {
            ResultSet rs = statement.executeQuery(TRACE_QUERY);
            if (rs.next()) {
                count = rs.getInt(1);
            }
        } catch (SQLException e) {
            printSQLException(e);
        }
        return count;
    }

    public void dump(String csvDir, String tableName) {
        final String SELECT_QUERY = "select * from " + tableName;
        try(PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_QUERY)){
            ResultSet rs = preparedStatement.executeQuery();
            new Csv().write(csvDir, rs, null);
        } catch (SQLException e) {
            printSQLException(e);
        }
    }

    public void createTable() {
        final String createTraceTableSQL = "create table traces (traceID  bigint primary key, trace clob, length int);";
        final String createMonitorTableSQL = "create table monitors (monitorID  varchar(150) primary key, traceID bigint);";
        try (Statement statement = getConnection().createStatement()) {
            statement.execute(createTraceTableSQL);
            statement.execute(createMonitorTableSQL);
        } catch (SQLException e) {
            printSQLException(e);
        }
    }

    private Connection getConnection() {
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

    public List<Integer> getTraceLengths() {
        List<Integer> lengths =  new ArrayList<>();
        final String LENGTHS_QUERY = "select length from traces";
        try (Statement statement = getConnection().createStatement()) {
            ResultSet rs =  statement.executeQuery(LENGTHS_QUERY);
            while (rs.next()) {
                lengths.add(rs.getInt(1));
            }
        } catch (SQLException e) {
            printSQLException(e);
        }
        return lengths;
    }

    public Map<String, Integer> getTraceFrequencies() {
        Map<String, Integer> traceFrequency = new HashMap<>();
        final String FREQUENCY_QUERY = "select trace, count(*) from traces group by trace";
        try(Statement statement = getConnection().createStatement()) {
            ResultSet rs = statement.executeQuery(FREQUENCY_QUERY);
            while (rs.next()) {
                traceFrequency.put(rs.getString(1), rs.getInt(2));
            }
        } catch (SQLException e) {
            printSQLException(e);
        }
        return traceFrequency;
    }

    private void printSQLException(SQLException ex) {
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

    public static void main(String[] args) throws SQLException, IOException {
        TraceDB traceDB = new TraceDB();
        traceDB.createTable();
        System.out.println("Start: " + new Date().toString());
        traceDB.put("fy#"+1, "[a,b,b,c]", 4);
        traceDB.put("fy#"+2, "[a,b,b,c,d,e]", 6);
        traceDB.put("fy#"+3, "[a,b,b,c]", 4);
        for (int i = 4; i < 1000000; i++) {
            traceDB.put("fy#"+i, "[a,b,b,c,d,e]", 6);
        }
        System.out.println("Filled: " + new Date().toString());
        System.out.println(traceDB.uniqueTraces());
        System.out.println("Queried: " + new Date().toString());

        Clob trace1 = new SerialClob("[a,b,b,c]".toCharArray());

        char[] cbuf = new char[(int) trace1.length()];
        trace1.getCharacterStream().read(cbuf);
        String s =  new String(cbuf);

        Map<String, Integer> traceFrequency = new HashMap<>();
        final String FREQUENCY_QUERY = "select traceID, trace from traces  T where  trace = ?;";
        try(PreparedStatement statement = traceDB.getConnection().prepareStatement(FREQUENCY_QUERY)) {
            statement.setClob(1, trace1);
            ResultSet rs = statement.executeQuery();
//            ResultSet rs = statement.executeQuery(FREQUENCY_QUERY);
            while (rs.next()) {
                System.out.println("GGGG: " + rs.getString(1) +  ":::" + rs.getClob(2));
            }
        } catch (SQLException e) {
            traceDB.printSQLException(e);
        }
        System.out.println("Freq: " + traceFrequency);

        System.out.println("FFF: " + s);
        traceDB.dump("/tmp/trace-table.csv", "traces");
        traceDB.dump("/tmp/monitor-table.csv", "monitors");
    }
}
