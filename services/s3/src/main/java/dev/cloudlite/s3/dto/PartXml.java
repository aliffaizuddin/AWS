package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import java.time.OffsetDateTime;

public class PartXml {

    @JacksonXmlProperty(localName = "PartNumber")
    private final int partNumber;

    @JacksonXmlProperty(localName = "ETag")
    private final String etag;

    @JacksonXmlProperty(localName = "Size")
    private final long size;

    @JacksonXmlProperty(localName = "LastModified")
    private final OffsetDateTime lastModified;

    public PartXml(int partNumber, String etag, long size, OffsetDateTime lastModified) {
        this.partNumber = partNumber;
        this.etag = etag;
        this.size = size;
        this.lastModified = lastModified;
    }

    public int getPartNumber() { return partNumber; }
    public String getEtag() { return etag; }
    public long getSize() { return size; }
    public OffsetDateTime getLastModified() { return lastModified; }
}
