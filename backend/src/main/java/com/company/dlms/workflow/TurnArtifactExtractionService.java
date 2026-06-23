package com.company.dlms.workflow;

import com.company.dlms.agent.dlms.DlmsInputNormalization;
import com.company.dlms.agent.dlms.DlmsInputNormalizer;
import com.company.dlms.agent.siconia.SiconiaInputNormalization;
import com.company.dlms.agent.siconia.SiconiaInputNormalizer;
import com.company.dlms.domain.InputClass;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

@Component
public class TurnArtifactExtractionService {

    static final int MAX_ARTIFACTS = 8;

    private static final Pattern BLANK_LINE_SPLIT = Pattern.compile("(?:\\r?\\n){2,}");
    private static final Pattern USER_CONTEXT_SPLIT = Pattern.compile("(?is)\\[user context]\\s*");
    private static final Pattern ATTACHMENT_HEADER = Pattern.compile("(?im)^\\[attachment\\s+\\d+:[^\\]]+]");
    private static final Pattern DIRECT_ALARM = Pattern.compile("(?i)^0x[0-9a-f]{1,8}$");
    private static final Pattern XML_BLOCK = Pattern.compile("(?is)<[a-z_][\\w:.-]*(?:\\s[^>]*)?>.*");
    private static final Pattern LOG_SIGNAL = Pattern.compile("(?i)\\[(WAN|PLC|RF|HES|DLMS)]\\s+(TRACE|DEBUG|INFO|WARN|ERROR|CRITICAL|FATAL)\\b");

    private final DlmsInputNormalizer dlmsInputNormalizer;
    private final SiconiaInputNormalizer siconiaInputNormalizer;

    public TurnArtifactExtractionService(
            DlmsInputNormalizer dlmsInputNormalizer,
            SiconiaInputNormalizer siconiaInputNormalizer
    ) {
        this.dlmsInputNormalizer = Objects.requireNonNull(dlmsInputNormalizer, "dlmsInputNormalizer");
        this.siconiaInputNormalizer = Objects.requireNonNull(siconiaInputNormalizer, "siconiaInputNormalizer");
    }

    public TurnArtifactExtraction extract(WorkflowRequest request) {
        List<TurnArtifact> explicitArtifacts = sanitizeExplicitArtifacts(request.artifacts());
        if (!explicitArtifacts.isEmpty()) {
            boolean tooMany = explicitArtifacts.size() > MAX_ARTIFACTS;
            List<TurnArtifact> limitedArtifacts = tooMany
                    ? List.copyOf(explicitArtifacts.subList(0, MAX_ARTIFACTS))
                    : List.copyOf(explicitArtifacts);
            return new TurnArtifactExtraction(
                    limitedArtifacts,
                    deriveTurnInstructionFromExplicitRequest(request.rawInput(), limitedArtifacts),
                    true,
                    tooMany
            );
        }
        return extractFromRawInput(request.rawInput());
    }

    private TurnArtifactExtraction extractFromRawInput(String rawInput) {
        String input = rawInput == null ? "" : rawInput.trim();
        if (input.isBlank()) {
            return new TurnArtifactExtraction(List.of(), null, false, false);
        }

        List<TurnArtifact> artifacts = new ArrayList<>();
        List<String> instructionBlocks = new ArrayList<>();

        for (String rawBlock : BLANK_LINE_SPLIT.split(input)) {
            String block = rawBlock == null ? "" : rawBlock.trim();
            if (block.isBlank()) {
                continue;
            }

            if (shouldPreserveWholeStructuredBlock(block) && looksLikeStructuredArtifact(block)) {
                artifacts.add(new TurnArtifact(null, ArtifactSource.PASTED_BLOCK, null, block, detectHintedInputClass(block), null));
                continue;
            }

            LineSplit lineSplit = splitStructuredLines(block);
            if (!lineSplit.artifacts().isEmpty()) {
                artifacts.addAll(lineSplit.artifacts());
                instructionBlocks.addAll(lineSplit.instructions());
                continue;
            }

            if (looksLikeStructuredArtifact(block)) {
                artifacts.add(new TurnArtifact(null, ArtifactSource.PASTED_BLOCK, null, block, detectHintedInputClass(block), null));
                continue;
            }

            instructionBlocks.add(block);
        }

        if (artifacts.isEmpty()) {
            artifacts.add(new TurnArtifact(null, ArtifactSource.PASTED_BLOCK, null, input, InputClass.QUERY, null));
            return new TurnArtifactExtraction(List.copyOf(artifacts), null, false, false);
        }

        boolean tooMany = artifacts.size() > MAX_ARTIFACTS;
        List<TurnArtifact> limitedArtifacts = tooMany
                ? List.copyOf(artifacts.subList(0, MAX_ARTIFACTS))
                : List.copyOf(artifacts);
        String instruction = instructionBlocks.isEmpty() ? null : String.join("\n\n", instructionBlocks).trim();
        if (instruction != null && instruction.isBlank()) {
            instruction = null;
        }
        return new TurnArtifactExtraction(limitedArtifacts, instruction, false, tooMany);
    }

