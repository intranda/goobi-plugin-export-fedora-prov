package de.intranda.goobi.plugins.model;

import org.junit.Assert;
import org.junit.Test;

public class BarcodeURLBuilderTest {

    /**
     * @see BarcodeURLBuilder#getContainerUrl(int)
     * @verifies return correct value
     */
    @Test
    public void getContainerUrl_shouldReturnCorrectValue() throws Exception {
        IURLBuilder builder = new BarcodeURLBuilder("https://example.com", "1234567890", "foo");
        Assert.assertEquals("https://example.com/records/1234", builder.getContainerUrl(0));
        Assert.assertEquals("https://example.com/records/1234/5678", builder.getContainerUrl(1));
        Assert.assertEquals("https://example.com/records/1234/5678/90", builder.getContainerUrl(2));
        Assert.assertEquals("https://example.com/records/1234/5678/90/images", builder.getContainerUrl(3));
    }
    

    /**
     * @see BarcodeURLBuilder#getRecordContainerUrl()
     * @verifies return correct value
     */
    @Test
    public void getRecordContainerUrl_shouldReturnCorrectValue() throws Exception {
        IURLBuilder builder = new BarcodeURLBuilder("https://example.com", "1234567890", "foo");
        Assert.assertEquals("https://example.com/records/1234/5678/90", builder.getRecordContainerUrl());
    }

    /**
     * @see BarcodeURLBuilder#getImageContainerUrl()
     * @verifies return correct value
     */
    @Test
    public void getImageContainerUrl_shouldReturnCorrectValue() throws Exception {
        IURLBuilder builder = new BarcodeURLBuilder("https://example.com", "1234567890", "foo");
        Assert.assertEquals("https://example.com/records/1234/5678/90/images", builder.getImageContainerUrl());
    }

    /**
     * @see BarcodeURLBuilder#getImageContainerUrlPart()
     * @verifies return correct value
     */
    @Test
    public void getImageContainerUrlPart_shouldReturnCorrectValue() throws Exception {
        IURLBuilder builder = new BarcodeURLBuilder("https://example.com", "1234567890", "foo");
        Assert.assertEquals("/1234/5678/90/images", builder.getImageContainerUrlPart());
    }
}