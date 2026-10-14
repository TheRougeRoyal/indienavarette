package com.veridion.shared;

import java.io.Serializable;
import java.time.LocalDateTime;

public record ActivityEvent(String clientId, String appName, String windowTitle, LocalDateTime timestamp) implements Serializable {}
