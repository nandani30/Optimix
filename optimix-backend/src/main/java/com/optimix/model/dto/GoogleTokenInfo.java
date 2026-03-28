package com.optimix.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// 🔴 CRITICAL FIX: Tells Jackson to safely ignore any extra fields Google sends (like 'iss', 'azp')
@JsonIgnoreProperties(ignoreUnknown = true)
public class GoogleTokenInfo {
    
    public String sub;
    public String email;
    public boolean email_verified;
    public String name;
    public String picture;
    public String aud;

    // You can add standard getters and setters if you were using them, 
    // but public fields work perfectly for Jackson data transfer objects.
}