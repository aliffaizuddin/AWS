package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.util.List;

@JacksonXmlRootElement(localName = "ListPartsResult")
public class ListPartsResultXml {

    @JacksonXmlProperty(localName = "Bucket")
    private final String bucket;

    @JacksonXmlProperty(localName = "Key")
    private final String key;

    @JacksonXmlProperty(localName = "UploadId")
    private final String uploadId;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "Part")
    private final List<PartXml> parts;

    public ListPartsResultXml(String bucket, String key, String uploadId, List<PartXml> parts) {
        this.bucket = bucket;
        this.key = key;
        this.uploadId = uploadId;
        this.parts = parts;
    }

    public String getBucket() { return bucket; }
    public String getKey() { return key; }
    public String getUploadId() { return uploadId; }
    public List<PartXml> getParts() { return parts; }
}
