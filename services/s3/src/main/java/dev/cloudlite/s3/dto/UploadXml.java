package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import java.time.OffsetDateTime;

public class UploadXml {

    @JacksonXmlProperty(localName = "Key")
    private final String key;

    @JacksonXmlProperty(localName = "UploadId")
    private final String uploadId;

    @JacksonXmlProperty(localName = "Initiated")
    private final OffsetDateTime initiated;

    public UploadXml(String key, String uploadId, OffsetDateTime initiated) {
        this.key = key;
        this.uploadId = uploadId;
        this.initiated = initiated;
    }

    public String getKey() { return key; }
    public String getUploadId() { return uploadId; }
    public OffsetDateTime getInitiated() { return initiated; }
}
