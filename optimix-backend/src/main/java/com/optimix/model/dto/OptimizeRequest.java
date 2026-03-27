package com.optimix.model.dto;
public class OptimizeRequest {
    public String  query;
    public Integer connectionId;
    // Inline connection fields (optional — for one-off queries without a saved profile)
    public String  host;
    public Integer port;
    public String  databaseName;
    public String  username;
    public String  password;
}
