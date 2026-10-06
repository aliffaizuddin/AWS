package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@JacksonXmlRootElement(localName = "InitiateMultipartUploadResult")
public class InitiateMultipartUploadResultXml {

    @JacksonXmlProperty(localName = "Bucket")
    private final String bucket;

    @JacksonXmlProperty(localName = "Key")
    private final String key;

    @JacksonXmlProperty(localName = "UploadId")
    private final String uploadId;

    public InitiateMultipartUploadResultXml(String bucket, String key, String uploadId) {
        this.bucket = bucket;
        this.key = key;
        this.uploadId = uploadId;
    }

    public String getBucket() { return bucket; }
    public String getKey() { return key; }
    public String getUploadId() { return uploadId; }
}
