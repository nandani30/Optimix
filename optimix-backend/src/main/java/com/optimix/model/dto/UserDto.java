package com.optimix.model.dto;
public class UserDto {
    public long    userId;
    public String  email;
    public String  fullName;
    public String  profilePictureUrl;
    public String  authMethod;
    public boolean emailVerified;

    public UserDto() {}
    public UserDto(long userId, String email, String fullName,
                   String profilePictureUrl, String authMethod, boolean emailVerified) {
        this.userId             = userId;
        this.email              = email;
        this.fullName           = fullName;
        this.profilePictureUrl  = profilePictureUrl;
        this.authMethod         = authMethod;
        this.emailVerified      = emailVerified;
    }
}
