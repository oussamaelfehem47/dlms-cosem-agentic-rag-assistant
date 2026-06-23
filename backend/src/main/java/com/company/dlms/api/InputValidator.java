package com.company.dlms.api;

import com.company.dlms.agent.dlms.DlmsInputNormalization;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.decoder.DlmsNormalizedKind;
import com.company.dlms.workflow.WorkflowRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.regex.Pattern;

import org.xml.sax.InputSource;

@Component
public class InputValidator {
    private static final int MAX_ARTIFACTS = 8;
    private static final Pattern HEX_FRAME = Pattern.compile("^[0-9A-Fa-f\\s]+$");
    private static final Pattern HEX_PAYLOAD = Pattern.compile("^[0-9A-Fa-f]+$");
    private static final Pattern ALARM = Pattern.compile("^0x[0-9A-Fa-f]+$");
    private static final Pattern NAMED_ALARM = Pattern.compile("^(?=.*[G-Z_])[A-Z][A-Z0-9_]{2,39}$");
    private static final Pattern OBIS = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){5}$");
    private static final Pattern SCRIPT = Pattern.compile("(?i)(<script|javascript:)");

    public Mono<WorkflowRequest> validate(WorkflowRequest request) {
        return Mono.fromCallable(() -> {
            if (request == null || request.rawInput() == null || request.inputClass() == null) {
                throw new ValidationException("Request, rawInput, and inputClass are required");
            }
            validateArtifacts(request);
            DlmsInputNormalization dlmsNormalization = request.dlmsNormalization();
            if (dlmsNormalization != null) {
                validateDlmsNormalization(dlmsNormalization);
            }

            String input = request.analysisInput();
            InputClass inputClass = request.inputClass();

            switch (inputClass) {
                case HEX_FRAME -> validateHexFrame(input);
                case XML_TRACE -> validateXmlTrace(input);
                case ALARM_CODE -> validateAlarmCode(input);
                case LOG_BLOCK -> validateLogBlock(input);
                case QUERY -> validateQuery(input);
            }
            return request;
        });
    }

    private void validateArtifacts(WorkflowRequest request) {
        if (request.artifacts() == null || request.artifacts().isEmpty()) {
            return;
        }
        if (request.artifacts().size() > MAX_ARTIFACTS) {
            throw new ValidationException("At most 8 artifacts are allowed per turn");
        }
        boolean allBlank = request.artifacts().stream()
                .allMatch(artifact -> artifact == null || artifact.text() == null || artifact.text().isBlank());
        if (allBlank) {
            throw new ValidationException("Artifact payloads are empty");
        }
    }

    private void validateDlmsNormalization(DlmsInputNormalization normalization) {
        if (normalization.ambiguous()) {
            return;
        }
        String input = normalization.normalizedInput();
        if (input == null || input.isBlank()) {
            throw new ValidationException("Normalized DLMS input is empty");
        }
        switch (normalization.kind()) {
            case FRAME_HEX -> validateHexFrame(input);
            case APDU_HEX -> {
                if (input.length() < 10 || input.length() > 20_000 || !HEX_PAYLOAD.matcher(input).matches() || (input.length() & 1) == 1) {
                    throw new ValidationException("Invalid normalized DLMS hex payload");
                }
            }
            case AXDR_HEX -> {
                if (input.length() < 2 || input.length() > 20_000 || !HEX_PAYLOAD.matcher(input).matches() || (input.length() & 1) == 1) {
                    throw new ValidationException("Invalid normalized DLMS AXDR payload");
                }
            }
            case OBIS_QUERY -> {
                if (!OBIS.matcher(input).matches()) {
                    throw new ValidationException("Invalid normalized OBIS query");
                }
            }
        }
    }

    private void validateHexFrame(String input) {
        if (input.length() < 10 || input.length() > 10_000 || !HEX_FRAME.matcher(input).matches()) {
            throw new ValidationException("Invalid HEX_FRAME input");
        }
    }

    private void validateXmlTrace(String input) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            factory.newDocumentBuilder().parse(new InputSource(new StringReader(input)));
        } catch (Exception e) {
            throw new ValidationException("Invalid XML_TRACE input");
        }
    }

    private void validateAlarmCode(String input) {
        if (!ALARM.matcher(input).matches() && !NAMED_ALARM.matcher(input).matches()) {
            throw new ValidationException("Invalid ALARM_CODE input");
        }
    }

    private void validateLogBlock(String input) {
        if (input.length() > 50_000) {
            throw new ValidationException("Invalid LOG_BLOCK input");
        }
    }

    private void validateQuery(String input) {
        if (input.length() > 2_000 || SCRIPT.matcher(input).find()) {
            throw new ValidationException("Invalid QUERY input");
        }
    }
}
