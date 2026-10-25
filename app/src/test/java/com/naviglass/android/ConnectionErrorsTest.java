package com.naviglass.android;

import org.junit.Test;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import static org.junit.Assert.*;

public class ConnectionErrorsTest {
    @Test public void distinguishDnsCertificateTimeoutAndReachability() {
        assertTrue(ConnectionErrors.message(new UnknownHostException("secret-url")).contains("URL spelling"));
        assertTrue(ConnectionErrors.message(new SSLHandshakeException("secret-url")).contains("certificate"));
        assertTrue(ConnectionErrors.message(new SocketTimeoutException("secret-url")).contains("timed out"));
        String unreachable=ConnectionErrors.message(new IOException("secret-url"));
        assertTrue(unreachable.contains("Tailscale-only"));
        assertFalse(unreachable.contains("secret-url"));
        assertFalse(unreachable.contains("No login was saved"));
    }
}
