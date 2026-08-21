package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import java.time.OffsetDateTime;

public class BucketXml {

    @JacksonXmlProperty(localName = "Name")
    private final String name;

    @JacksonXmlProperty(localName = "CreationDate")
    private final OffsetDateTime creationDate;

    public BucketXml(String name, OffsetDateTime creationDate) {
        this.name = name;
        this.creationDate = creationDate;
    }

    public String getName() {
        return name;
    }

    public OffsetDateTime getCreationDate() {
        return creationDate;
    }
}
