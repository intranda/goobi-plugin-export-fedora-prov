package de.intranda.goobi.plugins;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.entity.EntityBuilder;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.goobi.beans.GoobiProperty;
import org.goobi.beans.GoobiProperty.PropertyOwnerType;
import org.goobi.beans.Process;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import de.intranda.goobi.plugins.model.BarcodeURLBuilder;
import de.intranda.goobi.plugins.model.IURLBuilder;
import de.intranda.goobi.plugins.model.PIDURLBuilder;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import de.sub.goobi.persistence.managers.PropertyManager;
import de.unigoettingen.sub.commons.contentlib.exceptions.ImageManagerException;
import de.unigoettingen.sub.commons.contentlib.imagelib.ImageManager;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

@PluginImplementation
public class FedoraExportPlugin implements IExportPlugin, IPlugin {

    private static final long serialVersionUID = 4761152838946817150L;

    private static final Logger log = Logger.getLogger(FedoraExportPlugin.class);

    private static final String PLUGIN_NAME = "prov_export_fedora";

    private static final String PROP_NAME_BARCODE = "barcode";
    //    private static final String PROP_NAME_PID = "PID";
    private static final String PROP_NAME_UNIT_ITEM_CODE = "unit_Item_code";
    private static final String PROP_NAME_FULL_PARTIAL = "full_partial";
    private static final String PROP_NAME_AVAILABLE = "available";

    private static final String HEADER_NAME_ATOMIC_ID = "Atomic-ID";

