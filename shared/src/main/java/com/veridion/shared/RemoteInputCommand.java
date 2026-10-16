package com.veridion.shared;

import java.io.Serializable;

public record RemoteInputCommand(
    CommandType type,
    int x,
    int y,
    int button,
    int keyCode,
    int amount,
    long sequenceNumber
) implements Serializable {
    public enum CommandType {
        MOUSE_MOVE,
        MOUSE_PRESS,
        MOUSE_RELEASE,
        MOUSE_SCROLL,
        KEY_PRESS,
        KEY_RELEASE
    }

    public void validate() {
        if (type == null) throw new IllegalArgumentException("Command type required");
        if ((type == CommandType.MOUSE_MOVE || type == CommandType.MOUSE_PRESS || type == CommandType.MOUSE_RELEASE) 
            && (x < 0 || y < 0)) {
            throw new IllegalArgumentException("Coordinates must be non-negative");
        }
    }
}
