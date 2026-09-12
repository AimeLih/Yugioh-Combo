package com.aimestart.yugiohsearch;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Service
public class ComboService {

    private final CardRepository cardRepository;

    public record ComboOption(
            Card card,
            String reason,
            int score,
            String label,
            String timing,
            String sourceZone,
            String destination,
            String cost,
            boolean oncePerTurn
    ) {}

    public record FusionMaterialSlot(
            String requirement,
            int count,
            List<Card> eligibleCards
    ) {}
    public record FusionMaterialPlan(
            String action,
            String availableFrom,
            String destination,
            List<FusionMaterialSlot> slots,
            List<String> restrictions
    ) {}
    public record EffectPrerequisiteRequest(
            String source,
            String effect,
            String sourceZone,
            Map<String, List<String>> zones
    ) {}
    public record EffectPrerequisiteResult(
            boolean available,
            String reason,
            List<String> legalSummonTargets
    ) {}

    // Internal route-building models

    private static class ComboDraft {
        private final Card card;
        private int score;
        private final LinkedHashSet<String> reasons = new LinkedHashSet<>();
        private final LinkedHashSet<String> timings = new LinkedHashSet<>();
        private final LinkedHashSet<String> sourceZones = new LinkedHashSet<>();
        private final LinkedHashSet<String> destinations = new LinkedHashSet<>();

        private ComboDraft(Card card) {
            this.card = card;
        }
    }

    private record EffectSection(String text, String sourceZone) {}

    private record ParsedMaterialSlot(String requirement, int count) {}
    private record ZonedMaterial(Card card, String zone, boolean specialSummonedThisTurn) {}

    // Construction

    public ComboService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    // Combo search orchestration and rulebook filtering

    public List<ComboOption> getPossibleCombos(String cardName) {
        return getPossibleCombos(cardName, null, null);
    }

    public List<ComboOption> getPossibleCombos(String cardName, String zone) {
        return getPossibleCombos(cardName, zone, null);
    }

    public List<ComboOption> getPossibleCombos(String cardName, String zone, String selectedEffect) {
        Card card = requireCard(cardName);

        String zoneLabel = normalizedZoneLabel(zone);
        String availableDescription = zoneLabel.isBlank()
                ? safeLower(normalizeCardText(card.getDescription()))
                : safeLower(effectTextForZone(card, zoneLabel));
        if (!zoneLabel.isBlank() && availableDescription.isBlank()) {
            return Collections.emptyList();
        }

        String normalizedEffect = safeLower(normalizeCardText(selectedEffect)).trim();
        final String description;
        if (!normalizedEffect.isBlank()) {
            if (!availableDescription.contains(normalizedEffect)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Selected effect does not belong to this card or zone");
            }
            description = normalizedEffect;
        } else {
            description = availableDescription;
        }

        List<ComboOption> fusionTargets = buildFusionSummonTargets(card, description, zoneLabel);
        List<ComboOption> curated = zoneLabel.isBlank() && normalizedEffect.isBlank()
                ? buildCuratedGuideCombos(card)
                : Collections.emptyList();
        Map<String, ComboDraft> drafts = new LinkedHashMap<>();

        buildExplicitComboRoutes(card, description, drafts);
        buildTextDrivenComboRoutes(card, description, drafts, zoneLabel, !normalizedEffect.isBlank());

        List<ComboOption> options = drafts.values().stream()
                .filter(draft -> draft.score > 0)
                .sorted(Comparator
                        .comparingInt((ComboDraft draft) -> draft.score).reversed()
                        .thenComparing(draft -> draft.card.getName(), String.CASE_INSENSITIVE_ORDER))
                .map(draft -> new ComboOption(
                        draft.card,
                        joinReasons(draft.reasons),
                        draft.score,
                        comboLabelFor(draft.card),
                        joinMetadata(draft.timings, "Immediate"),
                        zoneLabel.isBlank()
                                ? joinMetadata(draft.sourceZones, sourceZoneFor(card))
                                : zoneLabel,
                        joinMetadata(draft.destinations, "Varies"),
                        comboCostFor(card, draft.card, description),
                        hasOncePerTurnRestriction(draft.card)))
                .collect(Collectors.toList());

        return mergeComboOptions(fusionTargets, mergeComboOptions(curated, options)).stream()
                .filter(option -> followsRulebookRouteRules(card, option.card(), description))
                .collect(Collectors.toList());
    }

    private boolean followsRulebookRouteRules(Card source, Card target, String effectText) {
        String targetType = safeLower(target.getType());
        if (targetType.contains("fusion")) {
            return effectText.contains("fusion summon");
        }
        if (containsAny(targetType, "synchro", "xyz", "link", "ritual")) {
            // Do not present these routes until their material/Tribute state can be verified.
            return false;
        }

        if (!containsAny(effectText,
                "add to your hand",
                "add 1",
                "search",
                "special summon",
                "normal summon",
                "tribute summon",
                "set 1",
                "set it",
                "place 1",
                "place this card",
                "send to the gy",
                "send to the graveyard",
                "discard",
                "banish")) {
            return false;
        }

        List<String> relevantQuotedTerms = extractQuotedTerms(effectText).stream()
                .filter(term -> !safeLower(term).equals(safeLower(source.getName())))
                .collect(Collectors.toList());
        String mentionedCardName = mentionedCardReference(effectText);
        if (!mentionedCardName.isBlank()
                && (safeLower(target.getName()).equals(safeLower(mentionedCardName))
                || safeLower(target.getDescription()).contains(safeLower(mentionedCardName)))) {
            return true;
        }
        if (!relevantQuotedTerms.isEmpty()) {
            return relevantQuotedTerms.stream().anyMatch(term ->
                    safeLower(target.getName()).contains(safeLower(term))
                            || safeLower(target.getArchetype()).contains(safeLower(term)));
        }

        if (containsAny(effectText,
                "special summon this card",
                "normal summon this card",
                "set this card",
                "place this card",
                "banish this card")
                && !Pattern.compile(
                        "(?:add|summon|set|place|send|discard|banish|target)\\s+(?:up to\\s+)?(?:\\d+|one|a)\\s+",
                        Pattern.CASE_INSENSITIVE)
                        .matcher(effectText)
                        .find()) {
            return false;
        }

        return Pattern.compile(
                "(?:add|summon|set|place|send|discard|banish|target)\\s+(?:up to\\s+)?(?:\\d+|one|a)\\s+",
                Pattern.CASE_INSENSITIVE)
                .matcher(effectText)
                .find();
    }

    // Selectable cost and Fusion Material planning

    public FusionMaterialPlan getFusionMaterialPlan(String sourceName, String targetName) {
        return getFusionMaterialPlan(sourceName, targetName, null);
    }

