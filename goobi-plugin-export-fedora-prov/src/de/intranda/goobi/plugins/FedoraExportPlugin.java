package de.intranda.goobi.plugins;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URLConnection;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.entity.EntityBuilder;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.log4j.Logger;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import de.unigoettingen.sub.commons.contentlib.exceptions.ImageManagerException;
import de.unigoettingen.sub.commons.contentlib.imagelib.ImageManager;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

@PluginImplementation
public class FedoraExportPlugin implements IExportPlugin, IPlugin {

    private static final Logger log = Logger.getLogger(FedoraExportPlugin.class);

    private static final String PLUGIN_NAME = "prov_export_fedora";

    private static final String PROP_NAME_BARCODE = "barcode";
    private static final String PROP_NAME_UNIT_ITEM_CODE = "unit_Item_code";
    private static final String PROP_NAME_FULL_PARTIAL = "full_partial";
    private static final String PROP_NAME_AVAILABLE = "available";

    private static String fedoraUrl;

    public static DateTimeFormatter formatter = DateTimeFormat.forPattern("yyyy-MM-dd_HH.mm.ss.SSS");

    @Override
    public PluginType getType() {
        return PluginType.Export;
    }

    @Override
    public String getTitle() {
        return PLUGIN_NAME;
    }

    @Override
    public void setExportFulltext(boolean exportFulltext) {
    }

    @Override
    public void setExportImages(boolean exportImages) {
    }

    @Override
    public boolean startExport(Process process) throws IOException, InterruptedException, DocStructHasNoTypeException, PreferencesException,
            WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException, SwapException, DAOException,
            TypeNotAllowedForParentException {
        String path = new VariableReplacer(null, null, process, null).replace(process.getProjekt().getDmsImportRootPath());
        return startExport(process, path);
    }

    @Override
    public boolean startExport(Process process, String destination) throws IOException, InterruptedException, DocStructHasNoTypeException,
            PreferencesException, WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException,
            SwapException, DAOException, TypeNotAllowedForParentException {
        return ingestData(process, destination);
    }

    /**
     * @param folder
     * @param process
     * @param destination
     * @param useVersioning If true, new versions of the existing resource will be added; if false, the resource will be deleted and created anew (all
     *            previous versions will be deleted).
     */
    private boolean ingestData(Process process, String destination) {
        boolean success = false;

        // get workflow name from properties
        String workflowName = null;
        for (Processproperty pp : process.getEigenschaften()) {
            if (pp.getTitel().equals("Template")) {
                workflowName = pp.getWert();
            }
        }

        // get the right configuration from the config file
        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig(PLUGIN_NAME);
        xmlConfig.setExpressionEngine(new XPathExpressionEngine());
        xmlConfig.setReloadingStrategy(new FileChangedReloadingStrategy());
        SubnodeConfiguration myconfig = null;
        try {
            myconfig = xmlConfig.configurationAt("//config[./workflow = '" + workflowName + "']");
        } catch (IllegalArgumentException e) {
            myconfig = xmlConfig.configurationAt("//config[./workflow = '*']");
        }

        // now read from the right config area
        fedoraUrl = myconfig.getString("fedoraUrl", "http://localhost:8080/fedora/rest");
        String userName = myconfig.getString("userName");
        String password = myconfig.getString("password");

        String externalLinkContent = myconfig.getString("externalLinkContent");
        String fullPartialContent = myconfig.getString("fullPartialContent");
        String availableMetadataQuery = myconfig.getString("availableMetadataQuery");
        String filesContainerMetadataQuery = myconfig.getString("filesContainerMetadataQuery");
        String imageFileMetadataQuery = myconfig.getString("imageFileMetadataQuery");
        boolean useVersioning = myconfig.getBoolean("useVersioning", true);
        boolean ingestMaster = myconfig.getBoolean("ingestMaster", true);
        boolean ingestMedia = myconfig.getBoolean("ingestMedia", true);
        boolean ingestJp2 = myconfig.getBoolean("ingestJp2", true);
        boolean ingestPdf = myconfig.getBoolean("ingestPdf", true);

        Map<String, String> properties = new HashMap<>(4);
        // get the barcode from the property list
        for (Processproperty prop : process.getEigenschaftenList()) {
            if (prop.getTitel() == null) {
                continue;
            }
            properties.put(prop.getTitel(), prop.getWert());
        }

        if (properties.get(PROP_NAME_BARCODE) == null) {
            Helper.addMessageToProcessLog(process.getId(), LogType.ERROR,
                    "The ingest into Fedora was not successful as no barcode could be found in the properties.");
            Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                    "The ingest into Fedora was not successful as no barcode could be found in the properties.");
            return false;
        }

