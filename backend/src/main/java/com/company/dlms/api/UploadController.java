package com.company.dlms.api;

import com.company.dlms.infrastructure.upload.FileUploadService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api")
public class UploadController {

    private final FileUploadService fileUploadService;

    public UploadController(FileUploadService fileUploadService) {
        this.fileUploadService = fileUploadService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<UploadResponse>> upload(@RequestPart("file") FilePart filePart) {
        return fileUploadService.extract(filePart)
                .map(result -> ResponseEntity.ok(new UploadResponse(
                        result.text(),
                        result.inputClass(),
                        result.type(),
                        result.suggestedEndpoint()
                )));
    }

    public record UploadResponse(
            String text,
            String input_class,
            String type,
            String suggested_endpoint
    ) {}
}