    public static final DateTimeFormatter formatter = DateTimeFormat.forPattern("yyyy-MM-dd_HH.mm.ss.SSS");

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
        return ingestData(process);
    }

    /**
     * @param folder
     * @param process
     * @param useVersioning If true, new versions of the existing resource will be added; if false, the resource will be deleted and created anew (all
     *            previous versions will be deleted).
     */
    private boolean ingestData(Process process) {
        boolean success = false;
        boolean usePID = false;

        // get workflow name from properties
        String workflowName = null;
        for (GoobiProperty pp : process.getProperties()) {
            if ("Template".equals(pp.getPropertyName())) {
                workflowName = pp.getPropertyValue();
            }
        }

        // get the right configuration from the config file
        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig(PLUGIN_NAME);
        log.debug("Config file path: " + xmlConfig.getBasePath());
        xmlConfig.setExpressionEngine(new XPathExpressionEngine());
        xmlConfig.setReloadingStrategy(new FileChangedReloadingStrategy());
        SubnodeConfiguration myconfig = null;
        try {
            myconfig = xmlConfig.configurationAt("//config[./workflow = '" + workflowName + "']");
        } catch (IllegalArgumentException e) {
            myconfig = xmlConfig.configurationAt("//config[./workflow = '*']");
        }

        // now read from the right config area
        String fedoraUrl = myconfig.getString("fedoraUrl", "http://localhost:8080/fedora/rest");
        log.debug("Fedora URL: " + fedoraUrl);
        String userName = myconfig.getString("userName");
        String password = myconfig.getString("password");

        boolean useVersioning = myconfig.getBoolean("useVersioning", true);
        boolean ingestMaster = myconfig.getBoolean("ingestMaster", true);
        boolean ingestMedia = myconfig.getBoolean("ingestMedia", true);
        boolean ingestJp2 = myconfig.getBoolean("ingestJp2", true);
        boolean ingestPdf = myconfig.getBoolean("ingestPdf", true);

        Map<String, String> properties = HashMap.newHashMap(4);
        // get the barcode from the property list
        for (GoobiProperty prop : process.getEigenschaftenList()) {
            if (prop.getPropertyName() == null) {
                continue;
            }
            properties.put(prop.getPropertyName(), prop.getPropertyValue());
        }

        String barcodeOrPID = properties.get(PROP_NAME_BARCODE);
        //        if (barcodeOrPID == null) {
        //            barcodeOrPID = properties.get(PROP_NAME_PID);
        //        }
        if (barcodeOrPID == null) {
            Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR,
                    "The ingest into Fedora was not successful as no barcode or PID could be found in the properties.");
            Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                    "The ingest into Fedora was not successful as no barcode or PID could be found in the properties.");
            return false;
        }
        if (barcodeOrPID.length() == 36) {
            usePID = true;
            log.info("Using PID");
            Helper.addMessageToProcessJournal(process.getId(), LogType.INFO, "PID detected: " + barcodeOrPID);
        } else {
            log.info("Using barcode");
            Helper.addMessageToProcessJournal(process.getId(), LogType.INFO, "Barcode detected: " + barcodeOrPID);
        }

        if (!usePID && StringUtils.isEmpty(properties.get(PROP_NAME_UNIT_ITEM_CODE))) {
            Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR,
                    "The ingest into Fedora was not successful as no type could be found in the properties.");
            Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                    "The ingest into Fedora was not successful as no type could be found in the properties.");
            return false;
        }

        if (StringUtils.isNotEmpty(properties.get(PROP_NAME_UNIT_ITEM_CODE))) {
            properties.put(PROP_NAME_UNIT_ITEM_CODE, properties.get(PROP_NAME_UNIT_ITEM_CODE).toUpperCase().substring(0, 1)
                    + properties.get(PROP_NAME_UNIT_ITEM_CODE).toLowerCase().substring(1));
        }

        IURLBuilder urlBuilder = usePID ? new PIDURLBuilder(fedoraUrl, barcodeOrPID)
                : new BarcodeURLBuilder(fedoraUrl, barcodeOrPID, properties.get(PROP_NAME_UNIT_ITEM_CODE));

        // JAX-WS HTTP client
        try (Client client = ClientBuilder.newClient()) {
            if (StringUtils.isNotEmpty(userName) && StringUtils.isNotEmpty(password)) {
                log.info("Using configured basic HTTP credentials.");
                // Helper.addMessageToProcessJournal(process.getId(), LogType.INFO, "Using configured basic HTTP credentials.");
                client.register(new BasicHttpAuthenticator(userName, password));
            }
            WebTarget fedoraBase = client.target(fedoraUrl);

            // If not using versioning remove resource prior to ingesting to speed things up
            if (!useVersioning) {
                WebTarget recordContainer = fedoraBase.path("records" + urlBuilder.getImageContainerUrlPart());
                String deleteTransactionUrl = createTransaction(fedoraBase, process.getId(), process.getTitel());
                if (deleteTransactionUrl == null) {
                    log.error("Deletion transaction could not be opened.");
                    return false;
                }
                if (deleteResource(process, recordContainer, deleteTransactionUrl)) {
                    // Commit deletion transaction before creating anything new
                    client.target(deleteTransactionUrl).request().put(Entity.entity("", MediaType.TEXT_PLAIN));
                } else {
                    Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR,
                            "The ingest into Fedora was not successful as the previous container could not be deleted for "
                                    + recordContainer.getUri().toString());
                    Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                            "The ingest into Fedora was not successful as the previous container could not be deleted for "
                                    + recordContainer.getUri().toString());
                    return false;
                }
            }

            String transactionUrl = createTransaction(fedoraBase, process.getId(), process.getTitel());
            if (transactionUrl == null) {
                Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR,
                        "The ingest into Fedora was not successful: No transaction ID received.");
                Helper.setFehlerMeldung(null, process.getTitel() + ": ", "The ingest into Fedora was not successful: No transaction ID received.");
                return false;
            }
            log.debug("Transaction URL: " + transactionUrl);
            // Base URL
            WebTarget ingestLocationBase = client.target(fedoraUrl);
            // URL including transaction ID
            WebTarget ingestLocationTransaction = client.target(transactionUrl);

            // Transaction that will be rolled back if anything fails
            try {

                // Create the required container hierarchy for the process identifier
                log.debug("Creating container hierarchy");
                createContainer(fedoraUrl, "records", transactionUrl, userName, password);
                for (int i = 0; i < urlBuilder.getParts().size(); ++i) {
                    String parentUrl = i > 0 ? urlBuilder.getContainerUrl(i - 1) : fedoraUrl + "/records";
                    boolean containerCreated = createContainer(parentUrl, urlBuilder.getContainerName(i), transactionUrl, userName, password);
                    if (!containerCreated) {
                        Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR,
                                "The ingest into Fedora was not successful (container creation for " + urlBuilder.getContainerUrl(i) + ")");
                        Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                                "The ingest into Fedora was not successful as the container could not be created for "
                                        + urlBuilder.getContainerUrl(i));
                        return false;
                    }
                }

                // Name for the new version, if using versioning
                String version = useVersioning ? "goobi-export." + formatter.print(System.currentTimeMillis()) : null;
                log.debug("version: " + version);

                WebTarget recordUrl = ingestLocationBase.path("records").path(urlBuilder.getImageContainerUrlPart()); // URL for the record folder
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
                    String imageNumberUrl = urlBuilder.getImageContainerUrl() + "/" + iValue;
                    String filesUrl = imageNumberUrl + "/files";
                    boolean containerCreated =
                            createContainer(urlBuilder.getImageContainerUrl(), iValue, transactionUrl, userName, password);
                    if (!containerCreated) {
                        Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR,
                                "The ingest into Fedora was not successful (container creation for " + imageNumberUrl + ")");
                        Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                                "The ingest into Fedora was not successful as the container could not be created for " + imageNumberUrl);
                        return false;
                    }
                    containerCreated = createContainer(imageNumberUrl, "files", transactionUrl, userName, password);
                    if (!containerCreated) {
                        Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR,
                                "The ingest into Fedora was not successful (container creation for " + filesUrl + ")");
                        Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                                "The ingest into Fedora was not successful as the container could not be created for " + filesUrl);
                        return false;
                    }

                    // add /files container membership metadata (must be added at creation time now; do not use old queries!)
                    String filesContainerMetadataQuery = myconfig.getString("filesContainerMetadataQuery");
                    if (filesContainerMetadataQuery != null) {
                        log.debug("Adding /files container metadata for file " + i);
                        if (!addPropertyViaSparql(filesUrl, filesContainerMetadataQuery.replace("[URL]", imageNumberUrl), transactionUrl, userName,
                                password)) {
                            Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR,
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
                        fileUrl = addFileResource(masterFile, target.path(masterFile.getFileName().toString()), transactionUrl);
                        imageDimensions = getImageDimensions(masterFile);
                    }
                    // ingest media
                    if (ingestMedia && mediaFile != null) {
                        fileUrl = addFileResource(mediaFile, target.path(mediaFile.getFileName().toString()), transactionUrl);
                        imageDimensions = getImageDimensions(mediaFile);
                    }
                    // ingest pdf
                    if (ingestPdf && pdfFile != null) {
                        fileUrl = addFileResource(pdfFile, target.path(pdfFile.getFileName().toString()), transactionUrl);
                    }
                    // ingest jp2
                    if (ingestJp2 && jp2File != null) {
                        fileUrl = addFileResource(jp2File, target.path(jp2File.getFileName().toString()), transactionUrl);
                        imageDimensions = getImageDimensions(jp2File);
                    }

                    String imageFileMetadataQuery = myconfig.getString("imageFileMetadataQuery");
                    if (fileUrl != null && imageFileMetadataQuery != null) {
                        log.info("Adding image dimensions metadata for file " + i);
                        if (!addPropertyViaSparql(fileUrl + "/fcr:metadata",
                                imageFileMetadataQuery.replace("[WIDTH]", String.valueOf(imageDimensions[0]))
                                        .replace("[HEIGHT]", String.valueOf(imageDimensions[1])),
                                transactionUrl, userName, password)) {
                            Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR,
                                    "The ingest into Fedora was not successful ([WIDTH]/[HEIGHT] property creation for " + imageNumberUrl + ")");
                            Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                                    "The ingest into Fedora was not successful ([WIDTH]/[HEIGHT] property creation for " + imageNumberUrl + ")");
                            return false;
                        }
                    }
                }

                // Add /image container membership metadata (must be added at creation time now; do not use old queries!)
                String imagesContainerMetadataQuery = myconfig.getString("imagesContainerMetadataQuery");
                if (imagesContainerMetadataQuery != null) {
                    log.info("Adding /images container metadata");
                    if (!addPropertyViaSparql(urlBuilder.getImageContainerUrl(),
                            imagesContainerMetadataQuery.replace("[URL]", urlBuilder.getRecordContainerUrl()), transactionUrl, userName, password)) {
                        Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR,
                                "The ingest into Fedora was not successful ([URL] property creation for " + urlBuilder.getRecordContainerUrl() + ")");
                        Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                                "The ingest into Fedora was not successful ([URL] property creation for " + urlBuilder.getRecordContainerUrl() + ")");
                        return false;
                    }
                }

                // add crm url
                String externalLinkContent = myconfig.getString("externalLinkContent");
                if (!usePID && externalLinkContent != null) {
                    // Barcode + unit item code type
                    if (!addPropertyViaSparql(urlBuilder.getRecordContainerUrl(),
                            externalLinkContent.replace("[BARCODE]", properties.get(PROP_NAME_BARCODE))
                                    .replace("[UNIT_ITEM_CODE]", properties.get(PROP_NAME_UNIT_ITEM_CODE)),
                            transactionUrl, userName, password)) {
                        Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR,
                                "The ingest into Fedora was not successful ([BARCODE]/[UNIT_ITEM_CODE] property creation for "
                                        + urlBuilder.getRecordContainerUrl() + ")");
                        Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                                "The ingest into Fedora was not successful ([BARCODE]/[UNIT_ITEM_CODE] property creation for "
                                        + urlBuilder.getRecordContainerUrl() + ")");
                        return false;
                    }
                } else if (usePID && myconfig.getString("externalLinkContentPID") != null) {
                    // PID type
                    String externalLinkContentPID = myconfig.getString("externalLinkContentPID");
                    if (!addPropertyViaSparql(urlBuilder.getRecordContainerUrl(), externalLinkContentPID.replace("[PID]", barcodeOrPID),
                            transactionUrl,
                            userName, password)) {
                        Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR,
                                "The ingest into Fedora was not successful ([PID] property creation for "
                                        + urlBuilder.getRecordContainerUrl() + ")");
                        Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                                "The ingest into Fedora was not successful ([PID] property creation for "
                                        + urlBuilder.getRecordContainerUrl() + ")");
                        return false;
                    }
                }
                // add full_partial
                String fullPartialContent = myconfig.getString("fullPartialContent");
                if (fullPartialContent != null && properties.get(PROP_NAME_FULL_PARTIAL) != null) {
                    if (!addPropertyViaSparql(urlBuilder.getRecordContainerUrl(),
                            fullPartialContent.replace("[FULL_PARTIAL]", properties.get(PROP_NAME_FULL_PARTIAL)), transactionUrl, userName,
                            password)) {
                        Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR,
                                "The ingest into Fedora was not successful ([FULL_PARTIAL] property creation for "
                                        + urlBuilder.getRecordContainerUrl()
                                        + ")");
                        Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                                "The ingest into Fedora was not successful ([FULL_PARTIAL] property creation for "
                                        + urlBuilder.getRecordContainerUrl()
                                        + ")");
                        return false;
                    }
                }
                // add available
                String availableMetadataQuery = myconfig.getString("availableMetadataQuery");
                if (availableMetadataQuery != null && properties.get(PROP_NAME_AVAILABLE) != null) {
                    if (!addPropertyViaSparql(urlBuilder.getRecordContainerUrl(),
                            availableMetadataQuery.replace("[DATE_AVAILABLE]", properties.get(PROP_NAME_AVAILABLE)), transactionUrl, userName,
                            password)) {
                        Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR,
                                "The ingest into Fedora was not successful ([DATE_AVAILABLE] property creation for "
                                        + urlBuilder.getRecordContainerUrl()
                                        + ")");
                        Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                                "The ingest into Fedora was not successful ([DATE_AVAILABLE] property creation for "
                                        + urlBuilder.getRecordContainerUrl()
                                        + ")");
                        return false;
                    }
                }

                // Finish the entire ingest by committing the transaction
                ingestLocationTransaction.request().put(Entity.entity("", MediaType.TEXT_PLAIN));

                // Version must be added outside of a transaction
                if (version != null) {
                    for (int i = 0; i < max; i++) {
                        WebTarget target = recordUrl.path((i + 1) + "/files"); // URL for the folder with the correct image number
                        try {
                            if (ingestMaster && masterFileList.size() > i) {
                                Path masterFile = masterFileList.get(i);
                                createVersion(target.path(masterFile.getFileName().toString()), version);
                            }
                            if (ingestMedia && mediaFileList.size() > i) {
                                Path mediaFile = mediaFileList.get(i);
                                createVersion(target.path(mediaFile.getFileName().toString()), version);
                            }
                            if (ingestJp2 && jp2FileList.size() > i) {
                                Path jp2File = jp2FileList.get(i);
                                createVersion(target.path(jp2File.getFileName().toString()), version);
                            }
                            if (ingestPdf && pdfFileList.size() > i) {
                                Path pdfFile = pdfFileList.get(i);
                                createVersion(target.path(pdfFile.getFileName().toString()), version);
                            }
                        } catch (IndexOutOfBoundsException ioc) {
                            // nothing happens here
                        }
                    }
                }

                log.info("Ingest of '" + process.getTitel() + "' successfully finished.");
                Helper.addMessageToProcessJournal(process.getId(), LogType.INFO, "Ingest into Fedora successfully finished.");
                Helper.setMeldung(null, process.getTitel() + ": ", "ExportFinished");
                success = true;
                return true;
            } catch (IOException | DAOException | SwapException e) {
                log.error(e.getMessage(), e);
                Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR,
                        "The ingest into Fedora was not successful and the transaction got rolled back: " + e.getMessage());
                Helper.setFehlerMeldung(null, process.getTitel() + ": ",
                        "The ingest into Fedora was not successful and the transaction got rolled back: " + e.getMessage());
                return false;
            } finally {
                // Roll back transaction, if anything fails
                if (!success) {
                    log.info("Rolling back transaction...");
                    ingestLocationTransaction.request().delete();
                } else {
                    // Write record container URL as new process property                   
                    GoobiProperty goobiProp = new GoobiProperty(PropertyOwnerType.PROCESS);
                    goobiProp.setOwner(process);
                    goobiProp.setPropertyName("root_SAMS_URL");
                    goobiProp.setPropertyValue(urlBuilder.getRecordContainerUrl());
                    PropertyManager.saveProperty(goobiProp);
                }
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
        } catch (ImageManagerException | FileNotFoundException e) {
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
    public boolean deleteResource(Process process, WebTarget recordContainer, String transactionUrl) {
        log.debug("deleteResource: " + recordContainer.getUri());
        if (StringUtils.isEmpty(transactionUrl)) {
            throw new IllegalArgumentException("transactionUrl may not me empty");
        }

        // Check whether the container for this record already exists (GET operation returns 200 if exists)
        Response response = recordContainer.request().header(HEADER_NAME_ATOMIC_ID, transactionUrl).get();
        if (response.getStatus() == 200) {
            log.debug("Record container already exists: " + recordContainer.getUri().toString());
            // Delete the container (DELETE operation)
            response = recordContainer.request().header(HEADER_NAME_ATOMIC_ID, transactionUrl).delete();
            log.debug("Delete response code: " + response.getStatus());
            switch (response.getStatus()) {
                case 204:
                case 410:
                    // Each deleted resource leaves a tombstone which prevents a resource with the same name from being created,
                    // so the tombstone has to be deleted as well (DELETE operation)
                    response = recordContainer.path("fcr:tombstone").request().header(HEADER_NAME_ATOMIC_ID, transactionUrl).delete();
                    switch (response.getStatus()) {
                        case 204:
                            // Deleted successfully
                            log.info("Record container deleted");
                            break;
                        default:
                            // Error occured while deleting the tombstone
                            String body = response.readEntity(String.class);
                            String msg = response.getStatus() + ": " + response.getStatusInfo().getReasonPhrase() + " - " + body;
                            log.error(msg);
                            Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, "The ingest into Fedora was not successful: " + msg);
                            Helper.setFehlerMeldung(null, process.getTitel() + ": ", "The ingest into Fedora was not successful: " + msg);
                            return false;
                    }
                    break;
                default:
                    // a general error occurred and gets logged
                    String body = response.readEntity(String.class);
                    String msg = response.getStatus() + ": " + response.getStatusInfo().getReasonPhrase() + " - " + body;
                    log.error(msg);
                    Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, "The ingest into Fedora was not successful: " + msg);
                    Helper.setFehlerMeldung(null, process.getTitel() + ": ", "The ingest into Fedora was not successful: " + msg);
                    return false;
            }
        }
        return true;
    }

    /**
     * 
     * @param fedoraBase
     * @param processId
     * @param processTitle
     * @return
     */
    private static String createTransaction(WebTarget fedoraBase, int processId, String processTitle) {
        if (fedoraBase == null) {
            throw new IllegalArgumentException("fedoraBase may not be null");
        }

        // Create a new transaction in Fedora (POST operation)
        log.debug("Creating transaction: " + fedoraBase.path("fcr:tx").getUri());
        Response transactionResponse = null;
        try {
            transactionResponse = fedoraBase.path("fcr:tx").request().post(null);
            if (transactionResponse == null) {
                Helper.addMessageToProcessJournal(processId, LogType.ERROR, "No transaction response" + " - " + fedoraBase.getUri().toString());
                Helper.setFehlerMeldung(null, "500", "No transaction response - " + fedoraBase.getUri().toString());
                return null;
            }
            if (transactionResponse.getStatus() >= 400) {
                log.debug("HTTP status: " + transactionResponse.getStatus());
                Helper.addMessageToProcessJournal(processId, LogType.ERROR,
                        transactionResponse.getStatusInfo().getReasonPhrase() + " - " + fedoraBase.getUri().toString());
                Helper.setFehlerMeldung(null, String.valueOf(transactionResponse.getStatus()),
                        transactionResponse.getStatusInfo().getReasonPhrase() + " - " + fedoraBase.getUri().toString());
                return null;
            }
            return transactionResponse.getHeaderString("Location");
        } catch (Exception e) {
            Helper.addMessageToProcessJournal(processId, LogType.ERROR, "The ingest into Fedora was not successful: " + e.getMessage());
            Helper.setFehlerMeldung(null, processTitle + ": ", "The ingest into Fedora was not successful: " + e.getMessage());
            log.error(e.getMessage(), e);
            return null;
        }
    }

    /**
     * Adds the given binary file to Fedora
     * 
     * @param file File to add
     * @param target Target URL containing the transaction ID
     * @param transactionUrl Transaction URL prefix used to remove transaction IDs from the final file location URL
     * @return File location URL in Fedora
     * @throws IOException
     */
    private static String addFileResource(Path file, WebTarget target, String transactionUrl) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file may not be null");
        }
        if (target == null) {
            throw new IllegalArgumentException("target may not be null");
        }

        // Create HTTP entity from the file
        try (InputStream inputStream = new FileInputStream(file.toFile())) {
            // Determine mime type using Java NIO
            String mimeType = Files.probeContentType(file);
            // If mime type could not be determined, use alternate method
            if (mimeType == null) {
                mimeType = URLConnection.guessContentTypeFromStream(inputStream);
            }
            // Manual fallback for Macs (using the file extension)
            if (mimeType == null) {
                mimeType = guessMimeTypeFromExtension(file);
            }
            Entity<InputStream> entity = Entity.entity(inputStream, mimeType);

            Response response = target.request()
                    .header(HEADER_NAME_ATOMIC_ID, transactionUrl)
                    .header("Content-Disposition",
                            "attachment; filename=\"" + file.getFileName() + "\"")
                    .put(entity);

            if (response.getStatus() != 201 && response.getStatus() != 204) {
                String body = response.readEntity(String.class);
                throw new IOException(response.getStatus() + ": " +
                        response.getStatusInfo().getReasonPhrase() + " - " + body);
            }

            return response.getHeaderString("Location");
        }
    }

    /**
     * 
     * @param file
     * @return Determined mime type; null if file has no extension
     */
    private static String guessMimeTypeFromExtension(Path file) {
        String mimeType = null;
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

        return mimeType;
    }

    private static void createVersion(WebTarget target, String versionLabel) throws IOException {
        log.debug("createVersion: " + target.getUri() + "/fcr:versions?versionLabel=" + versionLabel);
        Response response = target.path("fcr:versions")
                .queryParam("versionLabel", versionLabel)
                .request()
                .post(null);

        if (response.getStatus() != 201) {
            String body = response.readEntity(String.class);
            throw new IOException(response.getStatus() + ": " +
                    response.getStatusInfo().getReasonPhrase() + " - " + body);
        }

        log.debug("Version created: " + versionLabel);
    }

    /**
     * Creates the container hierarchy for the record (which is .../records/<record identifier>/media/). Containers along the path can be created
     * implicitly (i.e. creating "records/PPN123/media" will also created "/records" and "/records/PPN123"), but implicitly created containers have
     * the "pairtree" type and cannot contain binary documents. Therefore the containers for the record identifier and the media folder are created
     * explicitly here. Apache HTTP client is used here because it supports PUT operations without an entity.
     * 
     * @param parentUrl
     * @param containerName
     * @param transactionUrl
     * @param userName
     * @param password
     * @return true if container created successfully or already exists; false on error
     */
    private static boolean createContainer(String parentUrl, String containerName, String transactionUrl, String userName, String password) {
        log.debug("createContainer: " + parentUrl + "/" + containerName);
        if (StringUtils.isEmpty(transactionUrl)) {
            throw new org.jboss.weld.exceptions.IllegalArgumentException("transactionUrl may not me empty");
        }

        CredentialsProvider provider = Utils.getCredentialsProvider(userName, password);

        // Create proper (non-pairtree) container for the record identifier
        // POST to parent container with Slug header
        HttpPost post = new HttpPost(parentUrl);

        // Fedora 6: use Slug header for new container name
        post.setHeader("Slug", containerName);

        // Fedora 6: attach transaction via Atomic-ID header
        post.setHeader(HEADER_NAME_ATOMIC_ID, transactionUrl);

        // log.debug("REQUEST URI: " + post.getURI());
        // log.debug("Atomic-ID: " + post.getFirstHeader(HEADER_NAME_ATOMIC_ID));

        // Fedora 6: Set DirectContainer type at creation type
        if (isDirectContainer(containerName)) {
            post.setHeader("Link", "<http://www.w3.org/ns/ldp#DirectContainer>; rel=\"type\"");

            // Member relation properties must be added at creation time (otherwise it will add empty properties that cannot be replaced)
            String memberRelation = getMemberRelation(containerName);
            if (memberRelation != null) {
                post.setHeader("Content-Type", "text/turtle");
                String body =
                        "@prefix ldp: <http://www.w3.org/ns/ldp#> .\n" +
                                "@prefix pcdm: <http://pcdm.org/models#> .\n\n" +
                                "<> ldp:membershipResource <" + parentUrl + "> ;\n" +
                                "   ldp:hasMemberRelation " + memberRelation + " .";
                post.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
            }
        }

        try (CloseableHttpClient httpClient =
                provider != null ? HttpClientBuilder.create().setDefaultCredentialsProvider(provider).build() : HttpClients.createMinimal();
                CloseableHttpResponse httpResponse = httpClient.execute(post); StringWriter writer = new StringWriter()) {
            switch (httpResponse.getStatusLine().getStatusCode()) {
                case 201:
                    log.debug("Container created: " + parentUrl + "/" + containerName);
                    break;
                case 204:
                    log.debug("Container already exists: " + parentUrl + "/" + containerName);
                    break;
                case 409:
                    log.debug(httpResponse.getStatusLine().getStatusCode() + " " + EntityUtils.toString(httpResponse.getEntity()));
                    return false;
                default:
                    // Error
                    String body = IOUtils.toString(httpResponse.getEntity().getContent(), StandardCharsets.UTF_8);
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
     * 
     * @param name Container name
     * @return true if container with the given name should be a DirectContainer; false otherwise
     */
    private static boolean isDirectContainer(String name) {
        return "files".equals(name) || "images".equals(name) || "sequence".equals(name);
    }

    /**
     * 
     * @param name Container name
     * @return Value for ldp:hasMemberRelation
     */
    private static String getMemberRelation(String name) {
        switch (name) {
            case "files":
                return "pcdm:hasFile";
            case "images":
                return "pcdm:hasMember";
            default:
                return null; // e.g. "sequence" has no membership config
        }
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
    private static boolean addPropertyViaSparql(String rootUrl, String content, String transactionUrl, String userName, String password) {
        // log.debug("addPropertyViaSparql: " + rootUrl + '\n' + content);
        HttpPatch put = new HttpPatch(rootUrl);
        ContentType ct = ContentType.create("application/sparql-update");
        HttpEntity he = EntityBuilder.create().setText(content).setContentType(ct).build();
        put.addHeader(HEADER_NAME_ATOMIC_ID, transactionUrl);
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
                    String body = IOUtils.toString(httpResponse.getEntity().getContent(), StandardCharsets.UTF_8);
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

    private static final DirectoryStream.Filter<Path> ImageAndPdfFilter = new DirectoryStream.Filter<>() {
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
