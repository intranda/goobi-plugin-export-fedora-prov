package de.intranda.goobi.plugins;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;

public class Utils {

    /**
     * Returns an Apache HTTP credentials provider for the given user name and password.
     * 
     * @param userName
     * @param password
     * @return CredentialsProvider; null if credentials incomplete
     * @should return null if userName is empty
     * @should return null if password is empty
     * @should create provider correctly
     */
    public static CredentialsProvider getCredentialsProvider(String userName, String password) {
        if (StringUtils.isEmpty(userName) || StringUtils.isEmpty(password)) {
            return null;
        }

        CredentialsProvider provider = new BasicCredentialsProvider();
        provider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(userName, password));

        return provider;
    }
}
