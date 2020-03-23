package de.intranda.goobi.plugins.model;

import java.util.ArrayList;

public class BarcodeURLBuilder extends AbstractURLBuilder {

    /**
     * @param fedoraRestUrl
     * @param barcode
     * @param unitItemCode
     */
    public BarcodeURLBuilder(String fedoraRestUrl, String barcode, String unitItemCode) {
        if (fedoraRestUrl == null) {
            throw new IllegalArgumentException("fedoraRestUrl may not be null");
        }
        if (barcode == null || barcode.length() < 10) {
            throw new IllegalArgumentException("barcode must be 10 digits long");
        }
        if (unitItemCode == null) {
            throw new IllegalArgumentException("unitItemCode may not be null");
        }

        this.fedoraRestUrl = fedoraRestUrl;

        parts = new ArrayList<>(4);
        parts.add(barcode.substring(0, 4));
        parts.add(barcode.substring(4, 8));
        parts.add(barcode.substring(8, 10));
        parts.add("images");
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
    
    // create url parts
    //        String barcodePart1 = properties.get(PROP_NAME_BARCODE).substring(0, 4);
    //        String barcodePart2 = properties.get(PROP_NAME_BARCODE).substring(4, 8);
    //        String barcodePart3 = properties.get(PROP_NAME_BARCODE).substring(8, 10);
    //        String barcodePart4 = "images";
    //        String barcodeUrl1 = transactionUrl + "/records/" + barcodePart1;
    //        String barcodeUrl2 = barcodeUrl1 + "/" + barcodePart2;
    //        String barcodeUrl3 = barcodeUrl2 + "/" + barcodePart3;
    //        String barcodeUrl4 = barcodeUrl3 + "/" + barcodePart4;
}
