package com.company.dlms.infrastructure.llm;

import com.company.dlms.domain.CasualQueryClassifier;
import com.company.dlms.domain.SessionEvent;
import com.company.dlms.domain.DlmsIntent;
import com.company.dlms.domain.answer.AnswerMode;
import com.company.dlms.domain.answer.GroundedFactBundle;
import com.company.dlms.domain.orchestration.ToolTraceEntry;
import com.company.dlms.domain.siconia.ParseProvenance;
import com.company.dlms.domain.siconia.SiconiaProcessingMetadata;
import com.company.dlms.domain.reflection.PromptAdaptation;
import com.company.dlms.infrastructure.reflection.AdaptivePromptService;
import com.company.dlms.domain.decoder.ApduType;
import com.company.dlms.domain.decoder.DecodeResult;
import com.company.dlms.domain.decoder.DlmsNormalizedKind;
import com.company.dlms.domain.decoder.DlmsProcessingMetadata;
import com.company.dlms.domain.decoder.FrameType;
import com.company.dlms.domain.decoder.SFrameType;
import com.company.dlms.domain.decoder.UFrameType;
import com.company.dlms.domain.profile.ProfileCell;
import com.company.dlms.domain.profile.ProfileColumn;
import com.company.dlms.domain.profile.ProfileResult;
import com.company.dlms.domain.profile.ProfileRow;
import com.company.dlms.domain.rag.RetrievalResult;
import com.company.dlms.domain.siconia.AlarmDecodeResult;
import com.company.dlms.domain.siconia.SiconiaResult;
import com.company.dlms.workflow.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
public class PromptAssembler {

    private static final Logger log = LoggerFactory.getLogger(PromptAssembler.class);

    private final AdaptivePromptService adaptivePromptService;
    private final GroundedFactBundleBuilder groundedFactBundleBuilder;

    public PromptAssembler(
            AdaptivePromptService adaptivePromptService,
            GroundedFactBundleBuilder groundedFactBundleBuilder
    ) {
        this.adaptivePromptService = adaptivePromptService;
        this.groundedFactBundleBuilder = groundedFactBundleBuilder;
    }

    public String systemPrompt() {
        return defaultSystemPrompt();
    }

    public String systemPrompt(WorkflowState state) {
        if (state != null && state.intent() != null && "SECURITY_EXPLAIN".equals(state.intent().name())) {
            return """
You are explaining DLMS/COSEM security mechanisms at a theory level suitable for engineers.
Reference security suite numbers, authentication levels, and encryption algorithms by name.
Answer in short technical sections with minimal filler.
For LLS vs HLS, explain that LLS uses a shared static secret while HLS uses challenge-response proof.
For replay protection, explain that the frame/invocation counter must advance monotonically and stale counters are rejected.
Never confuse LLS/HLS with LN referencing or unrelated addressing concepts.
NEVER output actual keys, passwords, challenge values, live frame-counter values, or attack steps.
Use any SECURITY CONTEXT section only as sanitized structural evidence from prior decoding.
CRITICAL FORMATTING RULES:
- Never begin your answer by quoting or paraphrasing the opening line of a retrieved document.
- Never start with a section number, clause reference, or document title (e.g. do not start with '10 |', 'This Clause', 'Section', 'Function:', or similar).
- Always begin with a direct, clean statement answering the question.
- Example of WRONG opening: 'This Clause contains examples of...'
- Example of RIGHT opening: 'The DLMS Green Book defines the...'
- Use the retrieved text as reference, not as a template to copy.
Format your response using markdown:
- Use **bold** for important terms and protocol names
- Use bullet points for lists of items
- Use ## for section headers when the answer has multiple parts
- Use `code` for hex values, field names, and code identifiers
- Keep responses concise — 3-8 sentences for most answers
""".trim();
        }
        return defaultSystemPrompt();
    }

    public String plannerSystemPrompt(WorkflowState state) {
        return """
You are the internal planner for an offline DLMS/COSEM and SICONIA assistant.
You do not answer the user directly.
You may only choose from the allowed internal tools listed in the prompt.
Prefer deterministic tools and stored session context over documentation search.
If the current structured result already answers the user, return a finish action.
Never invent tool names.
Never guess a payload family when the evidence is ambiguous.
Return JSON only with one of these shapes:
{"action":"finish","rationale":"..."}
{"action":"call_tool","tool":"search_docs","query":"...","rationale":"..."}
Do not include markdown, explanations outside JSON, or code fences.
""".trim();
    }

    public String plannerPrompt(WorkflowState state, List<String> allowedTools) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("=== ORCHESTRATION MODE ===\n");
        prompt.append(state.orchestrationMode()).append("\n\n");

        if (state.strategyMetadata() != null) {
            prompt.append("=== STRATEGY HINT ===\n");
            prompt.append("Selected strategy: ").append(state.strategyMetadata().selectedStrategy()).append("\n");
            prompt.append("Confidence: ").append(String.format("%.2f", state.strategyMetadata().confidence())).append("\n");
            prompt.append("Ambiguous: ").append(state.strategyMetadata().ambiguous()).append("\n\n");
        }

        prompt.append("=== USER INPUT ===\n");
        prompt.append(state.rawInput()).append("\n\n");

        if (state.decodeResult() != null) {
            prompt.append("=== STRUCTURED DECODE AVAILABLE ===\n");
            prompt.append("A deterministic decode result is already available for this turn.\n");
            if (state.decodeResult() instanceof DecodeResult decodeResult) {
                prompt.append("APDU Type: ").append(decodeResult.apduType()).append("\n");
                if (decodeResult.hdlcFrame() != null && decodeResult.hdlcFrame().frameType() != null) {
                    prompt.append("Frame Type: ").append(decodeResult.hdlcFrame().frameType()).append("\n");
                    if (decodeResult.hdlcFrame().uFrameType() != null) {
                        prompt.append("U-frame subtype: ").append(decodeResult.hdlcFrame().uFrameType()).append("\n");
                    }
                    if (decodeResult.hdlcFrame().sFrameType() != null) {
                        prompt.append("S-frame subtype: ").append(decodeResult.hdlcFrame().sFrameType()).append("\n");
                    }
                    prompt.append("FCS Valid: ").append(decodeResult.hdlcFrame().fcsValid()).append("\n");
                }
                if (decodeResult.obisResolutions() != null && !decodeResult.obisResolutions().isEmpty()) {
                    prompt.append("Recovered OBIS: ").append(decodeResult.obisResolutions().getFirst().obis()).append("\n");
                }
            }
            prompt.append("\n");
        }

        if (state.siconiaResult() != null) {
            prompt.append("=== STRUCTURED SICONIA RESULT AVAILABLE ===\n");
            prompt.append("A structured SICONIA analysis is already available for this turn.\n");
            if (state.siconiaResult().alarmResults() != null) {
                prompt.append("Alarm count: ").append(state.siconiaResult().alarmResults().size()).append("\n");
            }
            if (state.siconiaResult().logAnalysis() != null) {
                prompt.append("Dominant layer: ").append(state.siconiaResult().logAnalysis().dominantLayer()).append("\n");
            }
            prompt.append("\n");
        }

