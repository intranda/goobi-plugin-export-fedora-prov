package de.intranda.goobi.plugins.model;

import java.util.ArrayList;

public class PIDURLBuilder extends AbstractURLBuilder {

    /**
     * 
     * @param pid
     */
    public PIDURLBuilder(String transactionUrl, String pid) {
        if (transactionUrl == null) {
            throw new IllegalArgumentException("transactionUrl may not be null");
        }
        if (pid == null || pid.length() != 36) {
            throw new IllegalArgumentException("barcode must be 36 digits long");
        }

        this.transactionUrl = transactionUrl;

        parts = new ArrayList<>(6);
        parts.add(pid.substring(0, 2));
        parts.add(pid.substring(2, 4));
        parts.add(pid.substring(4, 6));
        parts.add(pid.substring(6, 8));
        parts.add(pid.substring(8, 36));
        parts.add("images");
    }

    /**
     * @should return correct value
     */
    @Override
    public String getRecordContainerUrl() {
        return getContainerUrl(4);
    }

    /**
     * @should return correct value
     */
    @Override
    public String getImageContainerUrl() {
        return getContainerUrl(5);
    }
}
