package com.kratisai.controlplane.service;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Unchecked failure of a WebSocket write. Propagates to the framework or Reactor {@code onError};
 * there is no best-effort send path.
 */
public final class WebSocketSendException extends UncheckedIOException {

    public WebSocketSendException(String message, IOException cause) {
        super(message, cause);
    }
}
