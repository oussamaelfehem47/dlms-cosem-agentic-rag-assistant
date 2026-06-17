package com.company.dlms.api;

import com.company.dlms.domain.InputClass;
import com.company.dlms.infrastructure.security.AuditService;
import com.company.dlms.workflow.InputUnderstanding;
import com.company.dlms.workflow.InputUnderstandingService;
import com.company.dlms.workflow.StreamingWorkflowService;
import com.company.dlms.workflow.WorkflowRequest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class WorkflowStreamController {

    private final InputValidator inputValidator;
    private final StreamingWorkflowService streamingWorkflowService;
    private final AuditService auditService;
    private final InputUnderstandingService inputUnderstandingService;

    public WorkflowStreamController(
            InputValidator inputValidator,
            StreamingWorkflowService streamingWorkflowService,
            AuditService auditService,
            InputUnderstandingService inputUnderstandingService) {
        this.inputValidator = inputValidator;
        this.streamingWorkflowService = streamingWorkflowService;
        this.auditService = auditService;
        this.inputUnderstandingService = inputUnderstandingService;
    }

    @PostMapping(value = "/decode/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> decodeStream(@RequestBody Mono<WorkflowRequest> requestMono) {
        return prepareRequest(requestMono, "DECODE_REQUEST", "decode")
                .flatMapMany(streamingWorkflowService::streamDecode)
                .onErrorResume(ValidationException.class, ex -> Flux.just(errorDoneEvent(ex)));
    }

    @PostMapping(value = "/siconia/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> siconiaStream(@RequestBody Mono<WorkflowRequest> requestMono) {
        return prepareRequest(requestMono, "SICONIA_REQUEST", "siconia")
                .flatMapMany(streamingWorkflowService::streamSiconia)
                .onErrorResume(ValidationException.class, ex -> Flux.just(errorDoneEvent(ex)));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody Mono<WorkflowRequest> requestMono) {
        return prepareRequest(requestMono, "CHAT_REQUEST", "chat")
                .flatMapMany(streamingWorkflowService::streamChat)
                .onErrorResume(ValidationException.class, ex -> Flux.just(errorDoneEvent(ex)));
    }

    private void logRequest(Authentication auth, String action, String entity) {
        try {
            UUID userId = UUID.fromString((String) auth.getPrincipal());
            auditService.log(action, entity, userId, Map.of()).subscribe();
        } catch (Exception ignored) {
            // Best-effort audit — do not fail the request
        }
    }

    private WorkflowRequest injectRole(WorkflowRequest req, Authentication auth) {
        String role = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .findFirst()
                .orElse("VIEWER");
        return new WorkflowRequest(
                req.sessionId(),
                req.conversationId(),
                req.rawInput(),
                role,
                req.inputClass(),
                req.intentHint(),
                req.strategyMetadata(),
                req.siconiaNormalization(),
                req.dlmsNormalization(),
                req.orchestrationMode()
        );
    }

    private Mono<WorkflowRequest> prepareRequest(
            Mono<WorkflowRequest> requestMono,
            String action,
            String entity
    ) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .flatMap(auth -> requestMono.map(req -> normalizeUnifiedRequest(injectRole(req, auth)))
                        .doOnNext(req -> logRequest(auth, action, entity)))
                .switchIfEmpty(requestMono.map(this::normalizeUnifiedRequest))
                .flatMap(inputValidator::validate);
    }

    private WorkflowRequest normalizeUnifiedRequest(WorkflowRequest req) {
        InputUnderstanding understanding = inputUnderstandingService.understand(
                req.rawInput(),
                req.inputClass() == null ? InputClass.QUERY : req.inputClass()
        );
        return new WorkflowRequest(
                req.sessionId(),
                req.conversationId(),
                req.rawInput(),
                req.userRole(),
                understanding.inputClass(),
                understanding.intent(),
                understanding.strategyMetadata(),
                understanding.siconiaNormalization(),
                understanding.dlmsNormalization(),
                understanding.orchestrationMode()
        );
    }

    private ServerSentEvent<String> errorDoneEvent(ValidationException ex) {
        String data = "{\"type\":\"done\",\"content\":\"Validation failed: " + escape(ex.getMessage()) + "\"}";
        return ServerSentEvent.builder(data).event("done").build();
    }

    private static String escape(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
