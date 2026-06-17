package com.company.dlms.api;

import com.company.dlms.domain.InputClass;
import com.company.dlms.workflow.WorkflowRequest;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class InputValidatorTest {

    private final InputValidator validator = new InputValidator();

    @Test
    void validHexFramePasses() {
        WorkflowRequest request = new WorkflowRequest("s", "c", "7EA023210313A5E57E", "viewer", InputClass.HEX_FRAME);
        StepVerifier.create(validator.validate(request))
                .expectNext(request)
                .verifyComplete();
    }

    @Test
    void hexFrameWithNonHexCharsThrowsValidationException() {
        WorkflowRequest request = new WorkflowRequest("s", "c", "7EA023ZZZZ", "viewer", InputClass.HEX_FRAME);
        StepVerifier.create(validator.validate(request))
                .expectError(ValidationException.class)
                .verify();
    }

    @Test
    void xmlWithoutDtdPasses() {
        WorkflowRequest request = new WorkflowRequest("s", "c", "<root><a>ok</a></root>", "viewer", InputClass.XML_TRACE);
        StepVerifier.create(validator.validate(request))
                .expectNext(request)
                .verifyComplete();
    }

    @Test
    void xmlWithDoctypeThrowsValidationException() {
        String xml = "<!DOCTYPE foo [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]><root>&xxe;</root>";
        WorkflowRequest request = new WorkflowRequest("s", "c", xml, "viewer", InputClass.XML_TRACE);
        StepVerifier.create(validator.validate(request))
                .expectError(ValidationException.class)
                .verify();
    }

    @Test
    void alarmCodePasses() {
        WorkflowRequest request = new WorkflowRequest("s", "c", "0x1342", "viewer", InputClass.ALARM_CODE);
        StepVerifier.create(validator.validate(request))
                .expectNext(request)
                .verifyComplete();
    }

    @Test
    void namedAlarmCodePasses() {
        WorkflowRequest request = new WorkflowRequest("s", "c", "DCU_COMM_FAIL", "viewer", InputClass.ALARM_CODE);
        StepVerifier.create(validator.validate(request))
                .expectNext(request)
                .verifyComplete();
    }

    @Test
    void queryExceeding2000ThrowsValidationException() {
        WorkflowRequest request = new WorkflowRequest("s", "c", "q".repeat(2001), "viewer", InputClass.QUERY);
        StepVerifier.create(validator.validate(request))
                .expectError(ValidationException.class)
                .verify();
    }
}
