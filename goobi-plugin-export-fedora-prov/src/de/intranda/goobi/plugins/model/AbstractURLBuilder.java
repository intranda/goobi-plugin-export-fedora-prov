package de.intranda.goobi.plugins.model;

import java.util.List;

public abstract class AbstractURLBuilder implements IURLBuilder {
    
    protected String transactionUrl;
    protected List<String> parts;
    
    /**
     * @should return correct value for barcodes
     * @should return correct value for PIDs
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
     * @should return correct value for barcodes
     * @should return correct value for PIDs
     */
    @Override
    public String getImageContainerUrlPart() {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append('/').append(part);
        }
        return sb.toString();
    }

    @Override
    public List<String> getParts() {
        return parts;
    }
}
