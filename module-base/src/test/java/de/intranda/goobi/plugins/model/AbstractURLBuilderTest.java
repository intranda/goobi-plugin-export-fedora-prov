package de.intranda.goobi.plugins.model;

import org.junit.Assert;
import org.junit.Test;

public class AbstractURLBuilderTest {

    /**
     * @see AbstractURLBuilder#getContainerUrl(int)
     * @verifies return correct value for barcodes
     */
    @Test
    public void getContainerUrl_shouldReturnCorrectValueForBarcodes() throws Exception {
        IURLBuilder builder = new BarcodeURLBuilder("https://example.com", "1234567890", "foo");
        Assert.assertEquals("https://example.com/records/1234", builder.getContainerUrl(0));
        Assert.assertEquals("https://example.com/records/1234/5678", builder.getContainerUrl(1));
        Assert.assertEquals("https://example.com/records/1234/5678/90", builder.getContainerUrl(2));
        Assert.assertEquals("https://example.com/records/1234/5678/90/images", builder.getContainerUrl(3));
    }

    /**
     * @see AbstractURLBuilder#getContainerUrl(int)
     * @verifies return correct value for PIDs
     */
    @Test
    public void getContainerUrl_shouldReturnCorrectValueForPIDs() throws Exception {
        IURLBuilder builder = new PIDURLBuilder("https://example.com", "DB0027DB-F83B-11E9-AE98-A392051B17E6");
        Assert.assertEquals("https://example.com/records/DB", builder.getContainerUrl(0));
        Assert.assertEquals("https://example.com/records/DB/00", builder.getContainerUrl(1));
        Assert.assertEquals("https://example.com/records/DB/00/27", builder.getContainerUrl(2));
        Assert.assertEquals("https://example.com/records/DB/00/27/DB", builder.getContainerUrl(3));
        Assert.assertEquals("https://example.com/records/DB/00/27/DB/-F83B-11E9-AE98-A392051B17E6", builder.getContainerUrl(4));
        Assert.assertEquals("https://example.com/records/DB/00/27/DB/-F83B-11E9-AE98-A392051B17E6/images", builder.getContainerUrl(5));
    }

    /**
     * @see AbstractURLBuilder#getImageContainerUrlPart()
     * @verifies return correct value for barcodes
     */
    @Test
    public void getImageContainerUrlPart_shouldReturnCorrectValueForBarcodes() throws Exception {
        IURLBuilder builder = new BarcodeURLBuilder("https://example.com", "1234567890", "foo");
        Assert.assertEquals("/1234/5678/90/images", builder.getImageContainerUrlPart());
    }

    /**
     * @see AbstractURLBuilder#getImageContainerUrlPart()
     * @verifies return correct value for PIDs
     */
    @Test
    public void getImageContainerUrlPart_shouldReturnCorrectValueForPIDs() throws Exception {
        IURLBuilder builder = new PIDURLBuilder("https://example.com", "DB0027DB-F83B-11E9-AE98-A392051B17E6");
        Assert.assertEquals("/DB/00/27/DB/-F83B-11E9-AE98-A392051B17E6/images", builder.getImageContainerUrlPart());
    }
}