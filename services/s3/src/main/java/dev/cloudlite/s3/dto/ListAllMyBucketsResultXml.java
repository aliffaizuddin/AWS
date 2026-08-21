package dev.cloudlite.s3.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.util.List;

@JacksonXmlRootElement(localName = "ListAllMyBucketsResult")
public class ListAllMyBucketsResultXml {

    @JacksonXmlProperty(localName = "Owner")
    private final OwnerXml owner;

    @JacksonXmlElementWrapper(localName = "Buckets")
    @JacksonXmlProperty(localName = "Bucket")
    private final List<BucketXml> buckets;

    public ListAllMyBucketsResultXml(OwnerXml owner, List<BucketXml> buckets) {
        this.owner = owner;
        this.buckets = buckets;
    }

    public OwnerXml getOwner() {
        return owner;
    }

    public List<BucketXml> getBuckets() {
        return buckets;
    }
}
