package de.intranda.goobi.plugins.model;

import java.util.ArrayList;
import java.util.List;

public class BarcodeURLBuilder implements IURLBuilder {

    private String transactionUrl;
    private List<String> parts = new ArrayList<>(4);

    /**
     * @param transactionUrl
     * @param barcode
     * @param unitItemCode
     */
    public BarcodeURLBuilder(String transactionUrl, String barcode, String unitItemCode) {
        if (transactionUrl == null) {
            throw new IllegalArgumentException("transactionUrl may not be null");
        }
        if (barcode == null || barcode.length() < 10) {
            throw new IllegalArgumentException("barcode must be 10 digits long");
        }
        if (unitItemCode == null) {
            throw new IllegalArgumentException("unitItemCode may not be null");
        }

        this.transactionUrl = transactionUrl;

        parts.add(barcode.substring(0, 4));
        parts.add(barcode.substring(4, 8));
        parts.add(barcode.substring(8, 10));
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
        return getContainerUrl(2);
    }

    /**
     * @should return correct value
     */
    @Override
    public String getImageContainerUrl() {
        return getContainerUrl(3);
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
