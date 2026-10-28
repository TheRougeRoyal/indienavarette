package com.veridion.shared;

import java.io.Serializable;

/**
 * Immutable data-transfer object representing a single remote input command.
 *
 * <h2>Validation Rules (enforced by {@link #validate()})</h2>
 * <ul>
 *   <li>{@code type} must not be {@code null}.</li>
 *   <li>For {@code MOUSE_MOVE}, {@code MOUSE_PRESS}, {@code MOUSE_RELEASE}: coordinates
 *       ({@code x}, {@code y}) must be non-negative and within the primary screen bounds
 *       (checked via {@link java.awt.Toolkit#getDefaultToolkit()}).</li>
 *   <li>For {@code MOUSE_PRESS}, {@code MOUSE_RELEASE}: {@code button} must be 1–3
 *       (left / middle / right as defined by {@link java.awt.event.InputEvent}).</li>
 *   <li>For {@code MOUSE_SCROLL}: {@code amount} must be in the range [-10, +10].</li>
 *   <li>For {@code KEY_PRESS}, {@code KEY_RELEASE}: {@code keyCode} must be in the range
 *       [0, 255] (X11 key-code range).</li>
 * </ul>
 *
 * <h2>Replay Protection</h2>
 * {@code sequenceNumber} must be a monotonically-increasing value assigned by the caller.
 * The server rejects any command whose sequence number is not strictly greater than the last
 * accepted number for the current session, preventing replay attacks.
 *
 * @param type           the kind of input to simulate
 * @param x              screen x-coordinate (used by mouse commands)
 * @param y              screen y-coordinate (used by mouse commands)
 * @param button         mouse button index, 1-based (used by {@code MOUSE_PRESS/RELEASE})
 * @param keyCode        X11 key code in range [0, 255] (used by {@code KEY_PRESS/RELEASE})
 * @param amount         scroll wheel notches, range [-10, +10] (used by {@code MOUSE_SCROLL})
 * @param sequenceNumber monotonically-increasing counter for replay protection
 */
public record RemoteInputCommand(
        CommandType type,
        int x,
        int y,
        int button,
        int keyCode,
        int amount,
        long sequenceNumber
) implements Serializable {

    /** The type of desktop input to simulate. */
    public enum CommandType {
        MOUSE_MOVE,
        MOUSE_PRESS,
        MOUSE_RELEASE,
        MOUSE_SCROLL,
        KEY_PRESS,
        KEY_RELEASE
    }

    /**
     * Validates this command according to the rules documented on the class.
     *
     * @throws IllegalArgumentException if any field violates its constraint
     */
    public void validate() {
        if (type == null) throw new IllegalArgumentException("Command type required");

        switch (type) {
            case MOUSE_MOVE -> validateCoordinates();
            case MOUSE_PRESS, MOUSE_RELEASE -> {
                validateCoordinates();
                if (button < 1 || button > 3)
                    throw new IllegalArgumentException(
                            "Button must be 1-3 (got " + button + ")");
            }
            case MOUSE_SCROLL -> {
                if (amount < -10 || amount > 10)
                    throw new IllegalArgumentException(
                            "Scroll amount must be between -10 and 10 (got " + amount + ")");
            }
            case KEY_PRESS, KEY_RELEASE -> {
                if (keyCode < 0 || keyCode > 255)
                    throw new IllegalArgumentException(
                            "Key code must be 0-255 (got " + keyCode + ")");
            }
        }
    }

    /**
     * Validates that {@code x} and {@code y} are non-negative and fall within the bounds of the
     * primary display.
     *
     * @throws IllegalArgumentException if the coordinates are out of range
     */
    private void validateCoordinates() {
        if (x < 0 || y < 0)
            throw new IllegalArgumentException(
                    "Coordinates must be non-negative (got x=" + x + ", y=" + y + ")");
        java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        if (x >= screen.width || y >= screen.height)
            throw new IllegalArgumentException(
                    "Coordinates out of screen bounds (" + x + ", " + y
                    + "); screen is " + screen.width + "x" + screen.height);
    }
}