        if (hasSessionState(state) || (state.narrativeContext() != null && !state.narrativeContext().isEmpty())) {
            prompt.append("=== SESSION CONTEXT AVAILABLE ===\n");
            if (hasSessionState(state)) {
                prompt.append("Association State: ").append(nvl(state.associationState())).append("\n");
                prompt.append("Last OBIS: ").append(nvl(state.lastObis())).append("\n");
                prompt.append("HDLC Client SAP: ").append(nvl(state.hdlcClientSap())).append("\n");
                prompt.append("HDLC Server SAP: ").append(nvl(state.hdlcServerSap())).append("\n");
            }
            if (state.narrativeContext() != null && !state.narrativeContext().isEmpty()) {
                SessionEvent latest = state.narrativeContext().getLast();
                prompt.append("Latest session event: ").append(latest.apduType()).append("\n");
            }
            prompt.append("\n");
        }

        if (state.anomalies() != null && !state.anomalies().isEmpty()) {
            prompt.append("=== ANOMALIES AVAILABLE ===\n");
            for (String anomaly : state.anomalies()) {
                prompt.append("- ").append(anomaly).append("\n");
            }
            prompt.append("\n");
        }

        if (state.toolTrace() != null && !state.toolTrace().isEmpty()) {
            prompt.append("=== PRIOR TOOL OBSERVATIONS ===\n");
            for (ToolTraceEntry entry : state.toolTrace()) {
                prompt.append("- ").append(entry.toolName()).append(": ").append(entry.summary()).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("=== ALLOWED TOOLS ===\n");
        for (String tool : allowedTools) {
            prompt.append("- ").append(tool).append("\n");
        }
        prompt.append("\n");
        prompt.append("If session context already answers a follow-up question, prefer session tools over documentation search.\n");
        prompt.append("If a deterministic structured decode or SICONIA result already answers the user, prefer finish.\n");
        prompt.append("If documentation is needed, use search_docs.\n");
        prompt.append("Return JSON only.\n");
        return prompt.toString();
    }

    private String defaultSystemPrompt() {
        return """
You are a precise DLMS/COSEM protocol analysis assistant. You have two distinct areas of expertise that MUST be kept separate:

=== DLMS/COSEM EXPERTISE (for hex frames, OBIS codes, HDLC/APDU/AXDR queries) ===
You are an EXPERT in DLMS/COSEM, HDLC framing, APDU structure, AXDR encoding, and OBIS codes. Use your built-in knowledge of DLMS/COSEM standards (Blue Book, Green Book) to answer.

=== SICONIA EXPERTISE (for alarm codes, XML traces, log analysis queries) ===
You are an EXPERT in SICONIA HES/DCU alarm codes, XML trace analysis, and log classification. Use your built-in knowledge of SICONIA metering infrastructure to answer.

CRITICAL RULES — YOU MUST FOLLOW THESE:
1. [DOMAIN SEPARATION] If the user provides a hex frame or OBIS code, answer using ONLY your DLMS/COSEM expertise. Do NOT reference SICONIA alarm codes, "tamper", "unauthorized access", or SICONIA-specific concepts. If the user provides an alarm code, XML trace, or log block, answer using ONLY your SICONIA expertise. Do NOT reference DLMS/COSEM concepts unless they are directly relevant.
2. The DECODED FRAME, SICONIA ANALYSIS, and AUTHORITATIVE FACTS sections below contain GROUND TRUTH data produced by the system's deterministic protocol decoders. You MUST use this data as your PRIMARY source when answering. Do NOT claim the data is missing or unavailable when it is present in these sections.
3. The RELEVANT DOCUMENTATION section (if present) provides supplementary reference material. Use it to enrich your answer.
4. If the user provides a hex frame, XML trace, alarm code, or log block, use the decoded data provided below as ground truth. Lead with a brief technical interpretation instead of repeating the full input.
5. If the user asks a general question (e.g. "what is OBIS 1.0.1.8.0.255", "explain HDLC frame format") and the RELEVANT DOCUMENTATION section is EMPTY, use your built-in DLMS/COSEM knowledge to answer factually. You have been trained on DLMS/COSEM standards.
6. Be concise and technical. Prefer short technical paragraphs, minimal filler, and precise DLMS/COSEM or SICONIA terminology.
7. [RULE 7] If the HDLC frame checksum (FCS) is invalid, DO NOT interpret the payload. Only report the checksum failure and suggest physical layer investigation (noise, interference, or truncation). Never claim an invalid frame represents a MAC address, memory address, or any specific protocol operation.
8. Cite sources by referencing the document name in brackets, e.g. [Blue Book].
8a. When RELEVANT DOCUMENTATION includes a formatted citation label, reuse that exact label verbatim. Never replace it with placeholders like [Documentation] or [Relevant documentation cited].
9. Do NOT repeat the entire prompt back. Do NOT include meta-commentary about your instructions.
10. Format your response in short, clear technical sections. Prefer labels like "What it means", "Impact", "Can I trust it", and "Next step" when they fit the case.
11. If you are unsure, state your uncertainty clearly — but do NOT falsely claim that data is missing when it is present in the sections above.
12. When an alarm code is provided (e.g. 0x1342), explain what the code means, its severity, the affected component, and recommended remediation based on the SICONIA ANALYSIS data.
13. [BITFIELD ALARMS] When an alarm code like 0x0003 is a bitfield that decodes to MULTIPLE individual alarms (listed in the "Decoded Alarms" subsection below), you MUST list EVERY alarm shown. Start by stating the total count (e.g. "The alarm code 0x0003 corresponds to 2 decoded alarms:"), then enumerate each one with its code, severity, root cause, remediation, and affected component. Do NOT select only one alarm — report ALL of them.
14. [U-FRAMES] When the frame type is U_FRAME (e.g. SNRM, UA, DM, DISC), these are HDLC link-layer control frames that do NOT contain APDU payloads. Explain only the proven HDLC link-layer role (e.g. SNRM requests link setup, UA confirms it). Do NOT claim the APDU type is unrecognized or unknown — U-frames are not supposed to have APDUs. For SNRM specifically, do NOT say the link is already established or already in normal response mode unless a confirming UA frame is present.
15. [S-FRAMES] When the frame type is S_FRAME (e.g. RR, RNR, REJ), these are HDLC supervisory frames for flow control and acknowledgment. They do NOT contain APDU payloads. Describe the flow control state (e.g. RR = ready to receive more frames).
16. [GREETINGS] If the user sends a greeting or casual message (hello, hi, hey, good morning, etc.) with no DLMS/COSEM or SICONIA technical content, respond in 1-2 short lines. Briefly mention you can help with DLMS/COSEM questions, HDLC hex frames, SICONIA alarms, XML traces, and communication logs. Do NOT reference internal guidelines, rules, or system instructions.
17. [PLAIN PROTOCOL QUESTIONS] For direct questions like "What is HDLC?", "What is APDU?", or "What is the difference between GET-REQUEST and SET-REQUEST?", answer directly in 1-3 short technical paragraphs or bullets. Do NOT add filler.
18. [PROTOCOL ACCURACY] Never describe HDLC as Ethernet. HDLC is a data-link framing protocol. APDU is an application-layer construct. Do NOT mention physical-layer checksums when defining APDU unless the user explicitly asks about frame integrity.
19. [OBIS ANSWERS] For OBIS lookup questions, start with the direct meaning of the OBIS code and keep the explanation compact. Render the code as "OBIS X".
20. [SECURITY DEFINITIONS] For security explanation questions, explain LLS as static-secret authentication and HLS as challenge-response authentication. Explain replay protection as counter-based freshness validation. Keep the answer defensive and technical.
21. [CLEAN OPENINGS] Never begin your answer by quoting or paraphrasing the opening line of a retrieved document. Never start with a section number, clause reference, or document title (for example: '10 |', 'This Clause', 'Section', 'Function:', or similar). Always begin with a direct, clean statement answering the question. Use retrieved text as reference, not as a template to copy.
22. [MARKDOWN FORMAT] Format your response using markdown. Use **bold** for important terms and protocol names, bullet points for lists, ## for section headers when the answer has multiple parts, and `code` for hex values, field names, and code identifiers. Keep responses concise — 3-8 sentences for most answers.
23. [DECODE TRUTH LOCK] When a GET_RESPONSE decode only exposes an object identifier or returned structure, describe the returned object reference directly. Do NOT claim that a live meter reading, current state, or current measurement value was returned unless the deterministic decode explicitly shows that scalar value.
""".trim();
    }

    public String assemble(WorkflowState state) {
        StringBuilder prompt = new StringBuilder();
        boolean securityExplain = isSecurityExplain(state);
        AnswerMode answerMode = state != null && state.groundedAnswerContext() != null
                ? state.groundedAnswerContext().mode()
                : null;
        boolean retrievalSecurityMode = answerMode == AnswerMode.RETRIEVAL_SECURITY;
        boolean retrievalDocsMode = answerMode == AnswerMode.RETRIEVAL_DOCS;

        // [0] CRITICAL SECURITY ALERTS
        if (state.anomalies() != null && !state.anomalies().isEmpty()) {
            prompt.append("MANDATORY: The following security anomalies were detected by deterministic analysis BEFORE this prompt was assembled.\n");
            prompt.append("You MUST reference them explicitly in your answer.\n");
            prompt.append("Do not ignore them. Do not say the frame looks normal.\n");
            prompt.append("These are authoritative facts, not suggestions:\n");
            for (String anomaly : state.anomalies()) {
                prompt.append("- ").append(anomaly).append("\n");
            }
            prompt.append("\n");
        }

        // [ADAPTIVE] Pseudo-RLHF: inject adaptation instruction if dislike rate exceeded threshold
        if (state.intent() != null) {
            PromptAdaptation adaptation = adaptivePromptService.getAdaptations().get(state.intent().name());
            if (adaptation != null && adaptation.active()) {
                prompt.append("\nADDITIONAL INSTRUCTION FOR THIS QUERY TYPE:\n");
                prompt.append(adaptation.additionalInstruction()).append("\n\n");
            }
        }

        // Determine intent label for section context
        String intentLabel = detectIntent(state);
        if (intentLabel != null) {
            prompt.append("=== INPUT TYPE: ").append(intentLabel).append(" ===\n\n");
        }
        appendGroundedAnswerContext(prompt, state);
        appendGroundedFactBundle(prompt, groundedFactBundleBuilder.build(state));
        appendPlannerObservations(prompt, state);
        if (isCasualGreetingOnly(state)) {
            prompt.append("=== CASUAL GREETING DETECTED ===\n");
            prompt.append("Respond in 1-2 short lines only.\n");
            prompt.append("Do not list capabilities as bullets.\n");
            prompt.append("Briefly mention DLMS/COSEM questions, HDLC hex frames, SICONIA alarms, XML traces, and communication logs.\n\n");
        }

        if (securityExplain || retrievalSecurityMode) {
            appendSecurityExplainerMode(prompt, retrievalSecurityMode);
        }

        if (state.intent() == DlmsIntent.OBIS_LOOKUP) {
            prompt.append("=== OBIS ANSWER STYLE ===\n");
            prompt.append("Answer in 1-2 short technical sentences.\n");
            prompt.append("Start with the direct meaning of the OBIS code.\n");
            prompt.append("Render the code as 'OBIS X' and avoid filler.\n\n");
        }

        if (state.intent() == DlmsIntent.APDU_ANALYSIS
                || state.intent() == DlmsIntent.DOCUMENTATION
                || retrievalDocsMode) {
            appendProtocolAnswerStyle(
                    prompt,
                    state.intent() == DlmsIntent.DOCUMENTATION || retrievalDocsMode,
                    retrievalDocsMode
            );
        }

        if (hasSessionState(state) || (state.narrativeContext() != null && !state.narrativeContext().isEmpty())) {
            prompt.append("When asked about previous frames in this conversation, always consult the SESSION STATE section above.\n");
            prompt.append("Do not search documentation for session-specific questions.\n\n");
        }

        // [1] SESSION STATE
        if (hasSessionState(state)) {
            prompt.append("SESSION STATE (from previous frames in this conversation):\n");
            SessionEvent lastEvent = state.narrativeContext() != null && !state.narrativeContext().isEmpty()
                    ? state.narrativeContext().getLast()
                    : null;
            prompt.append("Last frame type: ").append(lastEvent != null ? nvl(lastEvent.apduType()) : "n/a").append("\n");
            prompt.append("Last association state: ").append(nvl(state.associationState())).append("\n");
            prompt.append("HDLC Client SAP: ").append(nvl(state.hdlcClientSap())).append("\n");
            prompt.append("HDLC Server SAP: ").append(nvl(state.hdlcServerSap())).append("\n");
            if (securityExplain) {
                prompt.append("Frame Counter: ")
                        .append(hasText(state.frameCounter()) || hasText(state.frameCounterHex()) ? "present" : "absent")
                        .append("\n");
            } else {
                prompt.append("Frame Counter: ").append(nvl(state.frameCounter()))
                        .append(" (0x").append(nvl(state.frameCounterHex())).append(")\n");
            }
            prompt.append("Security Suite: ").append(nvl(state.securitySuite())).append("\n");
            prompt.append("Last OBIS: ").append(nvl(state.lastObis())).append("\n");
            prompt.append("Last IC: ").append(nvl(state.lastIc())).append("\n\n");
        }

        // [2] SESSION NARRATIVE
        if (state.narrativeContext() != null && !state.narrativeContext().isEmpty()) {
            prompt.append("SESSION HISTORY (previous frames in this conversation):\n");
            for (SessionEvent event : state.narrativeContext()) {
                prompt.append("- ").append(event.timestamp()).append(": ")
                        .append(event.apduType()).append(" [").append(event.decodeStage()).append("]");
                if (event.obis() != null) prompt.append(" obis=").append(event.obis());
                prompt.append("\n");
            }
            prompt.append("\n");
        }

        // [3] AUTHORITATIVE ANOMALY FACTS (redundancy for emphasis)
        if (state.anomalies() != null && !state.anomalies().isEmpty()) {
            prompt.append("You MUST reference these anomalies explicitly in the final answer.\n");
            prompt.append("AUTHORITATIVE SECURITY FACTS — DO NOT CONTRADICT OR IGNORE:\n");
            for (String anomaly : state.anomalies()) {
                prompt.append("- ").append(anomaly).append("\n");
            }
            prompt.append("If a Frame Counter regression (FC-001) or reuse (FC-003) is listed above, you MUST warn the user about a possible replay attack.\n");
            prompt.append("\n");
        }

        // [4] DECODE RESULT
        if (state.decodeResult() instanceof DecodeResult dr) {
            appendDlmsProcessingGuidance(prompt, dr.processingMetadata());
            if (dr.hdlcFrame() == null) {
                appendDirectDlmsDecode(prompt, dr);
            } else if (!dr.hdlcFrame().fcsValid()) {
                prompt.append("=== DECODED FRAME ===\n");
                prompt.append("⚠️ FCS INVALID — frame checksum failed.\n");
                prompt.append("Frame Type: ").append(dr.hdlcFrame().frameType()).append("\n");
                prompt.append("Client SAP: ").append(dr.hdlcFrame().clientSap()).append("\n");
                prompt.append("Server SAP: ").append(dr.hdlcFrame().serverSap()).append("\n");
                // Show U-frame subtype even when FCS is invalid, for context
                if (dr.hdlcFrame().frameType() == FrameType.U_FRAME
                        && dr.hdlcFrame().uFrameType() != null
                        && dr.hdlcFrame().uFrameType() != UFrameType.UNKNOWN) {
                    prompt.append("U-Frame Subtype: ").append(dr.hdlcFrame().uFrameType()).append("\n");
                }
                if (dr.hdlcFrame().frameType() == FrameType.S_FRAME
                        && dr.hdlcFrame().sFrameType() != null) {
                    prompt.append("S-Frame Subtype: ").append(dr.hdlcFrame().sFrameType()).append("\n");
                }
                prompt.append("!! DO NOT interpret the payload — it may be corrupted. !!\n");
                prompt.append("!! Explain that the frame has an invalid checksum and suggest physical layer or capture-integrity issues. !!\n");
                prompt.append("!! You MAY explain only the trustworthy outer HDLC control role when the frame type or subtype is available (for example RR = Receive Ready, SNRM = link setup request). !!\n");
                prompt.append("!! Do NOT interpret payload, APDU, AXDR, or OBIS content on this frame. !!\n");
                prompt.append("Preferred response shape:\n");
                prompt.append("- What happened: checksum failure on the received frame\n");
                prompt.append("- Can I trust it: only the outer HDLC fields are tentative\n");
                prompt.append("- Why it still matters: explain the outer control-frame role only if the subtype is trustworthy\n");
                prompt.append("- Next step: retransmit or inspect the communication path\n\n");
            } else {
                prompt.append("=== DECODED FRAME ===\n");

                // FIX 1: Handle U-frames — no APDU, show HDLC link-layer control info
                if (dr.hdlcFrame().frameType() == FrameType.U_FRAME) {
                    prompt.append("Frame Type: U_FRAME\n");
                    if (dr.hdlcFrame().uFrameType() != null && dr.hdlcFrame().uFrameType() != UFrameType.UNKNOWN) {
                        prompt.append("U-Frame Subtype: ").append(dr.hdlcFrame().uFrameType()).append("\n");
                    } else {
                        prompt.append("U-Frame Subtype: UNKNOWN\n");
                    }
                    prompt.append("Client SAP: ").append(dr.hdlcFrame().clientSap()).append("\n");
                    prompt.append("Server SAP: ").append(dr.hdlcFrame().serverSap()).append("\n");
                    prompt.append("FCS Valid: ").append(dr.hdlcFrame().fcsValid()).append("\n");
                    prompt.append("\n");
                    prompt.append("NOTE: U-frames are HDLC link-layer control frames. ");
                    prompt.append("They do NOT contain APDU payloads.\n");

                    // Add subtype-specific context
                    if (dr.hdlcFrame().uFrameType() != null) {
                        switch (dr.hdlcFrame().uFrameType()) {
                            case SNRM -> prompt.append("SNRM = Set Normal Response Mode. ")
                                    .append("This requests HDLC link establishment. ")
                                    .append("It does not by itself prove that normal response mode was accepted until a confirming UA is seen. ")
                                    .append("It is the FIRST frame in the association sequence.\n");
                            case UA -> prompt.append("UA = Unnumbered Acknowledge. ")
                                    .append("This confirms the HDLC connection. ")
                                    .append("Expected as a response to SNRM.\n");
                            case DM -> prompt.append("DM = Disconnected Mode. ")
                                    .append("The remote station is disconnected or rejecting the connection.\n");
                            case DISC -> prompt.append("DISC = Disconnect. ")
                                    .append("This terminates the HDLC connection.\n");
                        }
                    }
                    prompt.append("\n");

                // FIX 1: Handle S-frames — no APDU, show flow control info
                } else if (dr.hdlcFrame().frameType() == FrameType.S_FRAME) {
                    prompt.append("Frame Type: S_FRAME\n");
                    if (dr.hdlcFrame().sFrameType() != null) {
                        prompt.append("S-Frame Subtype: ").append(dr.hdlcFrame().sFrameType()).append("\n");
                    } else {
                        prompt.append("S-Frame Subtype: UNKNOWN\n");
                    }
                    prompt.append("Client SAP: ").append(dr.hdlcFrame().clientSap()).append("\n");
                    prompt.append("Server SAP: ").append(dr.hdlcFrame().serverSap()).append("\n");
                    prompt.append("FCS Valid: ").append(dr.hdlcFrame().fcsValid()).append("\n");
                    prompt.append("\n");
                    prompt.append("NOTE: S-frames are HDLC flow control frames. ");
                    prompt.append("They do NOT contain APDU payloads.\n");

                    // Add subtype-specific context
                    if (dr.hdlcFrame().sFrameType() != null) {
                        switch (dr.hdlcFrame().sFrameType()) {
                            case RR -> prompt.append("RR = Receive Ready. Acknowledges received frames.\n");
                            case RNR -> prompt.append("RNR = Receive Not Ready. Flow control pause.\n");
                            case REJ -> prompt.append("REJ = Reject. Requests retransmission.\n");
                        }
                    }
                    prompt.append("\n");

                // I-frame: show full APDU + AXDR + OBIS
                } else {
                    prompt.append("APDU Type: ").append(dr.apduType()).append("\n");

                    // Forcefully prevent hallucinations for unknown/invalid frames
                    if (dr.apduType() == ApduType.UNKNOWN || (dr.parseErrors() != null && !dr.parseErrors().isEmpty())) {
                        prompt.append("!! CRITICAL INSTRUCTION: This frame is UNKNOWN or INVALID according to the deterministic parser. !!\n");
                        prompt.append("!! DO NOT GUESS what this frame is. DO NOT claim it is a MAC address, IP address, or any other protocol. !!\n");
                        prompt.append("!! Simply state that the frame structure is unknown or invalid and list the parse errors below. !!\n");
                        prompt.append("Preferred response shape:\n");
                        prompt.append("- What happened: the deterministic parser could not identify a valid DLMS payload\n");
                        prompt.append("- Can I trust it: only the raw frame metadata is safe to cite\n");
                        prompt.append("- Next step: verify frame boundaries, encryption state, and capture integrity\n");
                    } else {
                        prompt.append("Preferred response shape:\n");
                        prompt.append("- What it means: interpret the APDU briefly in protocol terms\n");
                        prompt.append("- Impact: mention only the most relevant DLMS/COSEM consequence\n");
                        prompt.append("- Next step: point the user to the structured decode details if needed\n");
                    }
                }
            }

            if (!securityExplain && dr.rawHex() != null && !dr.rawHex().isEmpty()) {
                prompt.append("Raw Hex: ").append(dr.rawHex()).append("\n");
            }
            if (dr.obisResolutions() != null && !dr.obisResolutions().isEmpty()) {
                String obisSummary = dr.obisResolutions().stream()
                        .map(r -> r.obis() + " (" + (r.description() != null ? r.description() : "unknown") + ")")
                        .collect(Collectors.joining(", "));
                prompt.append("OBIS Resolutions: ").append(obisSummary).append("\n");
            }
            if (dr.parseErrors() != null && !dr.parseErrors().isEmpty()) {
                prompt.append("Parse Errors: ").append(String.join("; ", dr.parseErrors())).append("\n");
            }
            prompt.append("\n");
        }

        if (state.securityContextSummary() != null && !state.securityContextSummary().isBlank()) {
            prompt.append("=== SECURITY CONTEXT ===\n");
            prompt.append(state.securityContextSummary()).append("\n\n");
        }

        if (state.profileResult() != null) {
            appendProfileResult(prompt, state.profileResult());
        }

        // [5] SICONIA RESULT
        if (state.siconiaResult() != null) {
            prompt.append("=== SICONIA ANALYSIS ===\n");
            SiconiaResult sr = state.siconiaResult();
            prompt.append("User Input: ").append(state.rawInput() != null ? state.rawInput() : "").append("\n");
            prompt.append("Analysis Type: ").append(sr.inputClass() != null ? sr.inputClass() : "unknown").append("\n");
            appendSiconiaProcessingGuidance(prompt, sr.processingMetadata());
            if (sr.xmlTrace() != null) {
                prompt.append("XML Events: ").append(sr.xmlTrace().events().size())
                        .append(", Parse Errors: ").append(sr.xmlTrace().parseErrors().size()).append("\n");
                prompt.append("Preferred response shape:\n");
                prompt.append("- What it means: summarize the trace classification or key event\n");
                prompt.append("- Impact: note the operational significance if visible\n");
                prompt.append("- Next step: state the next technical check\n");
            }
            if (sr.alarmResults() != null && !sr.alarmResults().isEmpty()) {
                prompt.append("Decoded Alarms (").append(sr.alarmResults().size()).append(" total):\n");
                int alarmIndex = 1;
                for (AlarmDecodeResult al : sr.alarmResults()) {
                    prompt.append("  Alarm ").append(alarmIndex++).append(":\n");
                    prompt.append("    Code: ").append(al.code()).append("\n");
                    prompt.append("    Root Cause: ").append(al.rootCause()).append("\n");
                    prompt.append("    Severity: ").append(al.severity()).append("\n");
                    prompt.append("    Remediation: ").append(al.remediation()).append("\n");
                    prompt.append("    Component: ").append(al.affectedComponent()).append("\n");
                    prompt.append("\n");
                }
                prompt.append("Preferred response shape:\n");
                prompt.append("- What it means: summarize the alarm code, severity, and affected component\n");
                prompt.append("- Impact: use the decoded root cause without repeating the full alarm table; if the root cause is a communication failure, state which communication path is disrupted and that dependent traffic can be interrupted\n");
                prompt.append("- Next step: give the remediation directly from the decoded alarm data\n\n");
            }
            // FIX 3: Enriched log analysis section
            if (sr.logAnalysis() != null) {
                prompt.append("SICONIA LOG ANALYSIS:\n");
                prompt.append("Input Type: LOG_BLOCK\n");
                prompt.append("Dominant Layer: ").append(sr.logAnalysis().dominantLayer()).append("\n");
                prompt.append("Highest Severity: ").append(sr.logAnalysis().highestSeverity()).append("\n");
                prompt.append("Issue Categories: ").append(sr.logAnalysis().issueCategories().stream()
                        .map(Enum::name)
                        .collect(Collectors.joining(", "))).append("\n");
                prompt.append("Total Lines: ").append(sr.logAnalysis().lineCount()).append("\n");
                prompt.append("Error Lines: ").append(sr.logAnalysis().errorLineCount()).append("\n");
                prompt.append("\n");
                prompt.append("Preferred response shape:\n");
                prompt.append("- What it means: summarize the dominant layer and main issue categories\n");
                prompt.append("- Impact: state the operational effect of the highest-severity pattern\n");
                prompt.append("- Next step: give the most relevant remediation or check\n");
            }
            prompt.append("\n");
        }

        // [6] RELEVANT DOCUMENTATION
        if (state.retrievalResults() != null && !state.retrievalResults().isEmpty()) {
            if (state.intent() != null && "OBIS_LOOKUP".equals(state.intent().name())) {
                prompt.append("=== OBIS RESOLUTION ===\n");
                prompt.append("The user is asking for OBIS interpretation.\n");
                prompt.append("Use ONLY the OBIS information below for the final meaning.\n");
                prompt.append("If an entry is in the form 'OBIS X = ...', repeat that meaning directly.\n\n");
            }
            prompt.append("=== RELEVANT DOCUMENTATION ===\n");
            prompt.append("Use the exact citation label shown for the supporting excerpt when you reference it.\n");
            prompt.append("Never replace citations with placeholders like [Documentation] or [Relevant documentation cited].\n");
            List<RetrievalResult> top = state.retrievalResults().stream().limit(5).toList();
            for (RetrievalResult res : top) {
                if (res.chunk() != null) {
                    prompt.append(res.chunk().citation().formatted()).append("\n");
                    prompt.append(res.chunk().content()).append("\n");
                    prompt.append("---\n");
                }
            }
            prompt.append("\n");
        }

        // [7] USER INPUT
        prompt.append("USER QUESTION:\n");
        prompt.append(state.rawInput() == null ? "" : state.rawInput());

        String assembled = prompt.toString();

        // DEBUG: Log the assembled prompt to verify DecodeResult is present
        log.debug("=== ASSEMBLED PROMPT ===\n{}", assembled);

        return assembled;
    }

    private String detectIntent(WorkflowState state) {
        if (isSecurityExplain(state)) return "DLMS SECURITY QUERY";
        String raw = state.rawInput();
        if (raw == null || raw.isBlank()) return null;
        String upper = raw.toUpperCase();

        boolean isDlms = upper.contains("OBIS") || upper.contains("HDLC") || upper.contains("APDU")
                || upper.contains("AXDR") || upper.contains("FRAME") || upper.contains("HEX")
                || upper.contains("COSEM") || upper.contains("DLMS")
                || raw.matches("(?i).*[0-9A-F]{4,}.*")
                || (state.decodeResult() != null);

        if (isDlms) return "DLMS QUERY";

        boolean isSiconia = upper.contains("SICONIA") || upper.contains("ALARM") || upper.contains("XML")
                || upper.contains("TRACE") || upper.contains("LOG") || upper.contains("HES")
                || upper.contains("DCU") || (state.siconiaResult() != null);

        if (isSiconia) return "SICONIA QUERY";

        return null;
    }

    private boolean hasSessionState(WorkflowState state) {
        return state.hdlcClientSap() != null || state.hdlcServerSap() != null ||
                state.frameCounter() != null || state.securitySuite() != null ||
                state.lastObis() != null;
    }

    private boolean isCasualGreetingOnly(WorkflowState state) {
        return CasualQueryClassifier.isCasualNonTechnicalQuery(state.rawInput());
    }

    private String nvl(String val) {
        return val == null ? "n/a" : val;
    }

    private boolean isSecurityExplain(WorkflowState state) {
        return state.intent() != null && "SECURITY_EXPLAIN".equals(state.intent().name());
    }

    private void appendDetailedGroundedExplainGuidance(StringBuilder prompt) {
        prompt.append("Answer directly and specifically using the retrieved text.\n");
        prompt.append("For security topics: name the specific mechanism, level, and algorithm involved (e.g., HLS Level 3, GMAC, AES-GCM-128).\n");
        prompt.append("For HLS: describe the 5-step challenge-response sequence.\n");
        prompt.append("For security suites: state exactly what each suite provides.\n");
        prompt.append("Do not give a generic summary - give technical detail.\n");
        prompt.append("3-5 sentences minimum for any security or protocol explanation.\n");
        prompt.append("Start directly with a clean answer in your own words.\n");
        prompt.append("CRITICAL FORMATTING RULES:\n");
        prompt.append("- Never begin your answer by quoting or paraphrasing the opening line of a retrieved document.\n");
        prompt.append("- Never start with a section number, clause reference, or document title (e.g. do not start with '10 |', 'This Clause', 'Section', 'Function:', or similar).\n");
        prompt.append("- Always begin with a direct, clean statement answering the question.\n");
        prompt.append("- Example of WRONG opening: 'This Clause contains examples of...'\n");
        prompt.append("- Example of RIGHT opening: 'The DLMS Green Book defines the...'\n");
        prompt.append("- Use the retrieved text as reference, not as a template to copy.\n");
        prompt.append("Format your response using markdown:\n");
        prompt.append("- Use **bold** for important terms and protocol names.\n");
        prompt.append("- Use bullet points for lists of items.\n");
        prompt.append("- Use ## for section headers when the answer has multiple parts.\n");
        prompt.append("- Use `code` for hex values, field names, and code identifiers.\n");
        prompt.append("- Keep responses concise - 3-8 sentences for most answers.\n");
        prompt.append("Do not append a Sources: line inside the answer body; citations are rendered separately.\n");
    }

    private void appendSecurityExplainerMode(StringBuilder prompt, boolean retrievalSecurityMode) {
        prompt.append("=== SECURITY EXPLAINER MODE ===\n");
        if (retrievalSecurityMode) {
            prompt.append("This section is authoritative for Answer Mode RETRIEVAL_SECURITY.\n");
            prompt.append("Use the RELEVANT DOCUMENTATION section as mandatory primary evidence for the final answer.\n");
            prompt.append("Do not fall back to generic summaries when the retrieved excerpts already define the mechanism.\n");
        }
        prompt.append("You are explaining DLMS/COSEM security mechanisms at a theory level suitable for engineers.\n");
        prompt.append("Reference security suite numbers, authentication levels, and encryption algorithms by name.\n");
        prompt.append("Answer with a direct opening sentence followed by 1-2 short technical paragraphs or compact bullets.\n");
        prompt.append("For LLS vs HLS, explain static secret versus challenge-response.\n");
        prompt.append("For replay protection, explain monotonic frame counter validation and stale-counter rejection.\n");
        prompt.append("For association reject diagnostics, explain the likely reason for the rejection before giving remediation.\n");
        prompt.append("map the diagnostic code only when the supporting context justifies it.\n");
        prompt.append("Give practical troubleshooting checks for authentication, application context, and negotiated security mismatches.\n");
        prompt.append("Do not confuse security mechanisms with LN referencing or unrelated addressing concepts.\n");
        prompt.append("NEVER output actual keys, passwords, challenge values, live frame-counter values, or attack steps.\n");
        appendDetailedGroundedExplainGuidance(prompt);
        prompt.append("\n");
    }

    private void appendProtocolAnswerStyle(StringBuilder prompt, boolean documentationMode, boolean retrievalDocsMode) {
        prompt.append("=== PROTOCOL ANSWER STYLE ===\n");
        if (retrievalDocsMode) {
            prompt.append("This section is authoritative for Answer Mode RETRIEVAL_DOCS.\n");
            prompt.append("Use the RELEVANT DOCUMENTATION section as mandatory primary evidence for the final answer.\n");
            prompt.append("Do not degrade to a generic summary when the retrieved text already defines the protocol term or mechanism.\n");
        }
        prompt.append("Answer directly and technically.\n");
        prompt.append("Never describe HDLC as Ethernet.\n");
        prompt.append("Do not mention physical-layer checksums when defining APDU unless the user asks about integrity or decode errors.\n");
        prompt.append("When the question asks what something is, define it directly in the first sentence.\n");
        prompt.append("Prefer standards-grounded wording over generic summaries.\n");
        prompt.append("Use polished prose or compact bullets, not markdown headings.\n");
        if (documentationMode) {
            prompt.append("Use markdown for readability.\n");
            prompt.append("Do not emit raw HTML.\n");
            prompt.append("Do not emit LaTeX or math markup such as \\boxed{}.\n");
            prompt.append("Define the topic directly from the retrieved excerpt instead of giving a generic abstraction.\n");
            prompt.append("Base the first sentence on the strongest supporting excerpt body, not on prior assumptions.\n");
            prompt.append("For conceptual HDLC structure questions, describe the general frame layout and role of each major field.\n");
            prompt.append("Do not invent a specific frame subtype or sample instance unless the user supplied one.\n");
            prompt.append("If the top supporting excerpt is a Confluence page title or topic, start by defining that title in one sentence using the excerpt body.\n");
            prompt.append("For SICONIA and Confluence-backed operational pages, prefer the operational meaning in the excerpt over generic DLMS protocol guesses.\n");
            prompt.append("For standards questions, prefer the standard term itself in the first sentence instead of a vague summary.\n");
            appendDetailedGroundedExplainGuidance(prompt);
        }
        prompt.append("\n");
    }

    private void appendGroundedAnswerContext(StringBuilder prompt, WorkflowState state) {
        if (state == null || state.groundedAnswerContext() == null) {
            return;
        }
        var context = state.groundedAnswerContext();
        prompt.append("=== GROUNDED ANSWER CONTEXT ===\n");
        prompt.append("Answer Mode: ").append(context.mode()).append("\n");
        prompt.append("Selected Strategy: ").append(context.selectedStrategy()).append("\n");
        prompt.append("Confidence: ").append(String.format("%.2f", context.confidence())).append("\n");
        prompt.append("Tentative: ").append(context.tentative()).append("\n");
        prompt.append("Do not echo the Answer Mode, Selected Strategy, Confidence, Tentative, or Warnings fields in the final answer.\n");
        switch (context.mode()) {
            case DETERMINISTIC_DECODE -> {
                prompt.append("Use the deterministic decode result as primary evidence.\n");
                prompt.append("Start with a direct interpretation and explain the protocol or operational role in 2-4 short sentences.\n");
                prompt.append("Never invent fields that are not present in the deterministic decode.\n");
                prompt.append("If the frame is tentative or corrupted, explain only the trustworthy outer-frame role and explicitly state that payload interpretation is unsafe.\n");
                prompt.append("Never emit Sources: inside the answer body for pure deterministic decode explanations.\n");
                prompt.append("For SNRM, describe it as a link-setup request. Do not say the link is already in normal response mode unless a confirming UA frame is present.\n");
                prompt.append("When a GET_RESPONSE only identifies an OBIS/object reference or returned structure, describe that returned object directly. Do not claim a live meter reading, current state, or current measurement value unless the deterministic decode explicitly shows one.\n");
                if (hasEmptyRetrievalAfterPlannerDocsAttempt(state)) {
                    prompt.append("You have no retrieved documentation for this frame.\n");
                    prompt.append("Explain ONLY what the decode result shows.\n");
                    prompt.append("Do NOT describe what happens next in the protocol.\n");
                    prompt.append("Do NOT invent peer responses, reply frames, or extra control states.\n");
                    prompt.append("Describe only the frame type, what it means, and the field values that are actually present.\n");
                    prompt.append("Do NOT speculate about SRR, ACK, RCV, or server responses.\n");
                } else {
                    prompt.append("Mention the next expected state or next technical check when the structured data supports it.\n");
                }
            }
            case DETERMINISTIC_SICONIA -> {
                prompt.append("Use the structured SICONIA analysis as primary evidence.\n");
                prompt.append("Start with a direct operational interpretation and explain why it matters in 2-4 short sentences.\n");
                prompt.append("Summarize the key facts without repeating the full structured panel.\n");
                prompt.append("Never invent alarm, XML, or log fields that are not present in the structured analysis.\n");
                prompt.append("Never emit Sources: inside the answer body for pure deterministic SICONIA explanations.\n");
            }
            case SESSION_RECALL -> prompt.append("Answer from SESSION STATE and SESSION HISTORY first. Do not search documentation when the session context already answers the question.\n");
            case RETRIEVAL_DOCS -> prompt.append("Use retrieved documentation as the primary evidence. Follow the PROTOCOL ANSWER STYLE section as authoritative, and paraphrase the strongest definition before expanding with technical detail.\n");
            case RETRIEVAL_SECURITY -> prompt.append("Use retrieved documentation as the primary evidence. Follow the SECURITY EXPLAINER MODE section as authoritative, and paraphrase the strongest definition before expanding with technical detail.\n");
            case AMBIGUOUS -> prompt.append("Do not guess. Compare the ranked candidates and explain the best grounded interpretations only.\n");
            case CASUAL_HELP -> prompt.append("Respond briefly and conversationally. Do not add citations or protocol detail unless the user asks.\n");
            case FAILURE -> prompt.append("Be explicit about the failure or uncertainty. Do not invent protocol meaning when the deterministic result is incomplete.\n");
        }
        if (!context.warnings().isEmpty()) {
            prompt.append("Warnings: ").append(String.join("; ", context.warnings())).append("\n");
        }
        if (!context.anomalies().isEmpty()) {
            prompt.append("Anomalies: ").append(String.join("; ", context.anomalies())).append("\n");
        }
        prompt.append("\n");
    }

    private void appendGroundedFactBundle(StringBuilder prompt, GroundedFactBundle bundle) {
        if (bundle == null || bundle.isEmpty()) {
            return;
        }
        prompt.append("=== AUTHORITATIVE FACT BUNDLE ===\n");
        prompt.append("Topic Family: ").append(bundle.family().name());
        if (!bundle.topicLabel().isBlank()) {
            prompt.append(" (").append(bundle.topicLabel()).append(")");
        }
        prompt.append("\n");
        if (!bundle.authoritativeFacts().isEmpty()) {
            prompt.append("Authoritative facts:\n");
            for (String fact : bundle.authoritativeFacts()) {
                prompt.append("- ").append(fact).append("\n");
            }
        }
        prompt.append("Use this fact bundle as authoritative grounding when the retrieved snippets are terse.\n\n");
    }

    private void appendPlannerObservations(StringBuilder prompt, WorkflowState state) {
        if (state == null || (!state.plannerUsed() && (state.toolTrace() == null || state.toolTrace().isEmpty())
                && (state.plannerFallbackReason() == null || state.plannerFallbackReason().isBlank()))) {
            return;
        }
        prompt.append("=== ORCHESTRATION OBSERVATIONS ===\n");
        if (state.orchestrationMode() != null) {
            prompt.append("Orchestration Mode: ").append(state.orchestrationMode()).append("\n");
        }
        prompt.append("Planner Used: ").append(state.plannerUsed()).append("\n");
        if (state.plannerFallbackReason() != null && !state.plannerFallbackReason().isBlank()) {
            prompt.append("Planner Fallback Reason: ").append(state.plannerFallbackReason()).append("\n");
        }
        if (state.toolTrace() != null && !state.toolTrace().isEmpty()) {
            prompt.append("Tool observations:\n");
            for (ToolTraceEntry entry : state.toolTrace()) {
                prompt.append("- ").append(entry.toolName())
                        .append(" [").append(entry.provenance()).append("]: ")
                        .append(entry.summary())
                        .append("\n");
            }
        }
        prompt.append("Use these observations as grounded evidence for the final answer when relevant.\n\n");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean hasEmptyRetrievalAfterPlannerDocsAttempt(WorkflowState state) {
        if (state == null || state.orchestrationMode() != com.company.dlms.domain.orchestration.OrchestrationMode.STRUCTURED_PLUS_AGENTIC) {
            return false;
        }
        if (state.retrievalResults() != null && !state.retrievalResults().isEmpty()) {
            return false;
        }
        if (state.toolTrace() == null || state.toolTrace().isEmpty()) {
            return false;
        }
        return state.toolTrace().stream()
                .filter(entry -> "search_docs".equalsIgnoreCase(entry.toolName()))
                .map(entry -> entry.summary() == null ? "" : entry.summary().toLowerCase(Locale.ROOT))
                .anyMatch(summary -> summary.contains("no supporting documentation snippets were recovered"));
    }

    private void appendProfileResult(StringBuilder prompt, ProfileResult profileResult) {
        prompt.append("=== PROFILE DATA ===\n");
        prompt.append("Profile Type: ").append(profileResult.profileType()).append("\n");
        prompt.append("Profile OBIS: ").append(profileResult.obis() == null ? "n/a" : profileResult.obis()).append("\n");
        prompt.append("Capture Objects (").append(profileResult.captureObjectCount()).append(" columns):\n");
        for (ProfileColumn column : profileResult.columns()) {
            prompt.append("  [").append(column.index()).append("] ")
                    .append(column.obis())
                    .append(" — ")
                    .append(column.description() == null ? "Unknown" : column.description());
            if (column.unit() != null && !column.unit().isBlank()) {
                prompt.append(" (").append(column.unit()).append(")");
            }
            prompt.append("\n");
        }
        prompt.append("Data (").append(profileResult.entryCount()).append(" rows):\n");
        int showRows = Math.min(5, profileResult.rows().size());
        for (int index = 0; index < showRows; index++) {
            ProfileRow row = profileResult.rows().get(index);
            prompt.append("  Row ").append(index + 1);
            if (row.timestamp() != null) {
                prompt.append(" [").append(row.timestamp()).append("]");
            }
            prompt.append(": ");
            String renderedCells = row.cells().stream()
                    .map(ProfileCell::displayString)
                    .collect(Collectors.joining(", "));
            prompt.append(renderedCells).append("\n");
        }
        if (profileResult.entryCount() > showRows) {
            prompt.append("  ... and ").append(profileResult.entryCount() - showRows).append(" more rows\n");
        }
        prompt.append("\n");
    }

    private void appendSiconiaProcessingGuidance(StringBuilder prompt, SiconiaProcessingMetadata metadata) {
        if (metadata == null) {
            return;
        }
        prompt.append("Processing Provenance: ").append(metadata.provenance()).append("\n");
        if (metadata.extractorNote() != null && !metadata.extractorNote().isBlank()) {
            prompt.append("Extractor Note: ").append(metadata.extractorNote()).append("\n");
        }
        if (metadata.warnings() != null && !metadata.warnings().isEmpty()) {
            prompt.append("Processing Warnings: ").append(String.join("; ", metadata.warnings())).append("\n");
        }
        if (metadata.provenance() == ParseProvenance.STRUCTURED_DIRECT) {
            prompt.append("Use the recovered structured facts as ground truth.\n");
        } else if (metadata.provenance() == ParseProvenance.STRUCTURED_HEURISTIC) {
            prompt.append("Use the recovered structured facts as primary evidence and mention ambiguity only where relevant.\n");
        } else if (metadata.provenance() == ParseProvenance.RAW_FALLBACK) {
            prompt.append("Interpret the XML from raw input only.\n");
            prompt.append("Do not claim that a full structured parse succeeded.\n");
            prompt.append("Use cautious language when fields were not recovered deterministically.\n");
        }
    }

    private void appendDlmsProcessingGuidance(StringBuilder prompt, DlmsProcessingMetadata metadata) {
        if (metadata == null) {
            return;
        }
        prompt.append("Processing Provenance: ").append(metadata.provenance()).append("\n");
        prompt.append("Processing Kind: ").append(metadata.normalizedKind()).append("\n");
        if (metadata.extractorNote() != null && !metadata.extractorNote().isBlank()) {
            prompt.append("Extractor Note: ").append(metadata.extractorNote()).append("\n");
        }
        if (metadata.warnings() != null && !metadata.warnings().isEmpty()) {
            prompt.append("Processing Warnings: ").append(String.join("; ", metadata.warnings())).append("\n");
        }
        if (metadata.provenance() == ParseProvenance.STRUCTURED_DIRECT) {
            prompt.append("Use the deterministic decode facts as ground truth.\n");
        } else if (metadata.provenance() == ParseProvenance.STRUCTURED_HEURISTIC) {
            prompt.append("Use the recovered deterministic payload as primary evidence.\n");
            prompt.append("Do not overstate certainty about the wrapper text around the payload.\n");
        } else if (metadata.provenance() == ParseProvenance.RAW_FALLBACK) {
            prompt.append("Do not claim that a deterministic protocol decode succeeded.\n");
            prompt.append("Use cautious language and avoid inventing byte-level structure.\n");
        }
    }

    private void appendDirectDlmsDecode(StringBuilder prompt, DecodeResult dr) {
        DlmsNormalizedKind kind = dr.processingMetadata() != null ? dr.processingMetadata().normalizedKind() : null;
        if (kind == DlmsNormalizedKind.OBIS_QUERY) {
            prompt.append("=== OBIS RESOLUTION ===\n");
            prompt.append("This result came from deterministic OBIS resolution, not from HDLC frame parsing.\n");
            prompt.append("Start with the direct meaning of the OBIS code.\n");
            prompt.append("Keep the answer compact and technical.\n\n");
            return;
        }

        if (kind == DlmsNormalizedKind.APDU_HEX) {
            prompt.append("=== DIRECT APDU DECODE ===\n");
            prompt.append("The user provided an APDU payload without an HDLC envelope.\n");
            prompt.append("Treat the APDU type and AXDR tree below as deterministic decode results.\n");
            prompt.append("Do not invent missing HDLC framing details.\n");
            prompt.append("Do not claim that an HDLC frame was decoded.\n");
            prompt.append("Do not mention SICONIA or any non-DLMS domain.\n");
            prompt.append("Preferred response shape:\n");
            prompt.append("- What it means: interpret the APDU in protocol terms\n");
            prompt.append("- Impact: mention the most relevant DLMS/COSEM consequence\n");
            prompt.append("- Next step: point to the AXDR/OBIS details if useful\n\n");
            return;
        }

        if (kind == DlmsNormalizedKind.AXDR_HEX) {
            prompt.append("=== DIRECT AXDR DECODE ===\n");
            prompt.append("The user provided raw AXDR payload bytes without an APDU or HDLC envelope.\n");
            prompt.append("Treat the AXDR tree below as deterministic decode output.\n");
            prompt.append("Do not claim that a full APDU or HDLC frame was decoded.\n");
            prompt.append("Do not invent a protocol operation or completion status that is not present in the decoded fields.\n");
            prompt.append("Preferred response shape:\n");
            prompt.append("- What it means: summarize the AXDR structure or values\n");
            prompt.append("- Impact: mention any OBIS/object identifiers that were recovered\n");
            prompt.append("- Next step: point to the structured payload details when helpful\n\n");
        }
    }
}