    private List<TurnArtifact> sanitizeExplicitArtifacts(List<TurnArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return List.of();
        }
        return artifacts.stream()
                .filter(Objects::nonNull)
                .map(artifact -> new TurnArtifact(
                        artifact.artifactId(),
                        artifact.source(),
                        artifact.filename(),
                        artifact.text() == null ? "" : artifact.text().trim(),
                        artifact.hintedInputClass(),
                        artifact.suggestedEndpoint()
                ))
                .filter(artifact -> !artifact.text().isBlank())
                .toList();
    }

    private String deriveTurnInstructionFromExplicitRequest(String rawInput, List<TurnArtifact> artifacts) {
        String input = rawInput == null ? "" : rawInput.trim();
        if (input.isBlank()) {
            return null;
        }

        String[] userContextSplit = USER_CONTEXT_SPLIT.split(input, 2);
        if (userContextSplit.length == 2) {
            String context = userContextSplit[1].trim();
            return context.isBlank() ? null : context;
        }

        if (ATTACHMENT_HEADER.matcher(input).find()) {
            return null;
        }

        if (artifacts.size() == 1 && input.equals(artifacts.getFirst().text().trim())) {
            return null;
        }

        return input;
    }

    private LineSplit splitStructuredLines(String block) {
        List<String> lines = block.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
        if (lines.size() < 2) {
            return LineSplit.empty();
        }
        if (lines.stream().allMatch(this::looksLikeStructuredArtifact)) {
            return new LineSplit(
                    lines.stream()
                            .map(line -> new TurnArtifact(null, ArtifactSource.PASTED_BLOCK, null, line, detectHintedInputClass(line), null))
                            .toList(),
                    List.of()
            );
        }
        if (shouldPreserveWholeStructuredBlock(block)) {
            return LineSplit.empty();
        }

        List<String> structuredLines = lines.stream()
                .filter(this::looksLikeStructuredArtifact)
                .toList();
        if (structuredLines.isEmpty()) {
            return LineSplit.empty();
        }

        List<String> instructionLines = lines.stream()
                .filter(line -> !looksLikeStructuredArtifact(line))
                .toList();

        return new LineSplit(
                structuredLines.stream()
                        .map(line -> new TurnArtifact(null, ArtifactSource.PASTED_BLOCK, null, line, detectHintedInputClass(line), null))
                        .toList(),
                instructionLines
        );
    }

    private boolean shouldPreserveWholeStructuredBlock(String block) {
        return LOG_SIGNAL.matcher(block).find()
                || (XML_BLOCK.matcher(block).matches() && block.contains("<"));
    }

    private boolean looksLikeStructuredArtifact(String value) {
        String input = value == null ? "" : value.trim();
        if (input.isBlank()) {
            return false;
        }
        if (DIRECT_ALARM.matcher(input).matches()) {
            return true;
        }
        if (LOG_SIGNAL.matcher(input).find()) {
            return true;
        }
        if (XML_BLOCK.matcher(input).matches() && input.contains("<")) {
            return true;
        }

        SiconiaInputNormalization siconiaNormalization = siconiaInputNormalizer.normalize(input, InputClass.QUERY);
        if (siconiaNormalization != null && siconiaNormalization.inputClass() != InputClass.QUERY) {
            return true;
        }

        DlmsInputNormalization dlmsNormalization = dlmsInputNormalizer.normalize(input, InputClass.QUERY);
        return dlmsNormalization != null;
    }

    private InputClass detectHintedInputClass(String value) {
        String input = value == null ? "" : value.trim();
        if (input.isBlank()) {
            return InputClass.QUERY;
        }
        if (DIRECT_ALARM.matcher(input).matches()) {
            return InputClass.ALARM_CODE;
        }
        if (LOG_SIGNAL.matcher(input).find()) {
            return InputClass.LOG_BLOCK;
        }
        if (XML_BLOCK.matcher(input).matches() && input.toLowerCase(Locale.ROOT).contains("<")) {
            return InputClass.XML_TRACE;
        }
        DlmsInputNormalization dlmsNormalization = dlmsInputNormalizer.normalize(input, InputClass.QUERY);
        if (dlmsNormalization != null && dlmsNormalization.kind() == com.company.dlms.domain.decoder.DlmsNormalizedKind.FRAME_HEX) {
            return InputClass.HEX_FRAME;
        }
        return InputClass.QUERY;
    }

    private record LineSplit(
            List<TurnArtifact> artifacts,
            List<String> instructions
    ) {
        private static LineSplit empty() {
            return new LineSplit(List.of(), List.of());
        }
    }
}
