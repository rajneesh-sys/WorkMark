package com.workmark.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PasswordResetRequestResponse(String developmentToken) {}
