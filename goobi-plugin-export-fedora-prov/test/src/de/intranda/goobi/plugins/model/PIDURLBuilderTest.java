package de.intranda.goobi.plugins.model;

import org.junit.Assert;
import org.junit.Test;

public class PIDURLBuilderTest {

    /**
     * @see PIDURLBuilder#getContainerUrl(int)
     * @verifies return correct value
     */
    @Test
    public void getContainerUrl_shouldReturnCorrectValue() throws Exception {
        IURLBuilder builder = new PIDURLBuilder("https://example.com", "DB0027DB-F83B-11E9-AE98-A392051B17E6");
        Assert.assertEquals("https://example.com/records/DB", builder.getContainerUrl(0));
        Assert.assertEquals("https://example.com/records/DB/00", builder.getContainerUrl(1));
        Assert.assertEquals("https://example.com/records/DB/00/27", builder.getContainerUrl(2));
        Assert.assertEquals("https://example.com/records/DB/00/27/DB", builder.getContainerUrl(3));
        Assert.assertEquals("https://example.com/records/DB/00/27/DB/-F83B-11E9-AE98-A392051B17E6", builder.getContainerUrl(4));
        Assert.assertEquals("https://example.com/records/DB/00/27/DB/-F83B-11E9-AE98-A392051B17E6/images", builder.getContainerUrl(5));
    }

    /**
     * @see PIDURLBuilder#getRecordContainerUrl()
     * @verifies return correct value
     */
    @Test
    public void getRecordContainerUrl_shouldReturnCorrectValue() throws Exception {
        IURLBuilder builder = new PIDURLBuilder("https://example.com", "DB0027DB-F83B-11E9-AE98-A392051B17E6");
        Assert.assertEquals("https://example.com/records/DB/00/27/DB/-F83B-11E9-AE98-A392051B17E6", builder.getRecordContainerUrl());
    }

    /**
     * @see PIDURLBuilder#getImageContainerUrl()
     * @verifies return correct value
     */
    @Test
    public void getImageContainerUrl_shouldReturnCorrectValue() throws Exception {
        IURLBuilder builder = new PIDURLBuilder("https://example.com", "DB0027DB-F83B-11E9-AE98-A392051B17E6");
        Assert.assertEquals("https://example.com/records/DB/00/27/DB/-F83B-11E9-AE98-A392051B17E6/images", builder.getImageContainerUrl());
    }

    /**
     * @see PIDURLBuilder#getImageContainerUrlPart()
     * @verifies return correct value
     */
    @Test
    public void getImageContainerUrlPart_shouldReturnCorrectValue() throws Exception {
        IURLBuilder builder = new PIDURLBuilder("https://example.com", "DB0027DB-F83B-11E9-AE98-A392051B17E6");
        Assert.assertEquals("/DB/00/27/DB/-F83B-11E9-AE98-A392051B17E6/images", builder.getImageContainerUrlPart());
    }
}