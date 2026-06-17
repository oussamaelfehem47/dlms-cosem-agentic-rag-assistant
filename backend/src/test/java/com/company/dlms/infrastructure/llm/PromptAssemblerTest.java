package com.company.dlms.infrastructure.llm;

import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.InputClass;
import com.company.dlms.domain.answer.GroundedAnswerContext;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.DlmsNormalizedKind;
import com.company.dlms.domain.decoder.DlmsProcessingMetadata;
import com.company.dlms.domain.decoder.FrameType;
import com.company.dlms.domain.decoder.HdlcFrame;
import com.company.dlms.domain.decoder.UFrameType;
import com.company.dlms.domain.orchestration.OrchestrationMode;
import com.company.dlms.domain.profile.ProfileCell;
import com.company.dlms.domain.profile.ProfileColumn;
import com.company.dlms.domain.profile.ProfileResult;
import com.company.dlms.domain.profile.ProfileRow;
import com.company.dlms.domain.profile.ProfileType;
import com.company.dlms.domain.rag.DocumentChunk;
import com.company.dlms.domain.rag.RetrievalResult;
import com.company.dlms.domain.rag.SourceCitation;
import com.company.dlms.domain.orchestration.StrategyKey;
import com.company.dlms.domain.orchestration.ToolTraceEntry;
import com.company.dlms.domain.siconia.AffectedComponent;
import com.company.dlms.domain.siconia.AlarmDecodeResult;
import com.company.dlms.domain.siconia.AlarmSeverity;
import com.company.dlms.domain.siconia.IssueCategory;
import com.company.dlms.domain.siconia.LogAnalysis;
import com.company.dlms.domain.siconia.LogLayer;
import com.company.dlms.domain.siconia.LogSeverity;
import com.company.dlms.domain.siconia.ParseProvenance;
import com.company.dlms.domain.siconia.SiconiaResult;
import com.company.dlms.domain.siconia.SiconiaProcessingMetadata;
import com.company.dlms.domain.reflection.PromptAdaptation;
import com.company.dlms.infrastructure.reflection.AdaptivePromptService;
import com.company.dlms.workflow.WorkflowState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PromptAssemblerTest {

    private final AdaptivePromptService adaptivePromptService = new AdaptivePromptService(null) {
        @Override
        public Map<String, PromptAdaptation> getAdaptations() {
            return Map.of();
        }
    };
    private final PromptAssembler promptAssembler = new PromptAssembler(adaptivePromptService, new GroundedFactBundleBuilder());

    @Test
    void systemPrompt_usesSecuritySpecificInstructionsForSecurityIntent() {
        WorkflowState state = WorkflowState.empty("s1", "c1", "Explain HLS authentication")
                .toBuilder()
                .intent(DlmsIntent.SECURITY_EXPLAIN)
                .build();

        String prompt = promptAssembler.systemPrompt(state);

        assertThat(prompt).contains("explaining DLMS/COSEM security mechanisms");
        assertThat(prompt).contains("NEVER output actual keys, passwords, challenge values, live frame-counter values, or attack steps.");
        assertThat(prompt).contains("LLS uses a shared static secret");
        assertThat(prompt).contains("frame/invocation counter must advance monotonically");
    }

    @Test
    void assemble_omitsSecurityContextWhenNoSummaryPresent() {
        WorkflowState state = WorkflowState.empty("s2", "c2", "Explain HLS authentication")
                .toBuilder()
                .intent(DlmsIntent.SECURITY_EXPLAIN)
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("=== SECURITY EXPLAINER MODE ===");
        assertThat(prompt).doesNotContain("=== SECURITY CONTEXT ===");
    }

    @Test
    void assemble_rendersSanitizedSecurityContextWithoutRawFrameCounter() {
        WorkflowState state = WorkflowState.empty("s3", "c3", "Explain the security applied here")
                .toBuilder()
                .intent(DlmsIntent.SECURITY_EXPLAIN)
                .frameCounter("12345678")
                .frameCounterHex("00BC614E")
                .securitySuite("1")
                .securityContextSummary("""
- Security Suite: Suite 1 (authentication + encryption, AES-GCM-128)
- Frame Counter: present
- Ciphered APDU: yes (GLO_GET_REQUEST)
- AARE Diagnostic: 6
""")
                .decodeResult(new DecodeResult(
                        new HdlcFrame(FrameType.I_FRAME, null, null, 16, 1, new byte[0], true, new byte[0]),
                        ApduType.GLO_GET_REQUEST,
                        null,
                        List.of(),
                        false,
                        "7EA023210313A5E500BC614E7E",
                        List.of()
                ))
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("=== SECURITY CONTEXT ===");
        assertThat(prompt).contains("AARE Diagnostic: 6");
        assertThat(prompt).contains("Frame Counter: present");
        assertThat(prompt).doesNotContain("12345678");
        assertThat(prompt).doesNotContain("00BC614E");
        assertThat(prompt).doesNotContain("Raw Hex:");
    }

    @Test
    void assemble_includesGroundedAnswerContextForDeterministicDecode() {
        DecodeResult decodeResult = new DecodeResult(
                new HdlcFrame(FrameType.U_FRAME, null, null, 16, 1, new byte[0], true, new byte[0]),
                ApduType.UNKNOWN,
                null,
                List.of(),
                false,
                "7EA0",
                List.of()
        );
        WorkflowState state = WorkflowState.empty("sga1", "cga1", "7EA0")
                .toBuilder()
                .decodeResult(decodeResult)
                .groundedAnswerContext(GroundedAnswerContext.deterministicDecode(
                        StrategyKey.DLMS_FRAME_DECODE,
                        decodeResult,
                        List.of("FCS verified"),
                        List.of(),
                        0.96,
                        false
                ))
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("=== GROUNDED ANSWER CONTEXT ===");
        assertThat(prompt).contains("Answer Mode: DETERMINISTIC_DECODE");
        assertThat(prompt).contains("Selected Strategy: DLMS_FRAME_DECODE");
        assertThat(prompt).contains("Use the deterministic decode result as primary evidence");
        assertThat(prompt).contains("Warnings: FCS verified");
        assertThat(prompt).contains("For SNRM, describe it as a link-setup request.");
        assertThat(prompt).contains("When a GET_RESPONSE only identifies an OBIS/object reference or returned structure");
    }

    @Test
    void assemble_includesGroundedAnswerContextForRetrievalSecurity() {
        RetrievalResult retrieval = new RetrievalResult(
                new DocumentChunk(
                        "doc-1",
                        "HLS uses challenge-response authentication.",
                        new SourceCitation("dlms", "blue-book.pdf", 0, "General", null, null, 1.0, "DLMS Standard — §Security")
                ),
                1.0,
                1.0,
                1.0
        );
        WorkflowState state = WorkflowState.empty("sga2", "cga2", "Explain HLS authentication")
                .toBuilder()
                .intent(DlmsIntent.SECURITY_EXPLAIN)
                .retrievalResults(List.of(retrieval))
                .groundedAnswerContext(GroundedAnswerContext.retrievalSecurity(
                        List.of(retrieval),
                        List.of(),
                        0.74
                ))
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("Answer Mode: RETRIEVAL_SECURITY");
        assertThat(prompt).contains("Selected Strategy: SECURITY_EXPLAIN");
        assertThat(prompt).contains("Use retrieved documentation as the primary evidence");
        assertThat(prompt).contains("This section is authoritative for Answer Mode RETRIEVAL_SECURITY.");
        assertThat(prompt).contains("Use the RELEVANT DOCUMENTATION section as mandatory primary evidence for the final answer.");
        assertThat(prompt).contains("=== AUTHORITATIVE FACT BUNDLE ===");
        assertThat(prompt).contains("Topic Family: SECURITY_HLS");
        assertThat(prompt).contains("HLS is DLMS/COSEM high-level security based on challenge-response proof");
    }

    @Test
    void assemble_includesModeDrivenProtocolPolicyForRetrievalDocs() {
        RetrievalResult retrieval = new RetrievalResult(
                new DocumentChunk(
                        "doc-2",
                        "The Green Book defines the system architecture, terms, and transport profiles for DLMS/COSEM.",
                        new SourceCitation("dlms", "green-book.pdf", 0, "General", null, null, 1.0, "DLMS Standard â€” Â§Green Book")
                ),
                1.0,
                1.0,
                1.0
        );
        WorkflowState state = WorkflowState.empty("sga3", "cga3", "What is the DLMS Green Book?")
                .toBuilder()
                .intent(DlmsIntent.DOCUMENTATION)
                .retrievalResults(List.of(retrieval))
                .groundedAnswerContext(GroundedAnswerContext.retrievalDocs(
                        List.of(retrieval),
                        List.of(),
                        0.69
                ))
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("Answer Mode: RETRIEVAL_DOCS");
        assertThat(prompt).contains("Selected Strategy: DOCUMENTATION");
        assertThat(prompt).contains("This section is authoritative for Answer Mode RETRIEVAL_DOCS.");
        assertThat(prompt).contains("Use the RELEVANT DOCUMENTATION section as mandatory primary evidence for the final answer.");
        assertThat(prompt).contains("=== AUTHORITATIVE FACT BUNDLE ===");
        assertThat(prompt).contains("Topic Family: STANDARDS_BOOK");
        assertThat(prompt).contains("The DLMS Green Book defines the DLMS/COSEM system architecture");
    }

    @Test
    void assemble_includesAssociationFactBundleForAarqVsAareQuestion() {
        WorkflowState state = WorkflowState.empty("sga4", "cga4", "What is the difference between AARQ and AARE in DLMS?")
                .toBuilder()
                .intent(DlmsIntent.DOCUMENTATION)
                .groundedAnswerContext(GroundedAnswerContext.retrievalDocs(
                        List.of(),
                        List.of(),
                        0.72
                ))
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("=== AUTHORITATIVE FACT BUNDLE ===");
        assertThat(prompt).contains("Topic Family: ASSOCIATION_APDUS");
        assertThat(prompt).contains("AARQ is the Association Request APDU sent by the COSEM client");
        assertThat(prompt).contains("AARE is the Association Response APDU sent by the COSEM server");
    }

    @Test
    void assemble_includesProfileSectionWhenPresent() {
        WorkflowState state = WorkflowState.empty("s4", "c4", "Explain this profile")
                .toBuilder()
                .profileResult(new ProfileResult(
                        ProfileType.LOAD_PROFILE,
                        List.of(
                                new ProfileColumn(0, "0.0.1.0.0.255", "Clock", 8, 2, null, null),
                                new ProfileColumn(1, "1.0.1.8.0.255", "Active Energy Delivered", 3, 2, "kWh", -3)
                        ),
                        List.of(new ProfileRow(
                                "2026-05-07 12:30:00",
                                List.of(
                                        new ProfileCell(0, "2026-05-07 12:30:00", null, "2026-05-07 12:30:00", null),
                                        new ProfileCell(1, 12345, new BigDecimal("12.345"), "12.345 kWh", "kWh")
                                )
                        )),
                        2,
                        1,
                        "1.0.99.1.0.255"
                ))
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("=== PROFILE DATA ===");
        assertThat(prompt).contains("Profile Type: LOAD_PROFILE");
        assertThat(prompt).contains("1.0.1.8.0.255");
        assertThat(prompt).contains("12.345 kWh");
    }

    @Test
    void assemble_usesCompactGreetingInstructions() {
        WorkflowState state = WorkflowState.empty("s5", "c5", "hello");

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("=== CASUAL GREETING DETECTED ===");
        assertThat(prompt).contains("Respond in 1-2 short lines only.");
        assertThat(prompt).contains("Do not list capabilities as bullets.");
        assertThat(prompt).doesNotContain("Then explain you can help with:");
    }

    @Test
    void assemble_doesNotTreatTechnicalGreetingAsCasualOnly() {
        WorkflowState state = WorkflowState.empty("s5b", "c5b", "hello, explain HDLC")
                .toBuilder()
                .intent(DlmsIntent.DOCUMENTATION)
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).doesNotContain("=== CASUAL GREETING DETECTED ===");
        assertThat(prompt).contains("=== PROTOCOL ANSWER STYLE ===");
    }

    @Test
    void assemble_addsCompactInvalidDecodeShape() {
        WorkflowState state = WorkflowState.empty("s6", "c6", "Decode this frame")
                .toBuilder()
                .decodeResult(new DecodeResult(
                        new HdlcFrame(FrameType.I_FRAME, null, null, 16, 1, new byte[0], true, new byte[0]),
                        ApduType.UNKNOWN,
                        null,
                        List.of(),
                        false,
                        "7EA0210002002303F17B2B80C401C100",
                        List.of("Unknown APDU"),
                        null
                ))
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("Preferred response shape:");
        assertThat(prompt).contains("What happened: the deterministic parser could not identify a valid DLMS payload");
        assertThat(prompt).contains("Can I trust it: only the raw frame metadata is safe to cite");
        assertThat(prompt).contains("Next step: verify frame boundaries, encryption state, and capture integrity");
    }

    @Test
    void assemble_addsCompactSiconiaShape() {
        WorkflowState state = WorkflowState.empty("s7", "c7", "0x1342")
                .toBuilder()
                .siconiaResult(new SiconiaResult(
                        null,
                        List.of(new AlarmDecodeResult(
                                "0x1342",
                                AlarmSeverity.HIGH,
                                "DCU comm failure",
                                "Check DCU-HES link",
                                AffectedComponent.HES
                        )),
                        new LogAnalysis(
                                LogLayer.PLC,
                                LogSeverity.ERROR,
                                Set.of(IssueCategory.CONNECTIVITY),
                                3,
                                1
                        ),
                        "ALARM_CODE"
                ))
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("What it means: summarize the alarm code, severity, and affected component");
        assertThat(prompt).contains("Impact: use the decoded root cause without repeating the full alarm table; if the root cause is a communication failure");
        assertThat(prompt).contains("Next step: give the remediation directly from the decoded alarm data");
        assertThat(prompt).contains("What it means: summarize the dominant layer and main issue categories");
        assertThat(prompt).contains("Impact: state the operational effect of the highest-severity pattern");
        assertThat(prompt).contains("Next step: give the most relevant remediation or check");
    }

    @Test
    void assemble_addsProtocolAnswerStyleForGeneralApduQuestion() {
        WorkflowState state = WorkflowState.empty("s8", "c8", "Explain the APDU structure in DLMS")
                .toBuilder()
                .intent(DlmsIntent.APDU_ANALYSIS)
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("=== PROTOCOL ANSWER STYLE ===");
        assertThat(prompt).contains("Never describe HDLC as Ethernet.");
        assertThat(prompt).contains("Do not mention physical-layer checksums when defining APDU");
    }

    @Test
    void assemble_requiresExactDocumentationCitationLabels() {
        WorkflowState state = WorkflowState.empty("s9", "c9", "What is Local operations?")
                .toBuilder()
                .intent(DlmsIntent.DOCUMENTATION)
                .retrievalResults(List.of(
                        new RetrievalResult(
                                new DocumentChunk(
                                        "doc-1",
                                        "Local operations content",
                                        new SourceCitation(
                                                "confluence",
                                                "Local-operations_408492990.html",
                                                0,
                                                "General",
                                                "Local operations",
                                                "SPL",
                                                1.0,
                                                "Confluence — Local operations (SPL)"
                                        )
                                ),
                                1.0,
                                1.0,
                                1.0
                        )
                ))
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("Use the exact citation label shown for the supporting excerpt");
        assertThat(prompt).contains("Never replace citations with placeholders like [Documentation]");
    }

    @Test
    void assemble_documentationPromptForbidsMathMarkupAndRequiresDirectDefinitionFromExcerpt() {
        WorkflowState state = WorkflowState.empty("s10", "c10", "Explain Local operations")
                .toBuilder()
                .intent(DlmsIntent.DOCUMENTATION)
                .retrievalResults(List.of(
                        new RetrievalResult(
                                new com.company.dlms.domain.rag.DocumentChunk(
                                        "doc-1",
                                        "Local operations are procedures used to run SPL services locally for troubleshooting and validation.",
                                        new SourceCitation(
                                                "confluence",
                                                "Local-operations_408492990.html",
                                                0,
                                                "General",
                                                "Local operations",
                                                "SPL",
                                                1.0,
                                                "Confluence â€” Local operations (SPL)"
                                        )
                                ),
                                1.0,
                                1.0,
                                1.0
                        )
                ))
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("Use markdown for readability.");
        assertThat(prompt).contains("Do not emit raw HTML.");
        assertThat(prompt).contains("Do not emit LaTeX or math markup such as \\boxed{}.");
        assertThat(prompt).contains("Define the topic directly from the retrieved excerpt");
        assertThat(prompt).contains("Base the first sentence on the strongest supporting excerpt body");
        assertThat(prompt).contains("If the top supporting excerpt is a Confluence page title");
    }

    @Test
    void assemble_protocolDefinitionQuestionsDemandDirectStandardsGroundedDefinition() {
        WorkflowState hdlc = WorkflowState.empty("s10b", "c10b", "Explain HDLC frame structure")
                .toBuilder()
                .intent(DlmsIntent.DOCUMENTATION)
                .build();

        WorkflowState greenBook = WorkflowState.empty("s10c", "c10c", "What is the DLMS Green Book?")
                .toBuilder()
                .intent(DlmsIntent.DOCUMENTATION)
                .build();

        String hdlcPrompt = promptAssembler.assemble(hdlc);
        String greenBookPrompt = promptAssembler.assemble(greenBook);

        assertThat(hdlcPrompt).contains("Define the topic directly from the retrieved excerpt");
        assertThat(hdlcPrompt).contains("Base the first sentence on the strongest supporting excerpt body");
        assertThat(hdlcPrompt).contains("Answer directly and technically.");

        assertThat(greenBookPrompt).contains("Define the topic directly from the retrieved excerpt");
        assertThat(greenBookPrompt).contains("Base the first sentence on the strongest supporting excerpt body");
        assertThat(greenBookPrompt).contains("Use markdown for readability.");
    }

    @Test
    void assemble_securityExplainGuidanceCoversAssociationRejectDiagnostics() {
        WorkflowState state = WorkflowState.empty("s10d", "c10d", "AARE association rejected, diagnostic 6 - what does this usually mean?")
                .toBuilder()
                .intent(DlmsIntent.SECURITY_EXPLAIN)
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("For association reject diagnostics, explain the likely reason for the rejection");
        assertThat(prompt).contains("map the diagnostic code only when the supporting context justifies it");
        assertThat(prompt).contains("Give practical troubleshooting checks for authentication, application context, and negotiated security mismatches");
    }

    @Test
    void assemble_securityExplainPromptRequiresDetailedGroundedSecurityAnswers() {
        WorkflowState state = WorkflowState.empty("s10da", "c10da", "Explain HLS authentication in DLMS/COSEM")
                .toBuilder()
                .intent(DlmsIntent.SECURITY_EXPLAIN)
                .groundedAnswerContext(GroundedAnswerContext.retrievalSecurity(
                        List.of(),
                        List.of(),
                        0.74
                ))
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("This section is authoritative for Answer Mode RETRIEVAL_SECURITY.");
        assertThat(prompt).contains("Answer directly and specifically using the retrieved text");
        assertThat(prompt).contains("For security topics: name the specific mechanism, level, and");
        assertThat(prompt).contains("algorithm involved (e.g., HLS Level 3, GMAC, AES-GCM-128)");
        assertThat(prompt).contains("For HLS: describe the 5-step challenge-response sequence");
        assertThat(prompt).contains("For security suites: state exactly what each suite provides");
        assertThat(prompt).contains("3-5 sentences minimum for any security or protocol explanation");
        assertThat(prompt).contains("Start directly with a clean answer in your own words.");
    }

    @Test
    void assemble_documentationGuidanceForHdlcStructureStaysConceptual() {
        WorkflowState state = WorkflowState.empty("s10e", "c10e", "Explain HDLC frame structure")
                .toBuilder()
                .intent(DlmsIntent.DOCUMENTATION)
                .groundedAnswerContext(GroundedAnswerContext.retrievalDocs(
                        List.of(),
                        List.of(),
                        0.68
                ))
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("This section is authoritative for Answer Mode RETRIEVAL_DOCS.");
        assertThat(prompt).contains("For conceptual HDLC structure questions, describe the general frame layout");
        assertThat(prompt).contains("Do not invent a specific frame subtype or sample instance unless the user supplied one");
    }

    @Test
    void assemble_documentationPromptRequiresDetailedGroundedProtocolAnswers() {
        WorkflowState state = WorkflowState.empty("s10ea", "c10ea", "What is security suite 1?")
                .toBuilder()
                .intent(DlmsIntent.DOCUMENTATION)
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("Answer directly and specifically using the retrieved text");
        assertThat(prompt).contains("For security topics: name the specific mechanism, level, and");
        assertThat(prompt).contains("algorithm involved (e.g., HLS Level 3, GMAC, AES-GCM-128)");
        assertThat(prompt).contains("For security suites: state exactly what each suite provides");
        assertThat(prompt).contains("Do not give a generic summary - give technical detail");
        assertThat(prompt).contains("3-5 sentences minimum for any security or protocol explanation");
    }

    @Test
    void assemble_withActiveAdaptation_includesAdditionalInstruction() {
        PromptAdaptation adaptation = new PromptAdaptation("FRAME_DECODE", "Provide byte-level breakdown.", 0.45, true);
        AdaptivePromptService svc = new AdaptivePromptService(null) {
            @Override
            public Map<String, PromptAdaptation> getAdaptations() {
                return Map.of("FRAME_DECODE", adaptation);
            }
        };
        PromptAssembler assembler = new PromptAssembler(svc, new GroundedFactBundleBuilder());

        WorkflowState state = WorkflowState.empty("s11", "c11", "Decode frame")
                .toBuilder()
                .intent(DlmsIntent.FRAME_DECODE)
                .build();

        String prompt = assembler.assemble(state);

        assertThat(prompt).contains("ADDITIONAL INSTRUCTION FOR THIS QUERY TYPE:");
        assertThat(prompt).contains("Provide byte-level breakdown.");
    }

    @Test
    void assemble_withNoActiveAdaptation_noAdditionalInstruction() {
        WorkflowState state = WorkflowState.empty("s12", "c12", "Decode frame")
                .toBuilder()
                .intent(DlmsIntent.FRAME_DECODE)
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).doesNotContain("ADDITIONAL INSTRUCTION FOR THIS QUERY TYPE:");
    }

    @Test
    void assemble_dlmsHeuristicProcessingGuidanceMentionsRecoveredDeterministicPayload() {
        WorkflowState state = WorkflowState.empty("s12b", "c12b", "Decode APDU C4020109060100010800FF")
                .toBuilder()
                .intent(DlmsIntent.APDU_ANALYSIS)
                .decodeResult(new DecodeResult(
                        new HdlcFrame(FrameType.I_FRAME, null, null, 0, 0, null, true, new byte[0]),
                        ApduType.GET_RESPONSE,
                        null,
                        List.of(),
                        false,
                        "C4020109060100010800FF",
                        List.of(),
                        new DlmsProcessingMetadata(
                                DlmsNormalizedKind.APDU_HEX,
                                ParseProvenance.STRUCTURED_HEURISTIC,
                                List.of(),
                                "Recovered APDU payload from wrapped prose input"
                        )
                ))
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("Processing Provenance: STRUCTURED_HEURISTIC");
        assertThat(prompt).contains("Processing Kind: APDU_HEX");
        assertThat(prompt).contains("Use the recovered deterministic payload as primary evidence");
        assertThat(prompt).contains("Do not overstate certainty about the wrapper text");
    }

    @Test
    void assemble_structuredPlusAgenticDecodeWithEmptyDocsForbidsProtocolSpeculation() {
        DecodeResult decodeResult = new DecodeResult(
                new HdlcFrame(FrameType.U_FRAME, UFrameType.SNRM, null, 1, 1, null, true, new byte[0]),
                ApduType.UNKNOWN,
                null,
                List.of(),
                false,
                "7EA00A030383CD6F7E",
                List.of(),
                new DlmsProcessingMetadata(
                        DlmsNormalizedKind.FRAME_HEX,
                        ParseProvenance.STRUCTURED_DIRECT,
                        List.of(),
                        null
                )
        );

        WorkflowState state = WorkflowState.empty("s12bb", "c12bb", "7EA00A030383CD6F7E what does this do?")
                .toBuilder()
                .intent(DlmsIntent.FRAME_DECODE)
                .inputClass(InputClass.QUERY)
                .orchestrationMode(OrchestrationMode.STRUCTURED_PLUS_AGENTIC)
                .plannerUsed(true)
                .toolTrace(List.of(new ToolTraceEntry(
                        "search_docs",
                        "No supporting documentation snippets were recovered.",
                        false,
                        "RETRIEVAL"
                )))
                .decodeResult(decodeResult)
                .groundedAnswerContext(GroundedAnswerContext.deterministicDecode(
                        StrategyKey.DLMS_FRAME_DECODE,
                        decodeResult,
                        List.of(),
                        List.of(),
                        0.92,
                        false
                ))
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("You have no retrieved documentation for this frame.");
        assertThat(prompt).contains("Explain ONLY what the decode result shows.");
        assertThat(prompt).contains("Do NOT describe what happens next in the protocol.");
        assertThat(prompt).contains("Do NOT speculate about SRR, ACK, RCV, or server responses.");
    }

    @Test
    void systemPromptAddsDecodeTruthLockGuidance() {
        String prompt = promptAssembler.systemPrompt();

        assertThat(prompt).contains("[DECODE TRUTH LOCK]");
        assertThat(prompt).contains("Do NOT claim that a live meter reading, current state, or current measurement value was returned");
        assertThat(prompt).contains("For SNRM specifically, do NOT say the link is already established or already in normal response mode");
    }

    @Test
    void assemble_previousFrameQuestionHighlightsSessionStateAndHistory() {
        WorkflowState state = WorkflowState.empty("s12c", "c12c", "what was the frame type of the frame I just sent?")
                .toBuilder()
                .intent(DlmsIntent.UNKNOWN)
                .associationState("ASSOCIATING")
                .hdlcClientSap("1")
                .hdlcServerSap("1")
                .narrativeContext(List.of(
                        new com.company.dlms.domain.SessionEvent(
                                "s12c",
                                java.time.Instant.parse("2026-05-26T10:00:00Z"),
                                1,
                                "U_FRAME (SNRM)",
                                "COMPLETE",
                                "ASSOCIATING",
                                null,
                                null,
                                List.of(),
                                List.of(),
                                List.of()
                        )
                ))
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("When asked about previous frames in this conversation, always consult the SESSION STATE section above.");
        assertThat(prompt).contains("Do not search documentation for session-specific questions.");
        assertThat(prompt).contains("SESSION STATE (from previous frames in this conversation):");
        assertThat(prompt).contains("Last frame type: U_FRAME (SNRM)");
        assertThat(prompt).contains("Last association state: ASSOCIATING");
        assertThat(prompt).contains("U_FRAME (SNRM) [COMPLETE]");
    }

    @Test
    void assemble_siconiaRawFallbackPromptForbidsPretendingStructuredParseSucceeded() {
        WorkflowState state = WorkflowState.empty("s13", "c13", "<Envelope><Payload/></Envelope>")
                .toBuilder()
                .intent(DlmsIntent.SICONIA_TROUBLESHOOT)
                .siconiaResult(new SiconiaResult(
                        new com.company.dlms.domain.siconia.SiconiaXmlTrace(List.of(), List.of("No supported event fields found"), "<Envelope><Payload/></Envelope>"),
                        null,
                        null,
                        "XML_TRACE",
                        new SiconiaProcessingMetadata(InputClass.XML_TRACE, ParseProvenance.RAW_FALLBACK, List.of("Structured event extraction did not match a supported schema"), "valid XML interpreted from raw input")
                ))
                .build();

        String prompt = promptAssembler.assemble(state);

        assertThat(prompt).contains("Interpret the XML from raw input only.");
        assertThat(prompt).contains("Do not claim that a full structured parse succeeded.");
        assertThat(prompt).contains("Use cautious language when fields were not recovered deterministically.");
    }

    @Test
    void assemble_documentationAndSecurityPromptsRequireMarkdownFormattingAndInlineSourceSeparation() {
        WorkflowState docsState = WorkflowState.empty("s14", "c14", "What is the DLMS Green Book?")
                .toBuilder()
                .intent(DlmsIntent.DOCUMENTATION)
                .inputClass(InputClass.QUERY)
                .groundedAnswerContext(GroundedAnswerContext.retrievalDocs(List.of(), List.of(), 0.8))
                .build();

        WorkflowState securityState = WorkflowState.empty("s15", "c15", "Explain HLS authentication")
                .toBuilder()
                .intent(DlmsIntent.SECURITY_EXPLAIN)
                .inputClass(InputClass.QUERY)
                .groundedAnswerContext(GroundedAnswerContext.retrievalSecurity(List.of(), List.of(), 0.8))
                .build();

        String docsPrompt = promptAssembler.assemble(docsState);
        String securityPrompt = promptAssembler.assemble(securityState);

        assertThat(docsPrompt).contains("Format your response using markdown:");
        assertThat(docsPrompt).contains("- Use **bold** for important terms and protocol names.");
        assertThat(docsPrompt).contains("- Use ## for section headers when the answer has multiple parts.");
        assertThat(docsPrompt).contains("Do not append a Sources: line inside the answer body");
        assertThat(docsPrompt).contains("Start directly with a clean answer in your own words.");
        assertThat(securityPrompt).contains("Format your response using markdown:");
        assertThat(securityPrompt).contains("- Use **bold** for important terms and protocol names.");
        assertThat(securityPrompt).contains("- Use ## for section headers when the answer has multiple parts.");
        assertThat(securityPrompt).contains("Do not append a Sources: line inside the answer body");
        assertThat(securityPrompt).contains("Start directly with a clean answer in your own words.");
    }
}
