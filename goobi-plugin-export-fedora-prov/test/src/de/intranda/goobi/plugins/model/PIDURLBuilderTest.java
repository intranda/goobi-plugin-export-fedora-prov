package de.intranda.goobi.plugins.model;

import org.junit.Assert;
import org.junit.Test;

public class PIDURLBuilderTest {

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
}