package com.optimix.model.dto;
public class ConnectionTestResult {
    public boolean success;
    public String  message;
    public String  mysqlVersion;
    public int     tableCount;

    public ConnectionTestResult() {}
    public ConnectionTestResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }
}
