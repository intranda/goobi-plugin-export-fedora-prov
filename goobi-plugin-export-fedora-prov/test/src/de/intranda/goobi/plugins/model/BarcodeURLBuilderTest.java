package de.intranda.goobi.plugins.model;

import org.junit.Assert;
import org.junit.Test;

public class BarcodeURLBuilderTest {

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
}