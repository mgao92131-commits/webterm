package com.webterm.transport.api;

public interface TransportFactory {
    /** Create the mux transport, never null. */
    MuxTransport create(String wsUrl, String cookie, String subprotocol);
}
