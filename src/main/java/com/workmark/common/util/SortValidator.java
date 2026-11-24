package com.workmark.common.util;

import com.workmark.common.exception.ApiException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.util.Set;

public final class SortValidator {
    private SortValidator() {}

    public static void validate(Sort sort, Set<String> allowedFields, String resourceName) {
        for (Sort.Order order : sort) {
            if (!allowedFields.contains(order.getProperty())) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Invalid " + resourceName + " sort field");
            }
        }
    }
}
