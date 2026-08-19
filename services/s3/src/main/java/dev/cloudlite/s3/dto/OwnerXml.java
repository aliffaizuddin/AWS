package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

public class OwnerXml {

    @JacksonXmlProperty(localName = "ID")
    private final String id;

    @JacksonXmlProperty(localName = "DisplayName")
    private final String displayName;

    public OwnerXml(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }
}
