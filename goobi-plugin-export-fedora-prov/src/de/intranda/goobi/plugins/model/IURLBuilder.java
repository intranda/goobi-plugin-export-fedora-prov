package de.intranda.goobi.plugins.model;

import java.util.List;

public interface IURLBuilder {

    public List<String> getParts();

    public String getContainerUrl(int level);
    
    public String getRecordContainerUrl();
    
    public String getImageContainerUrl();
    
    public String getImageContainerUrlPart();
}
