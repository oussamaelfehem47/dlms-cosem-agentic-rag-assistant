package com.company.dlms.infrastructure.upload;

import com.company.dlms.agent.RouterAgent;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileUploadServiceTest {

    private final FileUploadService fileUploadService = new FileUploadService(new RouterAgent());

    @Test
    void buildPayload_extractsPdfText() throws Exception {
        byte[] pdfBytes;
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText("What is OBIS 1.0.1.8.0.255?");
                stream.endText();
            }
            document.save(out);
            pdfBytes = out.toByteArray();
        }

        FilePart filePart = mockFilePart(MediaType.APPLICATION_PDF);
        FileUploadService.UploadPayload payload = fileUploadService.buildPayload("sample.pdf", "pdf", filePart, pdfBytes);

        assertThat(payload.text()).contains("OBIS 1.0.1.8.0.255");
        assertThat(payload.inputClass()).isEqualTo("query");
        assertThat(payload.suggestedEndpoint()).isEqualTo("decode");
    }

    @Test
    void buildPayload_extractsDocxText() throws Exception {
        byte[] docxBytes;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText("2024-01-15 10:30:00 [PLC] [ERROR] Connection lost to meter 12345");
            document.write(out);
            docxBytes = out.toByteArray();
        }

        FilePart filePart = mockFilePart(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        FileUploadService.UploadPayload payload = fileUploadService.buildPayload("sample.docx", "docx", filePart, docxBytes);

        assertThat(payload.text()).contains("Connection lost to meter 12345");
        assertThat(payload.suggestedEndpoint()).isEqualTo("siconia");
    }

    private FilePart mockFilePart(MediaType mediaType) {
        FilePart filePart = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        when(filePart.headers()).thenReturn(headers);
        return filePart;
    }
}
