package com.optimix.model.dto;
public class GoogleTokenInfo {
    public String sub;      // Google user ID
    public String email;
    public String name;
    public String picture;
    public String aud;      // audience — must match our Google client ID
    public String email_verified;
}
