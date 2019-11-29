package de.intranda.goobi.plugins;

import org.junit.Assert;
import org.junit.Test;

public class UtilsTest {
    
    /**
     * @see Utils#getCredentialsProvider(String,String)
     * @verifies return null if userName is empty
     */
    @Test
    public void getCredentialsProvider_shouldReturnNullIfUserNameIsEmpty() throws Exception {
        Assert.assertNull(Utils.getCredentialsProvider(null, "pw"));
        Assert.assertNull(Utils.getCredentialsProvider("", "pw"));
    }

    /**
     * @see Utils#getCredentialsProvider(String,String)
     * @verifies return null if password is empty
     */
    @Test
    public void getCredentialsProvider_shouldReturnNullIfPasswordIsEmpty() throws Exception {
        Assert.assertNull(Utils.getCredentialsProvider("user", null));
        Assert.assertNull(Utils.getCredentialsProvider("user", ""));
    }

    /**
     * @see Utils#getCredentialsProvider(String,String)
     * @verifies create provider correctly
     */
    @Test
    public void getCredentialsProvider_shouldCreateProviderCorrectly() throws Exception {
        Assert.assertNotNull(Utils.getCredentialsProvider("user", "pw"));
    }
}