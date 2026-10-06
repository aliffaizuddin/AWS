package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@JacksonXmlRootElement(localName = "CompleteMultipartUploadResult")
public class CompleteMultipartUploadResultXml {

    @JacksonXmlProperty(localName = "Location")
    private final String location;

    @JacksonXmlProperty(localName = "Bucket")
    private final String bucket;

    @JacksonXmlProperty(localName = "Key")
    private final String key;

    @JacksonXmlProperty(localName = "ETag")
    private final String etag;

    public CompleteMultipartUploadResultXml(String location, String bucket, String key, String etag) {
        this.location = location;
        this.bucket = bucket;
        this.key = key;
        this.etag = etag;
    }

    public String getLocation() { return location; }
    public String getBucket() { return bucket; }
    public String getKey() { return key; }
    public String getEtag() { return etag; }
}
