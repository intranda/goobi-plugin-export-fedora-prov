package de.intranda.goobi.plugins.model;

import java.util.ArrayList;
import java.util.List;

public class PIDURLBuilder implements IURLBuilder {

    private String transactionUrl;
    private List<String> parts = new ArrayList<>(4);

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

        parts.add(pid.substring(0, 2));
        parts.add(pid.substring(2, 4));
        parts.add(pid.substring(4, 6));
        parts.add(pid.substring(6, 8));
        parts.add(pid.substring(8, 36));
        parts.add("images");
    }

    @Override
    public List<String> getParts() {
        return parts;
    }

    /**
     * @should return correct value
     */
    @Override
    public String getContainerUrl(int level) {
        StringBuilder sb = new StringBuilder(transactionUrl + "/records");
        for (int i = 0; i <= level; ++i) {
            if (i >= parts.size()) {
                break;
            }
            sb.append('/').append(parts.get(i));
        }
        return sb.toString();
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

    /**
     * @should return correct value
     */
    @Override
    public String getImageContainerUrlPart() {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append('/').append(part);
        }
        return sb.toString();
    }

}
