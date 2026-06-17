package com.company.dlms.infrastructure.upload;

import com.company.dlms.agent.RouterAgent;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.RouterResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

@Service
public class FileUploadService {

    private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    private static final int MAX_QUERY_CHARS = 2_000;
    private static final int MAX_HEX_CHARS = 10_000;
    private static final int MAX_LOG_CHARS = 50_000;
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "hex", "xml", "log");
    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of("pdf", "docx");

    private final RouterAgent routerAgent;

    public FileUploadService(RouterAgent routerAgent) {
        this.routerAgent = routerAgent;
    }

    public Mono<UploadPayload> extract(FilePart filePart) {
        String filename = sanitizeFilename(filePart.filename());
        String extension = extensionOf(filename);

        if (!isSupportedExtension(extension)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported file type"));
        }

        return DataBufferUtils.join(filePart.content(), MAX_UPLOAD_BYTES)
                .onErrorMap(DataBufferLimitException.class,
                        ex -> new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File too large (max 10 MB)"))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Uploaded file is empty")))
                .map(this::toByteArray)
                .flatMap(bytes -> Mono.fromCallable(() -> buildPayload(filename, extension, filePart, bytes))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    UploadPayload buildPayload(String filename, String extension, FilePart filePart, byte[] bytes) {
        String extractedText = switch (extension) {
            case "txt", "hex", "xml", "log" -> new String(bytes, StandardCharsets.UTF_8);
            case "pdf" -> extractPdfText(bytes);
            case "docx" -> extractDocxText(bytes);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported file type");
        };

        String normalizedText = normalizeExtractedText(extractedText);
        if (normalizedText.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No content could be extracted from file");
        }

        RouterResult route = routerAgent.route(normalizedText);
        InputClass inputClass = route.inputClass();
        String constrainedText = constrainForWorkflow(normalizedText, inputClass);
        String type = resolveMimeType(extension, filePart);

        return new UploadPayload(
                constrainedText,
                inputClass.name().toLowerCase(Locale.ROOT),
                type,
                resolveSuggestedEndpoint(inputClass)
        );
    }

    private byte[] toByteArray(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private String extractPdfText(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not extract text from PDF");
        }
    }

    private String extractDocxText(byte[] bytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not extract text from DOCX");
        }
    }

    private String normalizeExtractedText(String text) {
        return text
                .replace("\u0000", "")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String constrainForWorkflow(String text, InputClass inputClass) {
        return switch (inputClass) {
            case QUERY -> limit(text, MAX_QUERY_CHARS);
            case HEX_FRAME -> limit(text, MAX_HEX_CHARS);
            case ALARM_CODE -> limit(text, 128);
            case LOG_BLOCK -> limit(text, MAX_LOG_CHARS);
            case XML_TRACE -> text;
        };
    }

    private String limit(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars).trim();
    }

    private String resolveSuggestedEndpoint(InputClass inputClass) {
        return switch (inputClass) {
            case XML_TRACE, ALARM_CODE, LOG_BLOCK -> "siconia";
            case HEX_FRAME, QUERY -> "decode";
        };
    }

    private boolean isSupportedExtension(String extension) {
        return TEXT_EXTENSIONS.contains(extension) || DOCUMENT_EXTENSIONS.contains(extension);
    }

    private String resolveMimeType(String extension, FilePart filePart) {
        MediaType contentType = filePart.headers().getContentType();
        if (contentType != null && !MediaType.APPLICATION_OCTET_STREAM.equals(contentType)) {
            return contentType.toString();
        }

        return switch (extension) {
            case "xml" -> MediaType.APPLICATION_XML_VALUE;
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> MediaType.TEXT_PLAIN_VALUE;
        };
    }

    private String extensionOf(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot < 0 || lastDot == filename.length() - 1) {
            return "";
        }
        return filename.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        String normalized = filename.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
    }

    public record UploadPayload(
            String text,
            String inputClass,
            String type,
            String suggestedEndpoint
    ) {}
}