        if (properties.get(PROP_NAME_UNIT_ITEM_CODE) == null || properties.get(PROP_NAME_UNIT_ITEM_CODE).length() == 0) {
            Helper.addMessageToProcessLog(process.getId(), LogType.ERROR,
                    "The ingest into Fedora was not successful as no type could be found in the properties.");
            Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                    "The ingest into Fedora was not successful as no type could be found in the properties.");
            return false;
        }

        properties.put(PROP_NAME_UNIT_ITEM_CODE, properties.get(PROP_NAME_UNIT_ITEM_CODE).toUpperCase().substring(0, 1)
                + properties.get(PROP_NAME_UNIT_ITEM_CODE).toLowerCase().substring(1));

        // JAX-WS HTTP client
        Client client = ClientBuilder.newClient();
        if (StringUtils.isNotEmpty(userName) && StringUtils.isNotEmpty(password)) {
            log.info("Using configured HTTP cretentials.");
            Helper.addMessageToProcessLog(process.getId(), LogType.INFO, "Using configured HTTP cretentials.");
            client.register(new BasicHttpAuthenticator(userName, password));
        }
        WebTarget fedoraBase = client.target(fedoraUrl);

        // Create a new transaction in Fedora (POST operation)
        Response transactionResponse = null;
        try {
            transactionResponse = fedoraBase.path("fcr:tx").request().post(null);
            if (transactionResponse == null || transactionResponse.getStatus() >= 400) {
                Helper.addMessageToProcessLog(process.getId(), LogType.ERROR,
                        transactionResponse.getStatusInfo().getReasonPhrase() + " - " + fedoraUrl);
                Helper.setFehlerMeldung(null, String.valueOf(transactionResponse.getStatus()),
                        transactionResponse.getStatusInfo().getReasonPhrase() + " - " + fedoraUrl);
                return false;
            }
        } catch (Exception e) {
            Helper.addMessageToProcessLog(process.getId(), LogType.ERROR, "The ingest into Fedora was not successful: " + e.getMessage());
            Helper.setFehlerMeldung(null, process.getTitel() + ": ", "The ingest into Fedora was not successful: " + e.getMessage());
            return false;
        }

        // The base URL to work with (contains the transaction ID)
        String transactionUrl = transactionResponse.getHeaderString("location");
        WebTarget ingestLocation = client.target(transactionUrl);

        // create url parts
        String barcodePart1 = properties.get(PROP_NAME_BARCODE).substring(0, 4);
        String barcodePart2 = properties.get(PROP_NAME_BARCODE).substring(4, 8);
        String barcodePart3 = properties.get(PROP_NAME_BARCODE).substring(8, 10);
        String barcodePart4 = "images";
        String barcodeUrl1 = transactionUrl + "/records/" + barcodePart1;
        String barcodeUrl2 = barcodeUrl1 + "/" + barcodePart2;
        String barcodeUrl3 = barcodeUrl2 + "/" + barcodePart3;
        String barcodeUrl4 = barcodeUrl3 + "/" + barcodePart4;

        // Transaction that will be rolled back if anything fails
        try {
            // If not using versioning remove resource prior to ingesting to speed things up
            if (!useVersioning) {
                WebTarget recordContainer =
                        ingestLocation.path("records/" + barcodePart1 + "/" + barcodePart2 + "/" + barcodePart3 + "/" + barcodePart4);
                if (!deleteResource(process, recordContainer)) {
                    Helper.addMessageToProcessLog(process.getId(), LogType.ERROR,
                            "The ingest into Fedora was not successful as the previous container could not be deleted for "
                                    + recordContainer.getUri().toString());
                    Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                            "The ingest into Fedora was not successful as the previous container could not be deleted for "
                                    + recordContainer.getUri().toString());
                    return false;
                }
            }

            // Create the required container hierarchy for the process identifier
            boolean containerCreated = createContainer(barcodeUrl1, userName, password);
            if (!containerCreated) {
                Helper.addMessageToProcessLog(process.getId(), LogType.ERROR,
                        "The ingest into Fedora was not successful (container creation for " + barcodeUrl1 + ")");
                Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                        "The ingest into Fedora was not successful as the container could not be created for " + barcodeUrl1);
                return false;
            }
            containerCreated = createContainer(barcodeUrl2, userName, password);
            if (!containerCreated) {
                Helper.addMessageToProcessLog(process.getId(), LogType.ERROR,
                        "The ingest into Fedora was not successful (container creation for " + barcodeUrl2 + ")");
                Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                        "The ingest into Fedora was not successful as the container could not be created for " + barcodeUrl2);
                return false;
            }
            containerCreated = createContainer(barcodeUrl3, userName, password);
            if (!containerCreated) {
                Helper.addMessageToProcessLog(process.getId(), LogType.ERROR,
                        "The ingest into Fedora was not successful (container creation for " + barcodeUrl3 + ")");
                Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                        "The ingest into Fedora was not successful as the container could not be created for " + barcodeUrl3);
                return false;
            }

            containerCreated = createContainer(barcodeUrl4, userName, password);
            if (!containerCreated) {
                Helper.addMessageToProcessLog(process.getId(), LogType.ERROR,
                        "The ingest into Fedora was not successful (container creation for " + barcodeUrl4 + ")");
                Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                        "The ingest into Fedora was not successful as the container could not be created for " + barcodeUrl4);
                return false;
            }

            // Name for the new version, if using versioning
            String version = useVersioning ? "goobi-export." + formatter.print(System.currentTimeMillis()) : null;

            WebTarget recordUrl = ingestLocation.path("records").path(barcodePart1 + "/" + barcodePart2 + "/" + barcodePart3 + "/" + barcodePart4); // URL for the record folder
            log.debug("record url: " + recordUrl.getUri().toString());

            // now ingest all content files by order
            // first collect all content files from all folders

            NIOFileUtils nfu = new NIOFileUtils();
            List<Path> masterFileList = nfu.listFiles(process.getImagesOrigDirectory(false), ImageAndPdfFilter);
            List<Path> mediaFileList = nfu.listFiles(process.getImagesTifDirectory(false), ImageAndPdfFilter);
            List<Path> pdfFileList = nfu.listFiles(process.getImagesDirectory() + process.getTitel() + "_pdf", ImageAndPdfFilter);
            List<Path> jp2FileList = nfu.listFiles(process.getImagesDirectory() + process.getTitel() + "_jp2", ImageAndPdfFilter);

            int max = masterFileList.size();
            if (mediaFileList.size() > masterFileList.size()) {
                max = mediaFileList.size();
            }
            if (pdfFileList.size() > masterFileList.size()) {
                max = pdfFileList.size();
            }
            if (jp2FileList.size() > masterFileList.size()) {
                max = jp2FileList.size();
            }
            log.debug("Found " + max + " files");

            for (int i = 0; i < max; i++) {
                Path masterFile = null;
                Path mediaFile = null;
                Path pdfFile = null;
                Path jp2File = null;

                try {
                    if (masterFileList.size() > i) {
                        masterFile = masterFileList.get(i);
                    }
                    if (mediaFileList.size() > i) {
                        mediaFile = mediaFileList.get(i);
                    }
                    if (jp2FileList.size() > i) {
                        jp2File = jp2FileList.get(i);
                    }
                    if (pdfFileList.size() > i) {
                        pdfFile = pdfFileList.get(i);
                    }
                } catch (IndexOutOfBoundsException ioc) {
                    // nothing happens here
                }

                // create container for this image
                String iValue = String.valueOf(i + 1);
                String imageNumberUrl = barcodeUrl4 + "/" + iValue;
                String filesUrl = imageNumberUrl + "/files";
                containerCreated = createContainer(imageNumberUrl, userName, password);
                if (!containerCreated) {
                    Helper.addMessageToProcessLog(process.getId(), LogType.ERROR,
                            "The ingest into Fedora was not successful (container creation for " + imageNumberUrl + ")");
                    Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                            "The ingest into Fedora was not successful as the container could not be created for " + imageNumberUrl);
                    return false;
                }
                containerCreated = createContainer(filesUrl, userName, password);
                if (!containerCreated) {
                    Helper.addMessageToProcessLog(process.getId(), LogType.ERROR,
                            "The ingest into Fedora was not successful (container creation for " + filesUrl + ")");
                    Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                            "The ingest into Fedora was not successful as the container could not be created for " + filesUrl);
                    return false;
                }

                // add /files container membership metadata
                if (filesContainerMetadataQuery != null) {
                    log.debug("Adding /files container metadata for file " + i);
                    if (!addPropertyViaSparql(filesUrl, filesContainerMetadataQuery.replace("[URL]", imageNumberUrl), userName, password)) {
                        Helper.addMessageToProcessLog(process.getId(), LogType.ERROR,
                                "The ingest into Fedora was not successful ([URL] property creation for " + imageNumberUrl + ")");
                        Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                                "The ingest into Fedora was not successful ([URL] property creation for " + imageNumberUrl + ")");
                        return false;
                    }
                    // TODO add metadata rdf:type
                    // TODO value: https://urldefense.proofpoint.com/v2/url?u=http-3A__pcdm.org_models-23Object&d=DwID-g&c=JnBkUqWXzx2bz-3a05d47Q&r=EEGRrm4Z8GH-zGshinHEIrcAhnouow98GQfr8zw1BZ4&m=4YXGRWKoNv-swVJLNBMEnrrtkGUfEz6dMSP1anqElvk&s=DNwiWXa-O30dZechpyPGgKent__3l65A8cm_HPgSKhU&e=
                    // TODO value: https://urldefense.proofpoint.com/v2/url?u=http-3A__www.w3.org_ns_ldp-23DirectContainer&d=DwID-g&c=JnBkUqWXzx2bz-3a05d47Q&r=EEGRrm4Z8GH-zGshinHEIrcAhnouow98GQfr8zw1BZ4&m=4YXGRWKoNv-swVJLNBMEnrrtkGUfEz6dMSP1anqElvk&s=hkv1824dR7ukNf1AU4BESFY1m2m7u_PS2tvHrXG4CKg&e=

                }

                // define folder where to ingest
                WebTarget target = recordUrl.path(iValue + "/files"); // URL for the folder with the correct image number

                String fileUrl = null;
                int[] imageDimensions = { 0, 0 };

                // ingest master
                if (ingestMaster && masterFile != null) {
                    fileUrl = addFileResource(masterFile, target.path(masterFile.getFileName().toString()), version, transactionUrl);
                    ingestLocation.path("fcr:tx").request().post(null);
                    imageDimensions = getImageDimensions(masterFile);
                }
                // ingest media
                if (ingestMedia && mediaFile != null) {
                    fileUrl = addFileResource(mediaFile, target.path(mediaFile.getFileName().toString()), version, transactionUrl);
                    ingestLocation.path("fcr:tx").request().post(null);
                    imageDimensions = getImageDimensions(mediaFile);
                }
                // ingest pdf
                if (ingestPdf && pdfFile != null) {
                    fileUrl = addFileResource(pdfFile, target.path(pdfFile.getFileName().toString()), version, transactionUrl);
                    ingestLocation.path("fcr:tx").request().post(null);
                }
                // ingest jp2
                if (ingestJp2 && jp2File != null) {
                    fileUrl = addFileResource(jp2File, target.path(jp2File.getFileName().toString()), version, transactionUrl);
                    ingestLocation.path("fcr:tx").request().post(null);
                    imageDimensions = getImageDimensions(jp2File);
                }

                if (fileUrl != null && imageFileMetadataQuery != null) {
                    log.debug("Adding image dimensions metadata for file " + i);
                    if (!addPropertyViaSparql(fileUrl + "/fcr:metadata", imageFileMetadataQuery.replace("[WIDTH]", String.valueOf(imageDimensions[0]))
                            .replace("[HEIGHT]", String.valueOf(imageDimensions[1])), userName, password)) {
                        Helper.addMessageToProcessLog(process.getId(), LogType.ERROR,
                                "The ingest into Fedora was not successful ([WIDTH]/[HEIGHT] property creation for " + imageNumberUrl + ")");
                        Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                                "The ingest into Fedora was not successful ([WIDTH]/[HEIGHT] property creation for " + imageNumberUrl + ")");
                        return false;
                    }
                }
            }

            // Add /image container membership metadata
            String imagesContainerMetadataQuery = myconfig.getString("imagesContainerMetadataQuery");
            if (imagesContainerMetadataQuery != null) {
                log.debug("Adding /images container metadata");
                if (!addPropertyViaSparql(barcodeUrl4, imagesContainerMetadataQuery.replace("[URL]", barcodeUrl3), userName, password)) {
                    Helper.addMessageToProcessLog(process.getId(), LogType.ERROR,
                            "The ingest into Fedora was not successful ([URL] property creation for " + barcodeUrl3 + ")");
                    Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                            "The ingest into Fedora was not successful ([URL] property creation for " + barcodeUrl3 + ")");
                    return false;
                }
            }

            // add crm url
            if (externalLinkContent != null) {
                if (!addPropertyViaSparql(barcodeUrl3, externalLinkContent.replace("[BARCODE]", properties.get(PROP_NAME_BARCODE))
                        .replace("[UNIT_ITEM_CODE]", properties.get(PROP_NAME_UNIT_ITEM_CODE)), userName, password)) {
                    Helper.addMessageToProcessLog(process.getId(), LogType.ERROR,
                            "The ingest into Fedora was not successful ([BARCODE]/[UNIT_ITEM_CODE] property creation for " + barcodeUrl3 + ")");
                    Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                            "The ingest into Fedora was not successful ([BARCODE]/[UNIT_ITEM_CODE] property creation for " + barcodeUrl3 + ")");
                    return false;
                }
            }
            // add full_partial
            if (fullPartialContent != null && properties.get(PROP_NAME_FULL_PARTIAL) != null) {
                if (!addPropertyViaSparql(barcodeUrl3, fullPartialContent.replace("[FULL_PARTIAL]", properties.get(PROP_NAME_FULL_PARTIAL)), userName,
                        password)) {
                    Helper.addMessageToProcessLog(process.getId(), LogType.ERROR,
                            "The ingest into Fedora was not successful ([FULL_PARTIAL] property creation for " + barcodeUrl3 + ")");
                    Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                            "The ingest into Fedora was not successful ([FULL_PARTIAL] property creation for " + barcodeUrl3 + ")");
                    return false;
                }
            }
            // add available
            if (availableMetadataQuery != null && properties.get(PROP_NAME_AVAILABLE) != null) {
                if (!addPropertyViaSparql(barcodeUrl3, availableMetadataQuery.replace("[DATE_AVAILABLE]", properties.get(PROP_NAME_AVAILABLE)),
                        userName, password)) {
                    Helper.addMessageToProcessLog(process.getId(), LogType.ERROR,
                            "The ingest into Fedora was not successful ([DATE_AVAILABLE] property creation for " + barcodeUrl3 + ")");
                    Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                            "The ingest into Fedora was not successful ([DATE_AVAILABLE] property creation for " + barcodeUrl3 + ")");
                    return false;
                }
            }

            // Finish the entire ingest by committing the transaction
            ingestLocation.path("fcr:tx").path("fcr:commit").request().post(null);
            log.info("Ingest of '" + process.getTitel() + "' successfully finished.");
            Helper.addMessageToProcessLog(process.getId(), LogType.INFO, "Ingest into Fedora successfully finished.");
            Helper.setMeldung(null, process.getTitel() + ": ", "ExportFinished");
            success = true;
            return true;
        } catch (IOException | DAOException | InterruptedException | SwapException e) {
            log.error(e.getMessage(), e);
            Helper.addMessageToProcessLog(process.getId(), LogType.ERROR,
                    "The ingest into Fedora was not successful and the transaction got rolled back: " + e.getMessage());
            Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                    "The ingest into Fedora was not successful and the transaction got rolled back: " + e.getMessage());
            return false;
        } finally {
            // Roll back transaction, if anything fails
            if (!success) {
                log.info("Rolling back transaction...");
                ingestLocation.path("fcr:tx").path("fcr:rollback").request().post(null);
            }
        }
    }

    /**
     * Returns image dimensions for the given image file path.
     * 
     * @param imageFile Path containing the image file
     * @return Integer array containing {width, height}
     */
    public int[] getImageDimensions(Path imageFile) {
        int[] ret = { 0, 0 };
        if (imageFile == null) {
            log.error("imageFile is null");
            return ret;
        }

        try (ImageManager im = new ImageManager(imageFile.toUri())) {
            ret[0] = im.getMyInterpreter().getWidth();
            ret[1] = im.getMyInterpreter().getHeight();
        } catch (ImageManagerException e) {
            log.error(e.getMessage(), e);
        } catch (FileNotFoundException e) {
            log.error(e.getMessage(), e);
        }

        return ret;
    }

    /**
     * Delete a resource form fedora based on the container name
     * 
     * @param process
     * @param recordContainer the container name to delete
     * @return
     */
    public boolean deleteResource(Process process, WebTarget recordContainer) {
        // Check whether the container for this record already exists (GET operation;
        // returns 200 if exists)
        Response response = recordContainer.request().get();
        if (response.getStatus() == 200) {
            log.debug("Record container already exists: " + recordContainer.getUri().toString());
            // Delete the container (DELETE operation)
            response = recordContainer.request().delete();
            switch (response.getStatus()) {
                case 204:
                    // Each deleted resource leaves a tombstone which prevents a resource with the same name from being created, 
                    // so the tombstone has to be deleted as well (DELETE operation)
                    response = recordContainer.path("fcr:tombstone").request().delete();
                    switch (response.getStatus()) {
                        case 204:
                            // Deleted successfully
                            log.debug("Record container deleted");
                            break;
                        default:
                            // Error occured while deleting the tombstone
                            String body = response.readEntity(String.class);
                            String msg = response.getStatus() + ": " + response.getStatusInfo().getReasonPhrase() + " - " + body;
                            log.error(msg);
                            Helper.addMessageToProcessLog(process.getId(), LogType.ERROR, "The ingest into Fedora was not successful: " + msg);
                            Helper.setFehlerMeldung(null, process.getTitel() + ": ", "The ingest into Fedora was not successful: " + msg);
                            return false;
                    }
                    break;
                default:
                    // a general error occurred and gets logged
                    String body = response.readEntity(String.class);
                    String msg = response.getStatus() + ": " + response.getStatusInfo().getReasonPhrase() + " - " + body;
                    log.error(msg);
                    Helper.addMessageToProcessLog(process.getId(), LogType.ERROR, "The ingest into Fedora was not successful: " + msg);
                    Helper.setFehlerMeldung(null, process.getTitel() + ": ", "The ingest into Fedora was not successful: " + msg);
                    return false;
            }
        }
        return true;
    }

    /**
     * Adds the given binary file to Fedora
     * 
     * @param file File to add
     * @param target Target URL containing the transaction ID
     * @param version Version name, if using versioning; otherwise null
     * @param Transaction URL prefix used to remove transaction IDs from the final file location URL
     * @return File location URL in Fedora
     * @throws IOException
     */
    private static String addFileResource(Path file, WebTarget target, String version, String transactionUrl) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file may not be null");
        }
        if (target == null) {
            throw new IllegalArgumentException("target may not be null");
        }

        // Check resource existence
        boolean exists = false;
        Response response = target.request().get();
        if (response.getStatus() == 200) {
            exists = true;
            log.debug("Resource already exists: " + target.getUri().toURL().toString().replace(transactionUrl, fedoraUrl));
        }

        try (InputStream inputStream = new FileInputStream(file.toFile())) {
            // Determine mime type using Java NIO
            String mimeType = Files.probeContentType(file);
            // If mime type could not be determined, use alternate method
            if (mimeType == null) {
                mimeType = URLConnection.guessContentTypeFromStream(inputStream);
            }
            // Manual fallback for Macs (using the file extension)
            if (mimeType == null) {
                String extension = FilenameUtils.getExtension(file.getFileName().toString());
                if (extension != null) {
                    switch (extension.toLowerCase()) {
                        case "tif":
                        case "tiff":
                            mimeType = "image/tiff";
                            break;
                        case "jpg":
                        case "jpeg":
                            mimeType = "image/jpeg";
                            break;
                        case "jp2":
                            mimeType = "image/jp2";
                            break;
                        case "png":
                            mimeType = "image/png";
                            break;
                        case "pdf":
                            mimeType = "application/pdf";
                            break;
                        case "xml":
                            mimeType = "text/xml";
                            break;
                        default:
                            mimeType = "text/html";
                            break;
                    }
                    log.debug("Manually determined mime type: " + mimeType);
                }
            }
            // Create HTTP entity from the file
            Entity<InputStream> fileEntity = Entity.entity(inputStream, mimeType);
            if (exists) {
                if (version != null) {
                    // Add new version (POST operation)
                    // "Slug" is the version name attribute
                    // "Content-Disposition" attribute contains the file name
                    response = target.path("fcr:versions")
                            .request()
                            .header("Slug", version)
                            .header("Content-Disposition", "attachment; filename=\"" + file.getFileName().toString() + "\"")
                            .post(Entity.entity(inputStream, mimeType));
                } else {
                    // No versioning: Delete file so it can be replaced (DELETE operation)
                    // TODO This part is obsolete because the entire container is now deleted if it
                    // already exists (much faster)
                    response = target.request().delete();
                    if (response.getStatus() != 204) {
                        // Error
                        String body = response.readEntity(String.class);
                        String msg = response.getStatus() + ": " + response.getStatusInfo().getReasonPhrase() + " - " + body;
                        log.error(msg);
                        log.error(body);
                        throw new IOException(msg);
                    }
                    // Delete tombstone (DELETE operation)
                    response = target.path("fcr:tombstone").request().delete();
                    if (response.getStatus() == 204) {
                        // Add file again (PUT operation)
                        // "Content-Disposition" attribute contains the file name
                        response = target.request()
                                .header("Content-Disposition", "attachment; filename=\"" + file.getFileName().toString() + "\"")
                                .put(fileEntity);
                    } else {
                        // Error
                        String body = response.readEntity(String.class);
                        String msg = response.getStatus() + ": " + response.getStatusInfo().getReasonPhrase() + " - " + body;
                        log.error(msg);
                        log.error(body);
                        throw new IOException(msg);
                    }
                }
            } else {
                // File does not exist yet, so just add it (PUT operation)
                // "Content-Disposition" attribute contains the file name
                response = target.request()
                        .header("Content-Disposition", "attachment; filename=\"" + file.getFileName().toString() + "\"")
                        .put(fileEntity);
            }
            // Handle response to the file adding operation (both versioned or not)
            switch (response.getStatus()) {
                case 201:
                    if (exists) {
                        if (version != null) {
                            // Successfully added new version
                            log.debug("New resource version " + version + " added: "
                                    + response.getHeaderString("location").replace(transactionUrl, fedoraUrl));
                        } else {
                            // Successfully deleted and re-added file
                            log.debug("Resource updated: " + response.getHeaderString("location").replace(transactionUrl, fedoraUrl));
                        }
                    } else {
                        // Added completely new file
                        log.debug("New resource added: " + response.getHeaderString("location").replace(transactionUrl, fedoraUrl));
                    }
                    break;
                default:
                    // Error
                    String body = response.readEntity(String.class);
                    log.error(body);
                    throw new IOException(response.getStatus() + ": " + response.getStatusInfo().getReasonPhrase());
            }

            return response.getHeaderString("location");
        } finally {
            if (response != null) {
                response.close();
            }
        }

    }

    /**
     * Creates the container hierarchy for the record (which is .../records/<record identifier>/media/). Containers along the path can be created
     * implicitly (i.e. creating "records/PPN123/media" will also created "/records" and "/records/PPN123"), but implicitly created containers have
     * the "pairtree" type and cannot contain binary documents. Therefore the containers for the record identifier and the media folder are created
     * explicitly here. Apache HTTP client is used here because it supports PUT operations without an entity.
     * 
     * @param rootUrl
     * @param identifier
     * @return
     */
    private static boolean createContainer(String url, String userName, String password) {
        CredentialsProvider provider = Utils.getCredentialsProvider(userName, password);

        // Create proper (non-pairtree) container for the record identifier
        // Create container (PUT operation with no entity - an empty entity will create an empty file instead)
        HttpPut put = new HttpPut(url);
        try (CloseableHttpClient httpClient =
                provider != null ? HttpClientBuilder.create().setDefaultCredentialsProvider(provider).build() : HttpClients.createMinimal();
                CloseableHttpResponse httpResponse = httpClient.execute(put); StringWriter writer = new StringWriter()) {
            switch (httpResponse.getStatusLine().getStatusCode()) {
                case 201:
                    // Container created
                    log.info("Container created: " + url);
                    break;
                case 204:
                case 409:
                    // Container already exists
                    log.debug("Container already exists: " + url);
                    break;
                default:
                    // Error
                    String body = IOUtils.toString(httpResponse.getEntity().getContent(), "UTF-8");
                    log.error(httpResponse.getStatusLine().getStatusCode() + ": " + httpResponse.getStatusLine().getReasonPhrase() + " - " + body);
                    return false;
            }
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            return false;
        }

        return true;
    }

    /**
     * Add cidoc crm URL for the images URL of the ingested data
     * 
     * @param rootUrl the images-container url to use
     * @param content
     * @param userName
     * @param password
     * @return
     */
    private static boolean addPropertyViaSparql(String rootUrl, String content, String userName, String password) {
        HttpPatch put = new HttpPatch(rootUrl);
        ContentType ct = ContentType.create("application/sparql-update");
        HttpEntity he = EntityBuilder.create().setText(content).setContentType(ct).build();
        put.setEntity(he);

        CredentialsProvider provider = Utils.getCredentialsProvider(userName, password);
        try (CloseableHttpClient httpClient =
                provider != null ? HttpClientBuilder.create().setDefaultCredentialsProvider(provider).build() : HttpClients.createMinimal();
                CloseableHttpResponse httpResponse = httpClient.execute(put)) {
            log.debug("Status code for cidoc crm URL generation is: " + httpResponse.getStatusLine().getStatusCode());

            switch (httpResponse.getStatusLine().getStatusCode()) {
                case 204:
                    // URL created
                    log.info("Cidoc crm URL created for " + rootUrl);
                    break;
                default:
                    // Error
                    String body = IOUtils.toString(httpResponse.getEntity().getContent(), "UTF-8");
                    log.error(httpResponse.getStatusLine().getStatusCode() + ": " + httpResponse.getStatusLine().getReasonPhrase() + " - " + body);
                    log.error("Content:\n" + content);
                    return false;
            }
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            return false;
        }
        return true;
    }

    public String getDescription() {
        return getTitle();
    }

    @Override
    public List<String> getProblems() {
        return null;
    }

    private static final DirectoryStream.Filter<Path> ImageAndPdfFilter = new DirectoryStream.Filter<Path>() {
        @Override
        public boolean accept(Path path) {
            boolean fileOk = false;
            String name = path.getFileName().toString().toLowerCase();
            if (name.endsWith(".pdf")) {
                fileOk = true;
            } else if (name.endsWith(".tif")) {
                fileOk = true;
            } else if (name.endsWith(".tiff")) {
                fileOk = true;
            } else if (name.endsWith(".jpg")) {
                fileOk = true;
            } else if (name.endsWith(".jpeg")) {
                fileOk = true;
            } else if (name.endsWith(".jp2")) {
                fileOk = true;
            }
            return fileOk;
        }
    };

    public static void main(String[] args) throws Exception {
        FedoraExportPlugin plugin = new FedoraExportPlugin();
    }
}
