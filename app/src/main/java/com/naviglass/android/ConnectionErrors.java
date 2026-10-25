package com.naviglass.android;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** Network guidance without exposing authenticated URLs or passwords. */
final class ConnectionErrors {
    static String message(IOException error) {
        if(error instanceof UnknownHostException)return "Server address could not be found. Check the URL spelling and phone DNS. For a Tailscale-only server, connect Tailscale on this phone.";
        if(error instanceof SSLException)return "Could not verify the server HTTPS certificate. Check the server address, certificate and phone date/time.";
        if(error instanceof SocketTimeoutException)return "Navidrome connection timed out. Check Wi-Fi/mobile data and server availability. Tailscale-only servers need Tailscale connected on this phone.";
        return "Cannot reach Navidrome from this phone. Check Wi-Fi/mobile data and the server address/port. Tailscale-only servers need Tailscale connected on this phone.";
    }
}
