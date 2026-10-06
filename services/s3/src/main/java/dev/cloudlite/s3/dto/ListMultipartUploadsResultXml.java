package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.util.List;

@JacksonXmlRootElement(localName = "ListMultipartUploadsResult")
public class ListMultipartUploadsResultXml {

    @JacksonXmlProperty(localName = "Bucket")
    private final String bucket;

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "Upload")
    private final List<UploadXml> uploads;

    public ListMultipartUploadsResultXml(String bucket, List<UploadXml> uploads) {
        this.bucket = bucket;
        this.uploads = uploads;
    }

    public String getBucket() { return bucket; }
    public List<UploadXml> getUploads() { return uploads; }
}
