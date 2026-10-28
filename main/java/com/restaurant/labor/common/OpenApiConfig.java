package com.restaurant.labor.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Adds the caller headers to every operation so Swagger UI can be used to try the APIs. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI api() {
        return new OpenAPI().info(new Info()
                .title("Labor Service: Employee/Role API + Labor Management API")
                .version("v1")
                .description("Demo identities: see README. Default tenant: ten_pecan."));
    }

    @Bean
    public OperationCustomizer callerHeaders() {
        return (operation, handlerMethod) -> {
            operation.addParametersItem(new HeaderParameter().name(CallerInterceptor.TENANT_HEADER)
                    .required(true).schema(new StringSchema()._default("ten_pecan")));
            operation.addParametersItem(new HeaderParameter().name(CallerInterceptor.EMPLOYEE_HEADER)
                    .required(false).description("Caller employee id, e.g. emp_gm_tanya")
                    .schema(new StringSchema()));
            operation.addParametersItem(new HeaderParameter().name(CallerInterceptor.PIN_SESSION_HEADER)
                    .required(false).description("Alternative to X-Employee-Id: token from POST /v1/pos/pin_sessions")
                    .schema(new StringSchema()));
            return operation;
        };
    }
}
