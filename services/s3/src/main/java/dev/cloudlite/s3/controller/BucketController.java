package dev.cloudlite.s3.controller;

import dev.cloudlite.s3.dto.BucketXml;
import dev.cloudlite.s3.dto.ListAllMyBucketsResultXml;
import dev.cloudlite.s3.dto.OwnerXml;
import dev.cloudlite.s3.service.BucketService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BucketController {

    private final BucketService bucketService;

    public BucketController(BucketService bucketService) {
        this.bucketService = bucketService;
    }

    @PutMapping("/{bucket}")
    public ResponseEntity<Void> create(@PathVariable String bucket) {
        bucketService.create(bucket);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/")
    public ResponseEntity<ListAllMyBucketsResultXml> list() {
        List<BucketXml> bucketXmls = bucketService.list().stream()
            .map(b -> new BucketXml(b.getName(), b.getCreatedAt()))
            .toList();
        ListAllMyBucketsResultXml body =
            new ListAllMyBucketsResultXml(new OwnerXml("cloudlite", "cloudlite"), bucketXmls);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(body);
    }

    @RequestMapping(path = "/{bucket}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> head(@PathVariable String bucket) {
        return bucketService.exists(bucket) ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{bucket}")
    public ResponseEntity<Void> delete(@PathVariable String bucket) {
        bucketService.delete(bucket);
        return ResponseEntity.noContent().build();
    }
}