    public FusionMaterialPlan getFusionMaterialPlan(
            String sourceName,
            String targetName,
            String selectedEffect
    ) {
        Card source = cardRepository.getCardByName(sourceName);
        Card target = cardRepository.getCardByName(targetName);
        if (source == null || target == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fusion source or target card not found");
        }
        String effectText = selectedEffect == null || selectedEffect.isBlank()
                ? source.getDescription()
                : selectedEffect;
        if (!safeLower(effectText).contains("fusion summon") || !isFusionMonster(target)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This route is not a Fusion Summon");
        }

        List<Card> cards = cardRepository.findAll().stream()
                .filter(card -> safeLower(card.getType()).contains("monster"))
                .filter(card -> !hasFusionMaterialProhibition(card))
                .sorted(Comparator.comparing(Card::getName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        List<FusionMaterialSlot> slots = parseFusionMaterialSlots(target).stream()
                .map(slot -> new FusionMaterialSlot(
                        slot.requirement(),
                        slot.count(),
                        cards.stream()
                                .filter(candidate -> matchesFusionMaterialRequirement(candidate, slot.requirement()))
                                .collect(Collectors.toList())))
                .collect(Collectors.toList());

        List<String> restrictions = new ArrayList<>();
        String sourceRestriction = fusionMaterialRestriction(source);
        if (!sourceRestriction.isBlank()) {
            restrictions.add(sourceRestriction);
        }

        String action = extractFusionMaterialAction(effectText);
        return new FusionMaterialPlan(
                action.isBlank() ? "Use the selected cards as Fusion Material" : capitalize(action),
                fusionMaterialSourceZones(action.isBlank() ? effectText : action),
                fusionMaterialDestination(effectText),
                slots,
                restrictions);
    }

    public FusionMaterialPlan getCardCostPlan(String sourceName, String targetName) {
        return getCardCostPlan(sourceName, targetName, null);
    }

    public FusionMaterialPlan getCardCostPlan(
            String sourceName,
            String targetName,
            String selectedEffect
    ) {
        Card source = cardRepository.getCardByName(sourceName);
        Card target = cardRepository.getCardByName(targetName);
        if (source == null || target == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cost source or target card not found");
        }

        String cost = comboCostFor(
                source,
                target,
                selectedEffect == null || selectedEffect.isBlank()
                        ? source.getDescription()
                        : selectedEffect);
        if (cost.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This route has no selectable card cost");
        }

        Matcher payment = Pattern.compile(
                "\\b(discard|tribute|banish|send|destroy|shuffle)\\s+(\\d+)\\s+([^,;.:]+)",
                Pattern.CASE_INSENSITIVE)
                .matcher(cost);
        List<FusionMaterialSlot> slots = new ArrayList<>();
        if (payment.find()) {
            String requirement = payment.group(3)
                    .replaceFirst("(?i)^(?:other|of your)\\s+", "")
                    .trim();
            List<Card> eligibleCards = cardRepository.findAll().stream()
                    .filter(card -> matchesGeneralCostRequirement(card, requirement))
                    .sorted(Comparator.comparing(Card::getName, String.CASE_INSENSITIVE_ORDER))
                    .collect(Collectors.toList());
            slots.add(new FusionMaterialSlot(
                    requirement,
                    Integer.parseInt(payment.group(2)),
                    eligibleCards));
        }

        return new FusionMaterialPlan(
                cost,
                generalCostSourceZone(cost),
                generalCostDestination(cost),
                slots,
                Collections.emptyList());
    }

    // Shared card lookup and restriction helpers

    private Card requireCard(String name) {
        Card card = cardRepository.getCardByName(name);
        if (card == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found: " + name);
        }
        return card;
    }

    private String oncePerTurnRule(Card card) {
        String desc = normalizeCardText(card.getDescription());

        if (Pattern.compile("you can only use each (?:effect|of the following effects).*?once per turn", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(desc).find()) {
            return "one of each";
        }
        if (Pattern.compile("you can only use (?:1|one) of the following effects.*?(?:once per turn|only once that turn)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(desc).find()) {
            return "one listed effect";
        }
        if (Pattern.compile("you can only activate (?:1|one) .*? per turn", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(desc).find()) {
            return "one activation";
        }
        if (Pattern.compile("you can only use (?:this|the) effect.*?once per turn", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(desc).find()) {
            return "one effect";
        }
        if (Pattern.compile("you can only use .*?(?:once per turn|only once that turn)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(desc).find()
                || desc.toLowerCase().contains("once per turn")) {
            return "once per turn";
        }
        return "Not once per turn";
    }

    private boolean hasOncePerTurnRestriction(Card card) {
        return !oncePerTurnRule(card).equals("Not once per turn");
    }

    // Fusion route discovery and material parsing

    private List<Card> getRelatedArchetypeCards(Card card) {
        if (card.getArchetype() == null || card.getArchetype().isBlank()) {
            return Collections.emptyList();
        }

        return cardRepository.findByArchetypeContainingIgnoreCase(card.getArchetype()).stream()
                .filter(other -> !Objects.equals(other.getId(), card.getId()))
                .sorted(Comparator
                        .comparingInt(Card::getWeight).reversed()
                        .thenComparing(Card::getName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    private List<ComboOption> buildFusionSummonTargets(Card card, String description, String sourceZoneOverride) {
        if (!description.contains("fusion summon")) {
            return Collections.emptyList();
        }

        String extractedRequiredMaterial = extractRequiredFusionMaterial(description);
        String printedRequiredMaterial = extractRequiredFusionMaterial(card.getDescription());
        final String requiredMaterial = !printedRequiredMaterial.isBlank()
                && printedRequiredMaterial.equalsIgnoreCase(extractedRequiredMaterial)
                ? printedRequiredMaterial
                : extractedRequiredMaterial;
        List<Card> candidates;
        if (requiredMaterial.isBlank()) {
            LinkedHashMap<String, Card> fusionCandidates = new LinkedHashMap<>();
            getRelatedArchetypeCards(card).stream()
                    .filter(this::isFusionMonster)
                    .forEach(candidate -> fusionCandidates.put(safeLower(candidate.getName()), candidate));
            cardRepository.findAll().stream()
                    .filter(this::isFusionMonster)
                    .forEach(candidate -> fusionCandidates.putIfAbsent(safeLower(candidate.getName()), candidate));
            candidates = new ArrayList<>(fusionCandidates.values());
        } else {
            candidates = cardRepository.findByTypeContainingIgnoreCaseAndDescriptionContainingIgnoreCase(
                    "Fusion",
                    requiredMaterial);
        }

        return candidates.stream()
                .filter(target -> !Objects.equals(target.getId(), card.getId()))
                .filter(this::isFusionMonster)
                .filter(this::hasRecognizableFusionMaterialLine)
                .filter(target -> requiredMaterial.isBlank()
                        || fusionMaterialLine(target).contains(safeLower(requiredMaterial)))
                .filter(target -> matchesFusionSummonTarget(target, description))
                .filter(target -> hasCompatibleFusionMaterialCount(description, target))
                .sorted(Comparator
                        .comparingInt(Card::getWeight).reversed()
                        .thenComparing(Card::getName, String.CASE_INSENSITIVE_ORDER))
                .map(target -> new ComboOption(
                        target,
                        requiredMaterial.isBlank()
                                ? card.getName() + " can Fusion Summon this monster when its material requirements are met"
                                : card.getName() + " can Fusion Summon this monster because it lists "
                                        + requiredMaterial + " as material",
                        200,
                        hasComboContinuationEffect(target) ? "fusion target" : "ender",
                        "Immediate",
                        sourceZoneOverride.isBlank() ? sourceZoneFor(card) : sourceZoneOverride,
                        "Extra Deck to Monster Zone",
                        fusionSummonCost(card, target, description),
                        hasOncePerTurnRestriction(target)))
                .collect(Collectors.toList());
    }

    public EffectPrerequisiteResult checkEffectPrerequisites(EffectPrerequisiteRequest request) {
        Card source = requireCard(request.source());
        String effectText = normalizeCardText(request.effect());
        Map<String, List<String>> zoneNames = request.zones() == null ? Collections.emptyMap() : request.zones();
        String costFailure = activationCostFailure(source, effectText, request.sourceZone(), zoneNames);
        if (!costFailure.isBlank()) {
            return new EffectPrerequisiteResult(false, costFailure, Collections.emptyList());
        }

        List<Card> allCards = cardRepository.findAll();
        Map<String, Card> cardsByName = allCards.stream().collect(Collectors.toMap(
                card -> safeLower(card.getName()),
                card -> card,
                (first, ignored) -> first,
                LinkedHashMap::new));

        String lowerEffect = safeLower(effectText);
        if (lowerEffect.contains("fusion summon")) {
            List<ComboOption> targets = buildFusionSummonTargets(
                    source,
                    lowerEffect,
                    normalizedZoneLabel(request.sourceZone()));
            String action = extractFusionMaterialAction(effectText);
            String availableFrom = fusionMaterialSourceZones(action.isBlank() ? effectText : action);
            List<ZonedMaterial> availableMaterials = fusionMaterialsFromZones(
                    availableFrom, zoneNames, cardsByName, allCards, source, effectText);
            List<String> legalTargets = targets.stream()
                    .filter(option -> canSatisfyFusionMaterials(option.card(), availableMaterials))
                    .map(option -> option.card().getName())
                    .distinct()
                    .collect(Collectors.toList());
            if (legalTargets.isEmpty()) {
                return new EffectPrerequisiteResult(
                        false,
                        "No legal Fusion Monster can be made with materials currently available in "
                                + availableFrom + ".",
                        Collections.emptyList());
            }
            return new EffectPrerequisiteResult(true, "", legalTargets);
        }

        if (lowerEffect.contains("synchro summon") || lowerEffect.contains("xyz summon")) {
            String kind = lowerEffect.contains("synchro summon") ? "Synchro" : "Xyz";
            String action = extractSummonMaterialAction(effectText);
            String availableFrom = fusionMaterialSourceZones(action.isBlank() ? "field" : action);
            List<Card> availableMaterials = materialCardsFromZones(availableFrom, zoneNames, cardsByName, allCards);
            List<String> legalTargets = allCards.stream()
                    .filter(card -> safeLower(card.getType()).contains(safeLower(kind)))
                    .filter(card -> matchesTypedSummonTarget(card, effectText, kind))
                    .filter(card -> canSatisfyTypedSummonMaterials(card, availableMaterials, kind))
                    .map(Card::getName)
                    .distinct()
                    .collect(Collectors.toList());
            if (legalTargets.isEmpty()) {
                return new EffectPrerequisiteResult(
                        false,
                        "No legal " + kind + " Monster can be made with materials currently available in "
                                + availableFrom + ".",
                        Collections.emptyList());
            }
            return new EffectPrerequisiteResult(true, "", legalTargets);
        }

        return new EffectPrerequisiteResult(true, "", null);
    }

    private String activationCostFailure(
            Card source,
            String effectText,
            String sourceZone,
            Map<String, List<String>> zones
    ) {
        String costClause = effectText.split(";", 2)[0];
        String lowerCost = safeLower(costClause);
        List<String> hand = zones.getOrDefault("hand", Collections.emptyList());

        if (lowerCost.contains("discard this card")) {
            return "hand".equalsIgnoreCase(sourceZone)
                    ? ""
                    : "This effect requires discarding this card from the Hand.";
        }

        Matcher discard = Pattern.compile("discard\\s+(\\d+|one|a)\\s+(?:other\\s+)?cards?", Pattern.CASE_INSENSITIVE)
                .matcher(costClause);
        if (discard.find()) {
            int required = wordCount(discard.group(1));
            int available = hand.size();
            if ("hand".equalsIgnoreCase(sourceZone)
                    && (lowerCost.contains("other card") || lowerCost.contains("special summon this card"))) {
                available -= (int) hand.stream().filter(name -> name.equalsIgnoreCase(source.getName())).findFirst().stream().count();
            }
            if (available < required) {
                return "This effect requires " + required + " card" + (required == 1 ? "" : "s")
                        + " in the Hand to discard.";
            }
        }

        Matcher tribute = Pattern.compile("tribute\\s+(\\d+|one|a)\\s+(?:other\\s+)?monsters?", Pattern.CASE_INSENSITIVE)
                .matcher(costClause);
        if (tribute.find()) {
            int required = wordCount(tribute.group(1));
            int available = zones.getOrDefault("monsterZone", Collections.emptyList()).size()
                    + zones.getOrDefault("extraMonsterZone", Collections.emptyList()).size();
            if (lowerCost.contains("other monster") && isFieldZone(sourceZone)) available--;
            if (available < required) {
                return "This effect requires " + required + " monster" + (required == 1 ? "" : "s")
                        + " on the field to Tribute.";
            }
        }
        return "";
    }

    private int wordCount(String value) {
        return switch (safeLower(value)) {
            case "a", "one" -> 1;
            default -> Integer.parseInt(value);
        };
    }

    private boolean isFieldZone(String zone) {
        String normalized = safeLower(zone);
        return normalized.contains("monster") || normalized.contains("field");
    }

    private List<Card> materialCardsFromZones(
            String availableFrom,
            Map<String, List<String>> zones,
            Map<String, Card> cardsByName,
            List<Card> allCards
    ) {
        String lowerZones = safeLower(availableFrom);
        List<Card> result = new ArrayList<>();
        if (lowerZones.contains("hand")) addNamedCards(result, zones.get("hand"), cardsByName);
        if (lowerZones.contains("field")) {
            addNamedCards(result, zones.get("monsterZone"), cardsByName);
            addNamedCards(result, zones.get("extraMonsterZone"), cardsByName);
        }
        if (lowerZones.contains("graveyard")) addNamedCards(result, zones.get("graveyard"), cardsByName);
        if (lowerZones.contains("banished")) addNamedCards(result, zones.get("banished"), cardsByName);
        if (lowerZones.contains("extra deck")) addNamedCards(result, zones.get("extraDeck"), cardsByName);
        if (lowerZones.contains("deck") && !lowerZones.equals("extra deck")) {
            allCards.stream()
                    .filter(card -> safeLower(card.getType()).contains("monster"))
                    .forEach(card -> {
                        result.add(card);
                        result.add(card);
                        result.add(card);
                    });
        }
        return result.stream().filter(card -> !hasFusionMaterialProhibition(card)).collect(Collectors.toList());
    }

    private void addNamedCards(List<Card> result, List<String> names, Map<String, Card> cardsByName) {
        if (names == null) return;
        names.stream().map(name -> cardsByName.get(safeLower(name))).filter(Objects::nonNull).forEach(result::add);
    }

    private List<ZonedMaterial> fusionMaterialsFromZones(
            String availableFrom,
            Map<String, List<String>> zones,
            Map<String, Card> cardsByName,
            List<Card> allCards,
            Card source,
            String effectText
    ) {
        String lowerZones = safeLower(availableFrom);
        boolean sourceWasSpecialSummoned = Pattern.compile(
                "if this card (?:is|was) (?:fusion|synchro|xyz|link|ritual|special) summoned",
                Pattern.CASE_INSENSITIVE).matcher(effectText).find();
        List<ZonedMaterial> result = new ArrayList<>();
        if (lowerZones.contains("hand")) {
            addZonedCards(result, zones.get("hand"), cardsByName, "hand", source, sourceWasSpecialSummoned);
        }
        if (lowerZones.contains("field")) {
            addZonedCards(result, zones.get("monsterZone"), cardsByName, "field", source, sourceWasSpecialSummoned);
            addZonedCards(result, zones.get("extraMonsterZone"), cardsByName, "field", source, sourceWasSpecialSummoned);
        }
        if (lowerZones.contains("graveyard")) {
            addZonedCards(result, zones.get("graveyard"), cardsByName, "graveyard", source, sourceWasSpecialSummoned);
        }
        if (lowerZones.contains("banished")) {
            addZonedCards(result, zones.get("banished"), cardsByName, "banished", source, sourceWasSpecialSummoned);
        }
        if (lowerZones.contains("extra deck")) {
            addZonedCards(result, zones.get("extraDeck"), cardsByName, "extra deck", source, sourceWasSpecialSummoned);
        }
        if (lowerZones.contains("deck") && !lowerZones.equals("extra deck")) {
            allCards.stream()
                    .filter(card -> safeLower(card.getType()).contains("monster"))
                    .forEach(card -> {
                        result.add(new ZonedMaterial(card, "deck", false));
                        result.add(new ZonedMaterial(card, "deck", false));
                        result.add(new ZonedMaterial(card, "deck", false));
                    });
        }
        return result.stream()
                .filter(candidate -> !hasFusionMaterialProhibition(candidate.card()))
                .collect(Collectors.toList());
    }

    private void addZonedCards(
            List<ZonedMaterial> result,
            List<String> names,
            Map<String, Card> cardsByName,
            String zone,
            Card source,
            boolean sourceWasSpecialSummoned
    ) {
        if (names == null) return;
        names.stream()
                .map(name -> cardsByName.get(safeLower(name)))
                .filter(Objects::nonNull)
                .map(card -> new ZonedMaterial(
                        card,
                        zone,
                        sourceWasSpecialSummoned && zone.equals("field")
                                && Objects.equals(card.getId(), source.getId())))
                .forEach(result::add);
    }

    private boolean canSatisfyFusionMaterials(Card target, List<ZonedMaterial> candidates) {
        List<String> requirements = parseFusionMaterialSlots(target).stream()
                .flatMap(slot -> Collections.nCopies(slot.count(), slot.requirement()).stream())
                .collect(Collectors.toList());
        return assignZonedMaterialRequirements(
                requirements, 0, candidates, new boolean[candidates.size()], new ArrayList<>());
    }

    private boolean assignZonedMaterialRequirements(
            List<String> requirements,
            int requirementIndex,
            List<ZonedMaterial> candidates,
            boolean[] used,
            List<ZonedMaterial> chosen
    ) {
        if (requirementIndex >= requirements.size()) {
            return satisfiesCombinedFusionRequirements(requirements, chosen);
        }
        for (int index = 0; index < candidates.size(); index++) {
            ZonedMaterial candidate = candidates.get(index);
            if (used[index] || !matchesFusionMaterialRequirement(candidate.card(), requirements.get(requirementIndex))
                    || !matchesMaterialZoneRequirement(candidate, requirements.get(requirementIndex))) {
                continue;
            }
            used[index] = true;
            chosen.add(candidate);
            if (assignZonedMaterialRequirements(requirements, requirementIndex + 1, candidates, used, chosen)) {
                return true;
            }
            chosen.remove(chosen.size() - 1);
            used[index] = false;
        }
        return false;
    }

    private boolean matchesMaterialZoneRequirement(ZonedMaterial candidate, String requirement) {
        String normalized = safeLower(requirement);
        if (containsAny(normalized, "face-down", "set monster")) return false;
        if (normalized.contains("special summoned this turn") && !candidate.specialSummonedThisTurn()) return false;
        if (containsAny(normalized, "in the hand", "in your hand", "from the hand", "from your hand")) {
            return candidate.zone().equals("hand");
        }
        if (containsAny(normalized, "on the field", "on your field", "you control")) {
            return candidate.zone().equals("field");
        }
        if (containsAny(normalized, "in the gy", "in your gy", "in the graveyard", "in your graveyard")) {
            return candidate.zone().equals("graveyard");
        }
        return true;
    }

    private boolean satisfiesCombinedFusionRequirements(
            List<String> requirements,
            List<ZonedMaterial> chosen
    ) {
        String combined = safeLower(String.join(" + ", requirements));
        if (combined.contains("with the same name")) {
            return chosen.stream().map(candidate -> safeLower(candidate.card().getName())).distinct().count() == 1;
        }
        if (combined.contains("with different names")) {
            return chosen.stream().map(candidate -> safeLower(candidate.card().getName())).distinct().count()
                    == chosen.size();
        }
        return true;
    }

    private boolean assignMaterialRequirements(
            List<String> requirements,
            int requirementIndex,
            List<Card> candidates,
            boolean[] used
    ) {
        if (requirementIndex >= requirements.size()) return true;
        for (int index = 0; index < candidates.size(); index++) {
            if (used[index] || !matchesFusionMaterialRequirement(candidates.get(index), requirements.get(requirementIndex))) {
                continue;
            }
            used[index] = true;
            if (assignMaterialRequirements(requirements, requirementIndex + 1, candidates, used)) return true;
            used[index] = false;
        }
        return false;
    }

    private String extractSummonMaterialAction(String effectText) {
        Matcher matcher = Pattern.compile("using\\s+[^.;]*?(?:as materials?|to (?:synchro|xyz) summon)[^.;]*", Pattern.CASE_INSENSITIVE)
                .matcher(effectText);
        return matcher.find() ? matcher.group().trim() : "";
    }

    private boolean matchesTypedSummonTarget(Card target, String effectText, String kind) {
        Matcher maximumLevel = Pattern.compile(
                "level\\s+(\\d+)\\s+or lower\\s+" + kind + " monster",
                Pattern.CASE_INSENSITIVE).matcher(effectText);
        if (maximumLevel.find() && (target.getLevel() == null
                || target.getLevel() > Integer.parseInt(maximumLevel.group(1)))) return false;
        Matcher minimumLevel = Pattern.compile(
                "level\\s+(\\d+)\\s+or higher\\s+" + kind + " monster",
                Pattern.CASE_INSENSITIVE).matcher(effectText);
        return !minimumLevel.find() || (target.getLevel() != null
                && target.getLevel() >= Integer.parseInt(minimumLevel.group(1)));
    }

    private boolean canSatisfyTypedSummonMaterials(Card target, List<Card> candidates, String kind) {
        String requirement = fusionMaterialRequirement(target);
        if (kind.equals("Xyz")) {
            Matcher xyz = Pattern.compile("^(\\d+)\\s+Level\\s+(\\d+)\\s+(.+?)?monsters?", Pattern.CASE_INSENSITIVE)
                    .matcher(requirement);
            if (!xyz.find()) return false;
            int count = Integer.parseInt(xyz.group(1));
            int level = Integer.parseInt(xyz.group(2));
            String descriptor = xyz.group(3) == null ? "monster" : xyz.group(3).trim() + " monster";
            return candidates.stream().filter(card -> card.getLevel() != null && card.getLevel() == level)
                    .filter(card -> matchesFusionMaterialRequirement(card, descriptor)).count() >= count;
        }

        List<ParsedMaterialSlot> slots = parseFusionMaterialSlots(target);
        int minimumCount = slots.stream().mapToInt(ParsedMaterialSlot::count).sum();
        return findSynchroMaterialSet(target, candidates, slots, minimumCount, 0, new ArrayList<>());
    }

    private boolean findSynchroMaterialSet(
            Card target,
            List<Card> candidates,
            List<ParsedMaterialSlot> slots,
            int minimumCount,
            int start,
            List<Card> chosen
    ) {
        if (chosen.size() >= minimumCount) {
            int levelTotal = chosen.stream().filter(card -> card.getLevel() != null).mapToInt(Card::getLevel).sum();
            boolean hasUnknownLevel = chosen.stream().anyMatch(card -> card.getLevel() == null);
            List<String> requirements = slots.stream()
                    .flatMap(slot -> Collections.nCopies(slot.count(), slot.requirement()).stream())
                    .collect(Collectors.toList());
            if (!hasUnknownLevel && target.getLevel() != null && levelTotal == target.getLevel()
                    && assignMaterialRequirements(requirements, 0, chosen, new boolean[chosen.size()])) return true;
            if (target.getLevel() != null && levelTotal >= target.getLevel()) return false;
        }
        if (chosen.size() >= 5) return false;
        for (int index = start; index < candidates.size(); index++) {
            chosen.add(candidates.get(index));
            if (findSynchroMaterialSet(target, candidates, slots, minimumCount, index + 1, chosen)) return true;
            chosen.remove(chosen.size() - 1);
        }
        return false;
    }

    private boolean matchesFusionSummonTarget(Card target, String effectText) {
        String text = safeLower(effectText);

        Matcher excludedName = Pattern.compile("except\\s+\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
                .matcher(effectText);
        while (excludedName.find()) {
            if (safeLower(target.getName()).equals(safeLower(excludedName.group(1)))) {
                return false;
            }
        }

        Matcher maximumLevel = Pattern.compile("level\\s+(\\d+)\\s+or lower fusion monster", Pattern.CASE_INSENSITIVE)
                .matcher(effectText);
        if (maximumLevel.find()
                && (target.getLevel() == null || target.getLevel() > Integer.parseInt(maximumLevel.group(1)))) {
            return false;
        }

        Matcher minimumLevel = Pattern.compile("level\\s+(\\d+)\\s+or higher fusion monster", Pattern.CASE_INSENSITIVE)
                .matcher(effectText);
        if (minimumLevel.find()
                && (target.getLevel() == null || target.getLevel() < Integer.parseInt(minimumLevel.group(1)))) {
            return false;
        }

        for (String attribute : List.of("dark", "light", "earth", "water", "fire", "wind", "divine")) {
            if (text.contains(attribute + " fusion monster")
                    && !attribute.equals(safeLower(target.getAttribute()))) {
                return false;
            }
        }

        Matcher namedFamily = Pattern.compile("\"([^\"]+)\"\\s+fusion monsters?", Pattern.CASE_INSENSITIVE)
                .matcher(effectText);
        if (namedFamily.find()) {
            String family = safeLower(namedFamily.group(1));
            return safeLower(target.getName()).contains(family)
                    || safeLower(target.getArchetype()).contains(family);
        }
        return true;
    }

    private String extractRequiredFusionMaterial(String sourceText) {
        Matcher matcher = Pattern.compile(
                "mentions\\s+\"([^\"]+)\"\\s+as material",
                Pattern.CASE_INSENSITIVE)
                .matcher(normalizeCardText(sourceText));
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String fusionMaterialLine(Card fusionMonster) {
        return safeLower(fusionMaterialRequirement(fusionMonster));
    }

    private String fusionMaterialRequirement(Card fusionMonster) {
        String[] lines = normalizeCardText(fusionMonster.getDescription()).split("\\n");
        for (String line : lines) {
            if (!line.isBlank()) {
                return line.trim();
            }
        }
        return "";
    }

    private boolean hasRecognizableFusionMaterialLine(Card fusionMonster) {
        String requirement = fusionMaterialRequirement(fusionMonster);
        return requirement.contains(" + ")
                || Pattern.compile("^\\d+\\+?\\s+.+\\bmonsters?\\b[^.;]*$", Pattern.CASE_INSENSITIVE)
                        .matcher(requirement)
                        .matches();
    }

    private List<ParsedMaterialSlot> parseFusionMaterialSlots(Card fusionMonster) {
        return Arrays.stream(fusionMaterialRequirement(fusionMonster).split("\\+"))
                .map(String::trim)
                .filter(requirement -> !requirement.isBlank())
                .map(requirement -> {
                    Matcher countMatcher = Pattern.compile("^(\\d+)\\+?\\s+").matcher(requirement);
                    int count = countMatcher.find() ? Integer.parseInt(countMatcher.group(1)) : 1;
                    String normalizedRequirement = countMatcher.find(0)
                            ? requirement.substring(countMatcher.end()).trim()
                            : requirement;
                    return new ParsedMaterialSlot(normalizedRequirement, count);
                })
                .collect(Collectors.toList());
    }

    private boolean matchesFusionMaterialRequirement(Card card, String requirement) {
        String normalized = safeLower(requirement);
        String name = safeLower(card.getName());
        String type = safeLower(card.getType());
        if (!type.contains("monster")) {
            return false;
        }

        List<String> quotedTerms = extractQuotedTerms(requirement);
        if (!quotedTerms.isEmpty()) {
            String quoted = safeLower(quotedTerms.get(0));
            if (normalized.matches("^\"" + Pattern.quote(quoted) + "\"$")) {
                return name.equals(quoted);
            }
            if (!name.contains(quoted) && !safeLower(card.getArchetype()).contains(quoted)) {
                return false;
            }
        }

        for (String attribute : List.of("dark", "light", "earth", "water", "fire", "wind", "divine")) {
            if (normalized.contains(attribute + " monster")
                    && !attribute.equals(safeLower(card.getAttribute()))) {
                return false;
            }
        }

        if (normalized.contains("effect monster") && !isEffectMonster(card)) {
            return false;
        }
        if (normalized.contains("normal monster") && !type.contains("normal")) {
            return false;
        }
        if (normalized.contains("non-effect monster")
                && isEffectMonster(card)) {
            return false;
        }

        for (String subtype : List.of("gemini", "spirit", "toon", "union", "flip", "tuner")) {
            if (normalized.contains(subtype + " monster") && !type.contains(subtype)) {
                return false;
            }
        }

        Matcher exactLevel = Pattern.compile("\\blevel\\s+(\\d+)\\b(?!\\s+or)", Pattern.CASE_INSENSITIVE)
                .matcher(requirement);
        if (exactLevel.find() && (card.getLevel() == null
                || card.getLevel() != Integer.parseInt(exactLevel.group(1)))) {
            return false;
        }
        Matcher minimumLevel = Pattern.compile("\\blevel\\s+(\\d+)\\s+or higher\\b", Pattern.CASE_INSENSITIVE)
                .matcher(requirement);
        if (minimumLevel.find() && (card.getLevel() == null
                || card.getLevel() < Integer.parseInt(minimumLevel.group(1)))) {
            return false;
        }
        Matcher maximumLevel = Pattern.compile("\\blevel\\s+(\\d+)\\s+or lower\\b", Pattern.CASE_INSENSITIVE)
                .matcher(requirement);
        if (maximumLevel.find() && (card.getLevel() == null
                || card.getLevel() > Integer.parseInt(maximumLevel.group(1)))) {
            return false;
        }

        if (!matchesPrintedStatRequirement(card.getAtk(), requirement, "ATK")
                || !matchesPrintedStatRequirement(card.getDef(), requirement, "DEF")) {
            return false;
        }

        List<String> requiredMonsterKinds = List.of(
                        "fusion", "synchro", "xyz", "link", "ritual", "pendulum")
                .stream()
                .filter(kind -> Pattern.compile("\\b" + kind + "(?:,|\\s+or|\\s+monster)")
                        .matcher(normalized)
                        .find())
                .collect(Collectors.toList());
        if (!requiredMonsterKinds.isEmpty()
                && requiredMonsterKinds.stream().noneMatch(type::contains)) {
            return false;
        }

        List<String> races = List.of(
                "aqua", "beast", "beast-warrior", "cyberse", "dinosaur", "divine-beast",
                "dragon", "fairy", "fiend", "fish", "illusion", "insect", "machine",
                "plant", "psychic", "pyro", "reptile", "rock", "sea serpent",
                "spellcaster", "thunder", "warrior", "winged beast", "wyrm", "zombie");
        for (String race : races) {
            boolean requiresRace = Pattern.compile(
                    "\\b" + Pattern.quote(race)
                            + "(?:-type)?(?:\\s+(?:fusion|synchro|xyz|link|ritual|pendulum))?\\s+monsters?\\b")
                    .matcher(normalized)
                    .find();
            if (requiresRace
                    && !race.equals(safeLower(card.getRace()))) {
                return false;
            }
        }
        return true;
    }

    private boolean isEffectMonster(Card card) {
        String type = safeLower(card.getType());
        if (type.contains("effect")) return true;
        if (!containsAny(type, "fusion", "synchro", "xyz", "link", "ritual")) return false;
        return normalizeCardText(card.getDescription()).split("\n").length > 1;
    }

    private boolean matchesPrintedStatRequirement(Integer cardValue, String requirement, String stat) {
        Matcher matcher = Pattern.compile(
                "(\\d+)\\s+or\\s+(more|less)\\s+" + stat,
                Pattern.CASE_INSENSITIVE).matcher(requirement);
        if (!matcher.find()) return true;
        if (cardValue == null) return false;
        int threshold = Integer.parseInt(matcher.group(1));
        return matcher.group(2).equalsIgnoreCase("more")
                ? cardValue >= threshold
                : cardValue <= threshold;
    }

    private boolean matchesGeneralCostRequirement(Card card, String requirement) {
        String normalized = safeLower(requirement);
        String type = safeLower(card.getType());
        if (normalized.contains("monster") && !type.contains("monster")) {
            return false;
        }
        if (normalized.contains("spell") && !type.contains("spell")) {
            return false;
        }
        if (normalized.contains("trap") && !type.contains("trap")) {
            return false;
        }

        List<String> quotedTerms = extractQuotedTerms(requirement);
        if (quotedTerms.isEmpty()) {
            return true;
        }
        String quoted = safeLower(quotedTerms.get(0));
        return safeLower(card.getName()).contains(quoted)
                || safeLower(card.getArchetype()).contains(quoted);
    }

    private String generalCostSourceZone(String cost) {
        String text = safeLower(cost);
        if (text.contains("discard")) {
            return "Hand";
        }
        if (containsAny(text, "from your deck", "from the deck")) {
            return "Deck";
        }
        if (containsAny(text, "from your hand", "from the hand")) {
            return "Hand";
        }
        if (containsAny(text, "from your gy", "from the gy", "from your graveyard")) {
            return "Graveyard";
        }
        if (text.contains("banished")) {
            return "Banished";
        }
        if (containsAny(text, "tribute", "destroy")) {
            return "Field";
        }
        return "Field / Hand / Graveyard";
    }

    private String generalCostDestination(String cost) {
        String text = safeLower(cost);
        if (text.contains("banish")) {
            return "Banished";
        }
        if (text.contains("shuffle")) {
            return "Deck";
        }
        return "Graveyard";
    }

    private boolean hasFusionMaterialProhibition(Card card) {
        String text = safeLower(card.getDescription());
        return containsAny(text,
                "cannot be used as fusion material",
                "cannot be used as a fusion material",
                "cannot be used as material for a fusion summon");
    }

    private String fusionMaterialSourceZones(String sourceText) {
        String text = safeLower(sourceText);
        LinkedHashSet<String> zones = new LinkedHashSet<>();
        if (containsAny(text, "your hand", "the hand")) {
            zones.add("Hand");
        }
        if (containsAny(text, "your deck", "the deck")
                || Pattern.compile("\\bdeck\\b").matcher(text).find()) {
            zones.add("Deck");
        }
        if (containsAny(text, "your field", "the field", "you control")
                || Pattern.compile("\\bfield\\b").matcher(text).find()) {
            zones.add("Field");
        }
        if (containsAny(text, "your gy", "the gy", "graveyard")
                || Pattern.compile("\\bgy\\b").matcher(text).find()) {
            zones.add("Graveyard");
        }
        if (text.contains("banished")) {
            zones.add("Banished");
        }
        if (text.contains("extra deck")) {
            zones.add("Extra Deck");
        }
        return zones.isEmpty() ? "Eligible zones named by the effect" : String.join(" / ", zones);
    }

    private String fusionMaterialDestination(String sourceText) {
        String text = safeLower(sourceText);
        if (containsAny(text, "by banishing", "banish the fusion materials")) {
            return "Banished";
        }
        if (containsAny(text, "by shuffling", "shuffle the fusion materials")) {
            return "Deck";
        }
        return "Graveyard";
    }

    private String fusionSummonCost(Card source, Card target) {
        return fusionSummonCost(source, target, source.getDescription());
    }

    private String fusionSummonCost(Card source, Card target, String selectedEffect) {
        String sourceDescription = normalizeCardText(source.getDescription());
        String normalizedSelectedEffect = normalizeCardText(selectedEffect);
        int selectedEffectStart = safeLower(sourceDescription).indexOf(safeLower(normalizedSelectedEffect));
        String sourceText = selectedEffectStart >= 0
                ? sourceDescription.substring(
                        selectedEffectStart,
                        selectedEffectStart + normalizedSelectedEffect.length())
                : normalizedSelectedEffect;
        String materialRequirement = fusionMaterialRequirement(target);
        String materialAction = extractFusionMaterialAction(sourceText);
        List<String> costParts = new ArrayList<>();

        costParts.add(materialAction.isBlank()
                ? "Use the listed Fusion Materials"
                : capitalize(materialAction));
        if (!materialRequirement.isBlank()) {
            costParts.add("Materials: " + materialRequirement);
        }

        String restriction = fusionMaterialRestriction(source);
        if (!restriction.isBlank()) {
            costParts.add("Restriction: " + restriction);
        }
        return String.join(". ", costParts);
    }

    private String extractFusionMaterialAction(String sourceText) {
        Matcher action = Pattern.compile(
                "\\b((?:using|by (?:banishing|sending|shuffling|destroying|tributing))\\b[^.;]*)",
                Pattern.CASE_INSENSITIVE)
                .matcher(sourceText);
        if (!action.find()) {
            return "";
        }

        String result = action.group(1).trim();
        return result.replaceFirst("(?i)^using\\s+", "Use ");
    }

    private String fusionMaterialRestriction(Card card) {
        String text = normalizeCardText(card.getDescription());
        Matcher restriction = Pattern.compile(
                "([^\\n.]*cannot be used as (?:a )?fusion material[^\\n.]*)",
                Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (restriction.find()) {
            return restriction.group(1).trim();
        }

        Matcher alternateRestriction = Pattern.compile(
                "([^\\n.]*cannot be used as material for a fusion summon[^\\n.]*)",
                Pattern.CASE_INSENSITIVE)
                .matcher(text);
        return alternateRestriction.find() ? alternateRestriction.group(1).trim() : "";
    }

    private String comboCostFor(Card source, Card target) {
        return comboCostFor(source, target, source.getDescription());
    }

    private String comboCostFor(Card source, Card target, String effectText) {
        if (isFusionMonster(target) && safeLower(effectText).contains("fusion summon")) {
            return fusionSummonCost(source, target);
        }

        Matcher costClause = Pattern.compile(
                "([^.;:]*\\b(?:discard|tribute|banish|send|pay|detach|destroy|shuffle)\\b[^;]*);",
                Pattern.CASE_INSENSITIVE)
                .matcher(normalizeCardText(effectText));
        return costClause.find() ? capitalize(costClause.group(1).trim()) : "";
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private boolean hasCompatibleFusionMaterialCount(String sourceText, Card fusionMonster) {
        Matcher sourceCount = Pattern.compile("using\\s+(\\d+)\\s+monsters?", Pattern.CASE_INSENSITIVE)
                .matcher(sourceText);
        if (!sourceCount.find()) {
            return true;
        }

        int availableMaterials = Integer.parseInt(sourceCount.group(1));
        String[] requirements = fusionMaterialLine(fusionMonster).split("\\+");
        int minimumMaterials = 0;
        for (String requirement : requirements) {
            Matcher count = Pattern.compile("\\b(\\d+)\\+?\\b").matcher(requirement.trim());
            minimumMaterials += count.find() ? Integer.parseInt(count.group(1)) : 1;
        }
        return minimumMaterials <= availableMaterials;
    }

    private List<ComboOption> mergeComboOptions(List<ComboOption> priorityOptions, List<ComboOption> otherOptions) {
        LinkedHashMap<String, ComboOption> merged = new LinkedHashMap<>();
        for (ComboOption option : priorityOptions) {
            merged.put(safeLower(option.card().getName()), option);
        }
        for (ComboOption option : otherOptions) {
            merged.putIfAbsent(safeLower(option.card().getName()), option);
        }
        return new ArrayList<>(merged.values());
    }

    // Explicit and text-driven route extraction

    private void buildExplicitComboRoutes(Card card, String description, Map<String, ComboDraft> drafts) {
        List<Card> relatedCards = getRelatedArchetypeCards(card);
        String archetype = safeLower(card.getArchetype());

        List<String> quotedCards = extractQuotedTerms(description);
        for (String quoted : quotedCards) {
            Card matchedCard = findBestCardMatch(quoted);
            if (matchedCard == null || Objects.equals(matchedCard.getId(), card.getId())) {
                continue;
            }
            if (!sameArchetype(archetype, matchedCard)) {
                continue;
            }
            if (isExtraDeckMonster(matchedCard)
                    && !containsAny(description, "extra deck", "fusion summon", "synchro summon", "xyz summon", "link summon")) {
                continue;
            }

            ComboDraft draft = draftFor(drafts, matchedCard);
            addScore(draft, 100, "Effect text directly names this card");
            addRouteMetadata(
                    draft,
                    timingForEffect(card, description),
                    sourceZoneFor(card),
                    destinationForEffect(description, matchedCard));

            if (description.contains("fusion summon")) {
                addScore(draft, 20, "It appears in a Fusion Summon line");
            }
            if (description.contains("add to your hand") || description.contains("set 1") || description.contains("search")) {
                addScore(draft, 15, "It is a follow-up target from the card text");
            }
        }

        if (description.contains("fusion summon") && description.contains("branded")) {
            Card brandedSpellTrap = relatedCards.stream()
                    .filter(this::isSpellOrTrap)
                    .filter(c -> safeLower(c.getName()).contains("branded"))
                    .findFirst()
                    .orElse(null);

            if (brandedSpellTrap != null && (description.contains("add to your hand") || description.contains("set 1"))) {
                ComboDraft draft = draftFor(drafts, brandedSpellTrap);
                addScore(draft, 35, "It is a branded follow-up spell or trap");
                draft.reasons.add(card.getName() + " can recycle or set it for follow-up");
                addRouteMetadata(draft, timingForEffect(card, description), sourceZoneFor(card), "Hand or Spell & Trap Zone");
            }
        }
    }

    private void buildTextDrivenComboRoutes(
            Card card,
            String description,
            Map<String, ComboDraft> drafts,
            String sourceZoneOverride,
            boolean selectedEffectOnly
    ) {
        List<Card> candidates = getTextDrivenCandidates(card, description);
        if (candidates.isEmpty()) {
            return;
        }

        List<EffectSection> sections = sourceZoneOverride.isBlank() && !selectedEffectOnly
                ? splitEffectSections(card)
                : List.of(new EffectSection(
                        description,
                        sourceZoneOverride.isBlank() ? sourceZoneFor(card) : sourceZoneOverride));
        for (EffectSection section : sections) {
            buildTextDrivenRoutesForSection(card, section, candidates, drafts);
        }
    }

    private List<Card> getTextDrivenCandidates(Card source, String effectText) {
        LinkedHashMap<String, Card> candidates = new LinkedHashMap<>();
        for (Card card : getRelatedArchetypeCards(source)) {
            candidates.put(safeLower(card.getName()), card);
        }

        String mentionedCardName = mentionedCardReference(effectText);
        if (!mentionedCardName.isBlank()) {
            Card namedCard = findBestCardMatch(mentionedCardName);
            if (namedCard != null) {
                candidates.putIfAbsent(safeLower(namedCard.getName()), namedCard);
            }
            for (Card candidate : cardRepository
                    .findByTypeContainingIgnoreCaseAndDescriptionContainingIgnoreCase("monster", mentionedCardName)) {
                candidates.putIfAbsent(safeLower(candidate.getName()), candidate);
            }
        }

        if (containsAny(effectText, "search", "add to your hand", "add 1")) {
            for (String quotedTerm : extractQuotedTerms(effectText)) {
                if (quotedTerm.isBlank() || safeLower(quotedTerm).equals(safeLower(source.getName()))) {
                    continue;
                }
                for (Card card : cardRepository.findByNameContainingIgnoreCase(quotedTerm)) {
                    candidates.putIfAbsent(safeLower(card.getName()), card);
                }
                for (Card card : cardRepository.findByTypeContainingIgnoreCaseAndDescriptionContainingIgnoreCase("", quotedTerm)) {
                    candidates.putIfAbsent(safeLower(card.getName()), card);
                }
            }
        }

        candidates.values().removeIf(card -> Objects.equals(card.getId(), source.getId()));
        return new ArrayList<>(candidates.values());
    }

    private void buildTextDrivenRoutesForSection(
            Card card,
            EffectSection section,
            List<Card> candidates,
            Map<String, ComboDraft> drafts
    ) {
        String effectText = safeLower(section.text());
        boolean sourceCanSearch = containsAny(effectText, "search", "add to your hand", "add 1");
        boolean searchesMainDeck = sourceCanSearch && containsAny(effectText, "from your deck", "from the deck");
        boolean searchesExtraDeck = sourceCanSearch && effectText.contains("extra deck");
        boolean placesPendulumFromDeck = effectText.contains("pendulum monster")
                && effectText.contains("from your deck")
                && effectText.contains("pendulum zone");
        boolean placesContinuousBackrow = containsAny(effectText, "continuous trap", "continuous spell")
                && containsAny(effectText, "spell & trap zone", "spell/trap zone");
        boolean sourceCanSummon = containsAny(
                effectText,
                "special summon",
                "you can special summon",
                "special summon 1",
                "special summon that",
                "special summon it",
                "special summon this card",
                "normal summon",
                "tribute summon");
        boolean sourceCanRecover = containsAny(effectText, "send to the graveyard", "discard", "banish this card", "recycle");
        boolean sourceCanSetBackrow = !placesContinuousBackrow
                && containsAny(effectText, "set 1", "set it", "set this", "place 1", "place this card");
        String mentionedCardName = mentionedCardReference(section.text());

        for (Card candidate : candidates) {
            String targetDescription = safeLower(candidate.getDescription());
            int score = 0;
            LinkedHashSet<String> reasons = new LinkedHashSet<>();
            LinkedHashSet<String> destinations = new LinkedHashSet<>();

            if (!mentionedCardName.isBlank()
                    && !isExtraDeckMonster(candidate)
                    && safeLower(candidate.getType()).contains("monster")
                    && (safeLower(candidate.getName()).equals(safeLower(mentionedCardName))
                    || safeLower(candidate.getDescription()).contains(safeLower(mentionedCardName)))) {
                score += 100;
                reasons.add(candidate.getName().equalsIgnoreCase(mentionedCardName)
                        ? "This is the specifically named monster"
                        : "This monster mentions \"" + mentionedCardName + "\" in its card text");
                destinations.add(sourceCanSummon ? "Monster Zone" : "Hand");
            }

            if (placesPendulumFromDeck && isPendulumMonster(candidate)) {
                score += 105;
                reasons.add(card.getName() + " can place this card from the Deck");
                destinations.add("Pendulum Zone");
            }

            if (placesContinuousBackrow && safeLower(candidate.getType()).contains("monster")) {
                score += 105;
                reasons.add(card.getName() + " can place this monster as a face-up Continuous Spell/Trap");
                destinations.add(effectText.contains("continuous spell")
                        ? "Spell & Trap Zone as Continuous Spell"
                        : "Spell & Trap Zone as Continuous Trap");
            }

            if (searchesMainDeck && isLegalMainDeckSearchTarget(effectText, candidate)) {
                score += 90;
                reasons.add(card.getName() + " can search or add this card");
                destinations.add("Hand");
                if (isSelfSummoningBridgeTarget(candidate, targetDescription)) {
                    reasons.add("This card can special summon itself after being searched");
                    score += 15;
                }
            } else if (searchesExtraDeck && isLegalExtraDeckSearchTarget(effectText, candidate)) {
                score += 85;
                reasons.add(card.getName() + " can add this card from the Extra Deck");
                destinations.add("Hand from Extra Deck");
            } else if (sourceCanSearch
                    && !searchesMainDeck
                    && !searchesExtraDeck
                    && !isExtraDeckMonster(candidate)
                    && isSearchBridgeTarget(candidate, targetDescription)) {
                score += 65;
                reasons.add(card.getName() + " can search or add this card");
                destinations.add("Hand");
            }

            if (sourceCanSummon && isSelfSummoningBridgeTarget(candidate, targetDescription)) {
                score += 60;
                reasons.add("This card can special summon itself or another extender");
                destinations.add("Monster Zone");
            }

            if (sourceCanRecover && isGraveyardBridgeTarget(candidate, targetDescription)) {
                score += 55;
                reasons.add("This card has graveyard recursion that turns setup into follow-up");
                destinations.add("Graveyard setup");
            }

            if (sourceCanSetBackrow && isBackrowBridgeTarget(candidate, targetDescription)) {
                score += 40;
                reasons.add("This is searchable/settable backrow follow-up");
                destinations.add("Spell & Trap Zone");
            }

            if (score <= 0) {
                continue;
            }

            ComboDraft draft = draftFor(drafts, candidate);
            addScore(draft, score, "Text-driven combo bridge");
            draft.reasons.addAll(reasons);
            addRouteMetadata(
                    draft,
                    timingForEffect(card, effectText),
                    section.sourceZone(),
                    destinations.isEmpty() ? destinationForEffect(effectText, candidate) : String.join(" / ", destinations));
            if (isStarterCard(candidate)) {
                draft.reasons.add("Acts as a starter once accessed");
            } else if (isExtenderCard(candidate)) {
                draft.reasons.add("Acts as an extender once accessed");
            } else if (isFollowUpCard(candidate)) {
                draft.reasons.add("Useful for follow-up after the first line");
            }
        }
    }

    private String mentionedCardReference(String effectText) {
        Matcher directReference = Pattern.compile(
                "monster\\s+that\\s+mentions\\s+\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE).matcher(effectText);
        if (directReference.find()) {
            return directReference.group(1).trim();
        }

        Matcher pronounReference = Pattern.compile(
                "\"([^\"]+)\"\\s*,?\\s*or\\s+(?:1\\s+)?monster\\s+that\\s+mentions\\s+(?:it|that card)",
                Pattern.CASE_INSENSITIVE).matcher(effectText);
        return pronounReference.find() ? pronounReference.group(1).trim() : "";
    }

    // Card and route classification heuristics

    private boolean isSpellOrTrap(Card card) {
        String type = safeLower(card.getType());
        return type.contains("spell") || type.contains("trap");
    }

    private boolean isFusionMonster(Card card) {
        String type = safeLower(card.getType());
        return type.contains("fusion") && type.contains("monster");
    }

    private boolean isExtraDeckMonster(Card card) {
        String type = safeLower(card.getType());
        return type.contains("fusion")
                || type.contains("synchro")
                || type.contains("xyz")
                || type.contains("link");
    }

    private boolean isPendulumMonster(Card card) {
        String type = safeLower(card.getType());
        return type.contains("pendulum") && type.contains("monster");
    }

    private boolean isLegalMainDeckSearchTarget(String sourceText, Card candidate) {
        if (isExtraDeckMonster(candidate)) {
            return false;
        }

        String type = safeLower(candidate.getType());
        if (sourceText.contains("spell/trap")) {
            return isSpellOrTrap(candidate);
        }
        if (sourceText.contains("pendulum monster")) {
            return isPendulumMonster(candidate);
        }
        if (sourceText.contains("monster from your deck") || sourceText.contains("monster from the deck")) {
            return type.contains("monster");
        }
        if (sourceText.contains("spell card from your deck") || sourceText.contains("spell from your deck")) {
            return type.contains("spell");
        }
        if (sourceText.contains("trap card from your deck") || sourceText.contains("trap from your deck")) {
            return type.contains("trap");
        }
        Matcher quotedFamily = Pattern.compile("\"([^\"]+)\"\\s+card").matcher(sourceText);
        if (quotedFamily.find()) {
            String family = safeLower(quotedFamily.group(1));
            return safeLower(candidate.getName()).contains(family)
                    || safeLower(candidate.getArchetype()).contains(family)
                    || safeLower(candidate.getDescription()).contains(family);
        }
        return true;
    }

    private boolean isLegalExtraDeckSearchTarget(String sourceText, Card candidate) {
        if (sourceText.contains("face-up") && isPendulumMonster(candidate)) {
            return true;
        }
        return isExtraDeckMonster(candidate);
    }

    private boolean isStarterCard(Card card) {
        String desc = safeLower(card.getDescription());
        return containsAny(desc, "search", "add to hand", "add to your hand", "add 1", "draw");
    }

    private boolean isExtenderCard(Card card) {
        String desc = safeLower(card.getDescription());
        return containsAny(desc,
                "special summon this card",
                "special summon itself",
                "special summon from your hand",
                "special summon from your graveyard",
                "if this card is in your hand",
                "from your hand",
                "from your graveyard",
                "extra deck");
    }

    private boolean hasComboContinuationEffect(Card card) {
        String desc = safeLower(card.getDescription());
        return containsAny(desc,
                "fusion summon",
                "special summon",
                "add to hand",
                "add to your hand",
                "add 1",
                "search",
                "set 1",
                "set it",
                "place 1",
                "place this card");
    }

    private boolean isFollowUpCard(Card card) {
        String desc = safeLower(card.getDescription());
        return containsAny(desc, "graveyard", "end phase", "set 1", "add to your hand", "search", "recycle", "return to the hand");
    }

    private boolean isSearchBridgeTarget(Card card, String description) {
        return isSpellOrTrap(card)
                ? containsAny(description, "search", "add 1", "add to your hand", "set 1")
                : containsAny(description, "search", "add 1", "add to your hand", "special summon this card", "special summon itself", "from your hand", "from your graveyard");
    }

    private boolean isSelfSummoningBridgeTarget(Card card, String description) {
        if (!safeLower(card.getType()).contains("monster")) {
            return false;
        }

        return containsAny(description,
                "special summon this card",
                "special summon itself",
                "special summon from your hand",
                "special summon from your graveyard",
                "if this card is in your hand",
                "if this card is sent to the graveyard",
                "if this card is normal summoned",
                "if you control");
    }

    private boolean isGraveyardBridgeTarget(Card card, String description) {
        return containsAny(description,
                "from your graveyard",
                "if this card is sent to the graveyard",
                "banish this card",
                "return this card from your graveyard",
                "send this card to the graveyard",
                "during the end phase");
    }

    private boolean isBackrowBridgeTarget(Card card, String description) {
        return isSpellOrTrap(card) && containsAny(description, "search", "add 1", "add to your hand", "set 1", "recycle", "activate 1");
    }

    private boolean containsAny(String text, String... terms) {
        if (text == null || text.isBlank()) {
            return false;
        }

        for (String term : terms) {
            if (term != null && !term.isBlank() && text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    // Effect text, timing, and zone metadata

    private List<EffectSection> splitEffectSections(Card card) {
        String text = normalizeCardText(card.getDescription());
        if (!isPendulumMonster(card)) {
            return List.of(new EffectSection(text, sourceZoneFor(card)));
        }

        Matcher matcher = Pattern.compile(
                "(?is)\\[?\\s*pendulum effect\\s*\\]?\\s*(.*?)\\[?\\s*monster effect\\s*\\]?\\s*(.*)")
                .matcher(text);
        if (matcher.find()) {
            return List.of(
                    new EffectSection(matcher.group(1).trim(), "Pendulum Zone"),
                    new EffectSection(matcher.group(2).trim(), "Monster Zone / Hand"));
        }

        return List.of(new EffectSection(text, "Pendulum Zone / Monster Zone / Hand"));
    }

    private String normalizeCardText(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace('“', '"')
                .replace('”', '"')
                .replace('’', '\'')
                .replace("\r\n", "\n")
                .trim();
    }

    private String normalizedZoneLabel(String zone) {
        String normalized = safeLower(zone).trim();
        if (containsAny(normalized, "graveyard", "gy")) {
            return "Graveyard";
        }
        if (normalized.contains("banish")) {
            return "Banished";
        }
        return "";
    }

    private String effectTextForZone(Card card, String zoneLabel) {
        return Arrays.stream(normalizeCardText(card.getDescription()).split("(?<=\\.)\\s+|\\n+"))
                .map(String::trim)
                .filter(effect -> {
                    String text = safeLower(effect);
                    if (zoneLabel.equals("Graveyard")) {
                        return containsAny(text,
                                "sent to the gy",
                                "sent to your gy",
                                "sent to the graveyard",
                                "in your gy",
                                "from your gy",
                                "in the gy",
                                "from the gy",
                                "in your graveyard",
                                "from your graveyard",
                                "this card is discarded",
                                "this card is tributed",
                                "used as fusion material");
                    }
                    return containsAny(text,
                            "this card is banished",
                            "it is banished",
                            "from your banished",
                            "among your banished");
                })
                .collect(Collectors.joining(" "));
    }

    private String timingForEffect(Card sourceCard, String effectText) {
        String text = safeLower(effectText);
        LinkedHashSet<String> timings = new LinkedHashSet<>();

        if (safeLower(sourceCard.getType()).contains("trap")
                && !containsAny(text, "activate this card the turn it was set", "activate it this turn")) {
            timings.add("After being Set");
        }
        if (containsAny(text, "except the turn it was sent", "except during the turn it was sent")) {
            timings.add("Next turn");
        }
        if (containsAny(text, "during the end phase", "in the end phase")) {
            timings.add("End Phase");
        }
        if (containsAny(text, "at the end of the battle phase", "end of the battle phase")) {
            timings.add("End of Battle Phase");
        }
        if (containsAny(text, "during the standby phase", "in the standby phase")) {
            timings.add("Standby Phase");
        }
        if (containsAny(text, "when your opponent activates", "if your opponent activates")) {
            timings.add("Opponent response");
        }

        return timings.isEmpty() ? "Immediate" : String.join(" / ", timings);
    }

    private String sourceZoneFor(Card card) {
        String type = safeLower(card.getType());
        if (type.contains("trap") || type.contains("spell")) {
            return "Spell & Trap Zone";
        }
        if (type.contains("pendulum")) {
            return "Pendulum Zone / Monster Zone / Hand";
        }
        return "Monster Zone / Hand";
    }

    private String destinationForEffect(String effectText, Card target) {
        String text = safeLower(effectText);
        if (containsAny(text, "spell & trap zone", "spell/trap zone") && text.contains("continuous trap")) {
            return "Spell & Trap Zone as Continuous Trap";
        }
        if (containsAny(text, "spell & trap zone", "spell/trap zone") && text.contains("continuous spell")) {
            return "Spell & Trap Zone as Continuous Spell";
        }
        if (text.contains("pendulum zone") && isPendulumMonster(target)) {
            return "Pendulum Zone";
        }
        if (text.contains("extra deck") && containsAny(text, "add", "hand")) {
            return "Hand from Extra Deck";
        }
        if (containsAny(text, "add to your hand", "add 1")) {
            return "Hand";
        }
        if (text.contains("special summon")) {
            return "Monster Zone";
        }
        if (containsAny(text, "set 1", "set it", "set this")) {
            return "Spell & Trap Zone";
        }
        if (text.contains("graveyard")) {
            return "Graveyard";
        }
        return "Varies";
    }

    private void addRouteMetadata(ComboDraft draft, String timing, String sourceZone, String destination) {
        if (timing != null && !timing.isBlank()) {
            draft.timings.add(timing);
        }
        if (sourceZone != null && !sourceZone.isBlank()) {
            draft.sourceZones.add(sourceZone);
        }
        if (destination != null && !destination.isBlank()) {
            draft.destinations.add(destination);
        }
    }

    private String joinMetadata(LinkedHashSet<String> values, String fallback) {
        return values.isEmpty() ? fallback : String.join(" / ", values);
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase();
    }

    // Candidate matching, scoring, and result assembly

    private List<String> extractQuotedTerms(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        List<String> terms = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(text);
        while (matcher.find()) {
            String term = matcher.group(1).trim();
            if (!term.isEmpty()) {
                terms.add(term);
            }
        }
        return terms;
    }

    private Card findBestCardMatch(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }

        String normalized = term.trim().toLowerCase();
        List<Card> cards = cardRepository.findByNameContainingIgnoreCase(term);
        if (cards.isEmpty()) {
            cards = cardRepository.findByNameContainingIgnoreCase(normalized);
        }
        if (cards.isEmpty()) {
            return null;
        }

        for (Card candidate : cards) {
            if (safeLower(candidate.getName()).equals(normalized)) {
                return candidate;
            }
        }

        return cards.get(0);
    }

    private ComboDraft draftFor(Map<String, ComboDraft> drafts, Card card) {
        return drafts.computeIfAbsent(safeLower(card.getName()), key -> new ComboDraft(card));
    }

    private void addScore(ComboDraft draft, int points, String reason) {
        draft.score += points;
        if (reason != null && !reason.isBlank()) {
            draft.reasons.add(reason);
        }
    }

    private String joinReasons(LinkedHashSet<String> reasons) {
        List<String> usefulReasons = reasons.stream()
                .filter(reason -> !reason.equalsIgnoreCase("Text-driven combo bridge"))
                .collect(Collectors.toList());
        if (usefulReasons.isEmpty()) {
            return "Good follow-up option";
        }
        return String.join("; ", usefulReasons);
    }

    private String comboLabelFor(Card card) {
        if (!hasComboContinuationEffect(card)) {
            return "ender";
        }
        if (isStarterCard(card)) {
            return "starter";
        }
        if (isExtenderCard(card)) {
            return "extender";
        }
        if (isFollowUpCard(card)) {
            return "follow-up";
        }
        return "extender";
    }

    // Curated archetype routes

    private List<ComboOption> buildCuratedGuideCombos(Card card) {
        List<ComboOption> options = new ArrayList<>();
        String name = safeLower(card.getName());
        String archetype = safeLower(card.getArchetype());

        if (name.contains("d/d") || archetype.contains("d/d")) {
            addCuratedCombo(options, "D/D Savant Kepler", card, "Guide-backed starter: Kepler searches Dark Contract with the Gate, which searches any D/D monster.", "starter", 120);
            addCuratedCombo(options, "Dark Contract with the Gate", card, "Guide-backed starter: Gate searches any D/D monster and is itself a one-card combo.", "starter", 115);
            addCuratedCombo(options, "D/D Gryphon", card, "Guide-backed extender: Gryphon special summons itself and searches after it hits the GY.", "extender", 110);
            addCuratedCombo(options, "D/D Necro Slime", card, "Guide-backed extender: Necro Slime enables Fusion Summons by banishing itself and another D/D from the GY.", "extender", 108);
            addCuratedCombo(options, "D/D Count Surveyor", card, "Guide-backed extender: Count Surveyor discards another D/D and replaces the discard with another search target.", "extender", 106);
            addCuratedCombo(options, "D/D/D Zero Doom Queen Machinex", card, "Guide-backed follow-up: Machinex is a 1-card starter that can place a Dark Contract directly from the deck.", "follow-up", 130);
            addCuratedCombo(options, "D/D Lance Soldier", card, "Guide-backed extender: Lance Soldier destroys a Dark Contract to summon itself and manipulate levels.", "extender", 104);
            addCuratedCombo(options, "D/D Orthros", card, "Guide-backed utility: Orthros is the low-scale extender and backrow remover used in combo lines.", "support", 100);
        }

        if (name.contains("swordsoul") || archetype.contains("swordsoul") || name.contains("incredible ecclesia")) {
            addCuratedCombo(options, "Swordsoul of Mo Ye", card, "Guide-backed starter: Mo Ye generates a Token and is the cleanest route into Level 8 Synchro plays.", "starter", 120);
            addCuratedCombo(options, "Swordsoul Strategist Longyuan", card, "Guide-backed extender: Longyuan discards a card to reach Baronne de Fleur or other Level 10 Synchro lines.", "extender", 118);
            addCuratedCombo(options, "Swordsoul Blackout", card, "Guide-backed follow-up: Blackout is the searchable interaction piece that completes the line.", "follow-up", 112);
            addCuratedCombo(options, "Swordsoul Grandmaster - Chixiao", card, "Guide-backed follow-up: the basic Mo Ye line turns into Chixiao.", "follow-up", 116);
            addCuratedCombo(options, "Baronne de Fleur", card, "Guide-backed follow-up: Longyuan makes the simple Baronne line.", "follow-up", 114);
        }

        if (name.contains("archfiend") || archetype.contains("archfiend") || name.contains("tour guide")) {
            addCuratedCombo(options, "Archfiend Heiress", card, "Guide-backed searcher: Heiress converts Archfiend names into more access.", "starter", 112);
            addCuratedCombo(options, "Archfiend Strategy", card, "Guide-backed searcher: Strategy finds any Archfiend card and keeps the engine moving.", "starter", 110);
            addCuratedCombo(options, "Archfiend's Usurpation", card, "Guide-backed starter/removal card: Usurpation can start plays and acts as a ritual spell.", "starter", 108);
            addCuratedCombo(options, "Archfiend Emperor", card, "Guide-backed follow-up: Emperor is the main boss monster and primary endboard piece.", "follow-up", 130);
            addCuratedCombo(options, "Archfiend Matriarch", card, "Guide-backed follow-up: Matriarch is the grind-game recycler and follow-up threat.", "follow-up", 114);
        }

        if (name.contains("dogmatika") || archetype.contains("dogmatika") || name.contains("ecclesia") || name.contains("nadir servant") || name.contains("fallen of the white dragon")) {
            addCuratedCombo(options, "Dogmatika Ecclesia, the Virtuous", card, "Guide-backed starter: Ecclesia searches any Dogmatika card and is the cleanest bridge into the archetype.", "starter", 120);
            addCuratedCombo(options, "Nadir Servant", card, "Guide-backed starter: Nadir Servant sends an Extra Deck monster to access Dogmatika pieces.", "starter", 116);
            addCuratedCombo(options, "Dogmatika Punishment", card, "Guide-backed follow-up: Punishment is the main trap interaction and converts into ED-based removal.", "follow-up", 112);
            addCuratedCombo(options, "Dogmatika Fleurdelis, the Knighted", card, "Guide-backed extender: Fleurdelis is the on-board negate that follows Dogmatika access pieces.", "extender", 108);
            addCuratedCombo(options, "The Fallen & The Virtuous", card, "Guide-backed follow-up: the main send-and-destroy spell that converts Dogmatika setup into tempo.", "follow-up", 114);
        }

        if (name.contains("exosister") || archetype.contains("exosister") || name.contains("martha") || name.contains("pax")) {
            addCuratedCombo(options, "Exosister Martha", card, "Guide-backed starter: Martha is the easiest way to get an Exosister body on board.", "starter", 120);
            addCuratedCombo(options, "Exosister Pax", card, "Guide-backed starter: Pax is the deck's main search spell and links the rest of the line together.", "starter", 118);
            addCuratedCombo(options, "Exosister Mikailis", card, "Guide-backed follow-up: Mikailis is the primary first Xyz that turns the starter into advantage.", "follow-up", 116);
            addCuratedCombo(options, "Exosister Kaspitell", card, "Guide-backed extender: Kaspitell converts spare names into another rank 4 body.", "extender", 110);
            addCuratedCombo(options, "Exosister Magnifica", card, "Guide-backed follow-up: Magnifica is the layered endboard upgrade that gives the deck its closing power.", "follow-up", 114);
            addCuratedCombo(options, "Exosister Karmael", card, "Guide-backed follow-up: Karmael gives the deck another disruption layer and a way to keep playing.", "follow-up", 106);
        }

        if (name.contains("stardust") || name.contains("bystial") || archetype.contains("bystial") || archetype.contains("stardust")) {
            addCuratedCombo(options, "Stardust Dragon", card, "Guide-backed starter: Stardust is the centerpiece used to branch into the modern Synchro lines.", "starter", 120);
            addCuratedCombo(options, "Bystial Magnamhut", card, "Guide-backed extender: Magnamhut is one of the best ways to convert a grave setup into follow-up.", "extender", 118);
            addCuratedCombo(options, "Bystial Druiswurm", card, "Guide-backed extender: Druiswurm is a live Bystial body that both pressures and clears cards.", "extender", 114);
            addCuratedCombo(options, "Stardust Synchron", card, "Guide-backed starter: Synchron is the card that converts Stardust access into the combo tree.", "starter", 116);
            addCuratedCombo(options, "Junk Speeder", card, "Guide-backed follow-up: Speeder is one of the big Synchro route endpoints if your build includes the Warrior package.", "follow-up", 112);
            addCuratedCombo(options, "Dis Pater, the Black Dragon", card, "Guide-backed follow-up: Dis Pater is a recurring Synchro payoff and recursion piece.", "follow-up", 110);
        }

        options.sort(Comparator
                .comparingInt((ComboOption option) -> option.score()).reversed()
                .thenComparing(option -> option.card().getName(), String.CASE_INSENSITIVE_ORDER));
        return options;
    }

    private void addCuratedCombo(List<ComboOption> options, String targetName, Card sourceCard, String reason, String label, int score) {
        Card target = findBestCardMatch(targetName);
        if (target == null || Objects.equals(target.getId(), sourceCard.getId())) {
            return;
        }

        if (!sameArchetype(sourceCard, target)) {
            return;
        }

        String cleanedReason = reason.replaceFirst("(?i)^Guide-backed [^:]+:\\s*", "");
        options.add(new ComboOption(
                target,
                cleanedReason,
                score,
                hasComboContinuationEffect(target) ? label : "ender",
                timingForEffect(sourceCard, sourceCard.getDescription()),
                sourceZoneFor(sourceCard),
                destinationForEffect(sourceCard.getDescription(), target),
                comboCostFor(sourceCard, target),
                hasOncePerTurnRestriction(target)));
    }

    private boolean sameArchetype(Card sourceCard, Card targetCard) {
        String sourceArchetype = safeLower(sourceCard.getArchetype()).trim();
        String targetArchetype = safeLower(targetCard.getArchetype()).trim();
        if (sourceArchetype.isBlank() || targetArchetype.isBlank()) {
            return false;
        }
        return sourceArchetype.equals(targetArchetype);
    }

    private boolean sameArchetype(String sourceArchetype, Card targetCard) {
        String targetArchetype = safeLower(targetCard.getArchetype()).trim();
        if (sourceArchetype == null || sourceArchetype.isBlank() || targetArchetype.isBlank()) {
            return false;
        }
        return sourceArchetype.trim().equals(targetArchetype);
    }
}
