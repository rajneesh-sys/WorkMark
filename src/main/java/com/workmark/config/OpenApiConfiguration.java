package com.workmark.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "WorkMark Leave and Attendance API",
        version = "v1",
        description = "Tenant-isolated company, employee, leave, and attendance management APIs."))
public class OpenApiConfiguration {}
