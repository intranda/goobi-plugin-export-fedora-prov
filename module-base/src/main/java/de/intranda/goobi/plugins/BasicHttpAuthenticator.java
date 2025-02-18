package de.intranda.goobi.plugins;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.xml.bind.DatatypeConverter;

/**
 * JAX-WS authenticator.
 */
public class BasicHttpAuthenticator implements ClientRequestFilter {

    private final String user;
    private final String password;

    public BasicHttpAuthenticator(String user, String password) {
            this.user = user;
            this.password = password;
        }

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        MultivaluedMap<String, Object> headers = requestContext.getHeaders();
        final String basicAuthentication = getBasicAuthentication();
        headers.add("Authorization", basicAuthentication);

    }

    private String getBasicAuthentication() {
        String token = this.user + ":" + this.password;
        try {
            return "BASIC " + DatatypeConverter.printBase64Binary(token.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException("Cannot encode with UTF-8", ex);
        }
    }
}
