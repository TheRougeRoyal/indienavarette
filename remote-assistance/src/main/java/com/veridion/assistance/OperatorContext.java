package com.veridion.assistance;

import java.util.Objects;

public record OperatorContext(
    String operatorId,
    String operatorName,
    OperatorRole role,
    String ipAddress
) {
    public enum OperatorRole { ADMIN, VIEWER, OPERATOR }

    public OperatorContext {
        Objects.requireNonNull(operatorId);
        Objects.requireNonNull(operatorName);
        Objects.requireNonNull(role);
        Objects.requireNonNull(ipAddress);
    }
}
