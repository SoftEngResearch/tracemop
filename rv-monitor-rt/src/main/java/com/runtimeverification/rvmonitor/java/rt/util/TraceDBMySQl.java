package com.runtimeverification.rvmonitor.java.rt.util;

import org.h2.tools.Csv;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.sql.DriverManager.getConnection;

public class TraceDBMySQl implements TraceDB{

    private Connection connection;

    private String jdbcURL = "jdbc:mysql://localhost:3306/mop?serverTimezone=UTC";
    private String jdbcUsername = "mop";

    private String jdbcPassword = "javamop";

    private String tableName;

    public TraceDBMySQl() {
        this("defaultTableName");
    }

    public TraceDBMySQl(String tableName) {
        this.connection = getConnection();
        this.tableName  = tableName;
    }

    public Connection getConnection() {
        if (connection != null) {
            return connection;
        }
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection(jdbcURL, jdbcUsername, jdbcPassword);
        } catch (SQLException e) {
            printSQLException(e);
        } catch (ClassNotFoundException ex) {
            throw new RuntimeException(ex);
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
        final String INSERT_TRACE_SQL = "INSERT INTO " + tableName + "(monitorID, trace, length ) VALUES (?, ?, ?);";
        try (PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_TRACE_SQL)) {
            preparedStatement.setString(1, monitorID);
            preparedStatement.setString(2, trace);
            preparedStatement.setInt(3, length);
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            printSQLException(e);
        }
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
        final String createTableSQL = "create table " + tableName + " (monitorID  varchar(400) not null , trace LONGTEXT, length int, primary key (monitorID));";
        try (Statement statement = getConnection().createStatement()) {
            statement.execute(createTableSQL);
        } catch (SQLException e) {
            printSQLException(e);
        }
    }

    @Override
    public int uniqueTraces() {
        String query = "select count(distinct(trace)) from " + tableName + ";";
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
        String query = "select count(*) from " + tableName + ";";
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
        String query = "select length from " + tableName + ";";
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
        String query = "select count(*), trace from " + tableName + " group by trace";
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
//        String tableName = "traces";
        final String SELECT_QUERY = "select * from " + tableName + " into outfile '/var/lib/mysql-files/" + tableName + ".csv' fields terminated by ',';";
        try(PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_QUERY)){
            ResultSet rs = preparedStatement.executeQuery();
        } catch (SQLException e) {
            printSQLException(e);
        }
    }

    public static void main(String[] args) {
        TraceDBMySQl traceDB = new TraceDBMySQl("dummy");
//        traceDB.createTable();
//        traceDB.put("Spec1#6", "[a,b,b,c]", 4);
//        traceDB.put("Spec1#7", "[a,b,b,c,c,c,c]", 7);


//        traceDB.update("Spec1#1", "[a,b,b,c,d]", 5);
        System.out.println("UNIQ: " + traceDB.uniqueTraces());
        System.out.println("size: " + traceDB.size());
        System.out.println("TL: " + traceDB.getTraceLengths());
        System.out.println("TF: " + traceDB.getTraceFrequencies());
        traceDB.dump();
    }
}
