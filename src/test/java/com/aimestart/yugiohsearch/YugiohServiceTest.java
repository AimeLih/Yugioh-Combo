package com.aimestart.yugiohsearch;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YugiohServiceTest {

    private final CardRepository repository = mock(CardRepository.class);
    private final ComboService service = new ComboService(repository);
    private final CardAnalysisService cardAnalysisService = new CardAnalysisService(repository);
    private final YugiohService yugiohService = mock(YugiohService.class);
    private final CardCatalogService cardCatalogService = new CardCatalogService(yugiohService, repository);

    @Test
    void effectPrerequisitesRequireDiscardCostBeforeActivation() {
        String effect = "If this card is Fusion Summoned: You can discard 1 card; Fusion Summon 1 Level 8 or lower Fusion Monster.";
        Card lubellion = card(1, "Lubellion the Searing Dragon", effect, "Fusion Monster", "Branded");
        when(repository.getCardByName(lubellion.getName())).thenReturn(lubellion);
        when(repository.findAll()).thenReturn(List.of(lubellion));

        ComboService.EffectPrerequisiteResult result = service.checkEffectPrerequisites(
                new ComboService.EffectPrerequisiteRequest(
                        lubellion.getName(), effect, "monsterZone", Map.of("hand", List.of())));

        assertFalse(result.available());
        assertTrue(result.reason().contains("Hand to discard"));
    }

    @Test
    void effectPrerequisitesRequireFusionMaterialsInNamedZones() {
        String effect = "If this card is Fusion Summoned: You can Fusion Summon 1 Level 8 or lower "
                + "Fusion Monster from your Extra Deck, except \"Albion the Branded Dragon\", by banishing "
                + "Fusion Materials mentioned on it from your hand, field, and/or GY.";
        Card albion = card(1, "Albion the Branded Dragon", effect, "Fusion Monster", "Branded");
        albion.setLevel(8);
        albion.setAttribute("DARK");
        Card lubellion = card(2, "Lubellion the Searing Dragon",
                "1 DARK monster + \"Fallen of Albaz\"", "Fusion Monster", "Branded");
        lubellion.setLevel(8);
        Card fallen = card(3, "Fallen of Albaz", "A required monster.", "Effect Monster", "Branded");
        fallen.setAttribute("DARK");
        Card illegalTarget = card(4, "Unmakeable Branded Fusion",
                "2 WATER monsters", "Fusion Monster", "Branded");
        illegalTarget.setLevel(8);
        Card fieldOnlyTarget = card(5, "Field-Only Fusion",
                "2 DARK monsters on the field", "Fusion Monster", "Branded");
        fieldOnlyTarget.setLevel(8);
        Card raceLockedTarget = card(6, "Race-Locked Fusion",
                "1 DARK Dragon-Type monster + 1 Beast-Type monster", "Fusion Monster", "Branded");
        raceLockedTarget.setLevel(8);
        when(repository.getCardByName(albion.getName())).thenReturn(albion);
        when(repository.findAllByNameIn(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(albion, fallen));
        when(repository.findByTypeContainingIgnoreCase("fusion")).thenReturn(List.of(
                albion, lubellion, illegalTarget, fieldOnlyTarget, raceLockedTarget));
        when(repository.findByArchetypeContainingIgnoreCase("Branded"))
                .thenReturn(List.of(lubellion, fallen, illegalTarget, fieldOnlyTarget, raceLockedTarget));

        ComboService.EffectPrerequisiteResult unavailable = service.checkEffectPrerequisites(
                new ComboService.EffectPrerequisiteRequest(
                        albion.getName(), effect, "monsterZone",
                        Map.of("monsterZone", List.of(albion.getName()), "hand", List.of(), "graveyard", List.of())));
        ComboService.EffectPrerequisiteResult available = service.checkEffectPrerequisites(
                new ComboService.EffectPrerequisiteRequest(
                        albion.getName(), effect, "monsterZone",
                        Map.of("monsterZone", List.of(albion.getName()), "hand", List.of(fallen.getName()), "graveyard", List.of())));

        assertFalse(unavailable.available());
        assertTrue(unavailable.reason().contains("Hand / Field / Graveyard"));
        assertTrue(unavailable.legalSummonTargets().isEmpty());
        assertTrue(available.available());
        assertEquals(List.of(lubellion.getName()), available.legalSummonTargets());
    }

    @Test
    void deckFusionPrerequisitesDoNotLoadTheEntireMonsterCatalog() {
        String effect = "Fusion Summon 1 Fusion Monster that mentions \"Fallen of Albaz\" as material "
                + "from your Extra Deck, using 2 monsters from your hand, Deck, or field as material.";
        Card brandedFusion = card(1, "Branded Fusion", effect, "Spell Card", "Branded");
        Card albion = card(2, "Albion the Branded Dragon",
                "\"Fallen of Albaz\" + 1 LIGHT monster\nMust be Fusion Summoned.",
                "Fusion Monster", "Branded");

        when(repository.getCardByName(brandedFusion.getName())).thenReturn(brandedFusion);
        when(repository.findAllByNameIn(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(brandedFusion));
        when(repository.findByTypeContainingIgnoreCaseAndDescriptionContainingIgnoreCase(
                "Fusion", "Fallen of Albaz")).thenReturn(List.of(albion));

        ComboService.EffectPrerequisiteResult result = service.checkEffectPrerequisites(
                new ComboService.EffectPrerequisiteRequest(
                        brandedFusion.getName(), effect, "spellTrapZone",
                        Map.of("spellTrapZone", List.of(brandedFusion.getName()))));

        assertTrue(result.available());
        assertEquals(List.of(albion.getName()), result.legalSummonTargets());
        verify(repository, never()).findByTypeContainingIgnoreCase("monster");
    }

    @Test
    void recognizesModernOncePerTurnWording() {
        Card card = card(1, "Aileron",
                "You can only use 1 of the following effects of \"Aileron\" per turn, and only once that turn.",
                "Effect Monster", "Sky Striker");
        when(repository.getCardByName(card.getName())).thenReturn(card);

        assertEquals("one listed effect", cardAnalysisService.isOncePerTurn(card.getName()));
    }

    @Test
    void mainDeckSearchReturnsAllLegalTargetsAndRejectsExtraDeckMonsters() {
        Card engage = card(1, "Sky Striker Mobilize - Engage!",
                "If you control no monsters in your Main Monster Zone: Add 1 \"Sky Striker\" card from your Deck to your hand, except \"Sky Striker Mobilize - Engage!\".",
                "Spell Card", "Sky Striker");
        Card hornet = card(2, "Sky Striker Mecha - Hornet Drones",
                "If you control no monsters in your Main Monster Zone: Special Summon 1 \"Sky Striker Ace Token\".",
                "Spell Card", "Sky Striker");
        Card linkMonster = card(3, "Sky Striker Ace - Kagari",
                "If this card is Special Summoned: You can target 1 \"Sky Striker\" Spell in your GY; add it to your hand.",
                "Link Monster", "Sky Striker");

        List<Card> related = new ArrayList<>(List.of(hornet, linkMonster));
        for (int index = 0; index < 6; index++) {
            related.add(card(10 + index, "Sky Striker Test Spell " + index,
                    "Target 1 card on the field; destroy it.", "Spell Card", "Sky Striker"));
        }
        stubRelatedCards(engage, related);

        List<ComboService.ComboOption> options = service.getPossibleCombos(engage.getName());

        assertEquals(7, options.size());
        assertTrue(options.stream().anyMatch(option -> option.card().getName().equals(hornet.getName())));
        assertFalse(options.stream().anyMatch(option -> option.card().getName().equals(linkMonster.getName())));
        assertEquals("extender", options.stream()
                .filter(option -> option.card().getName().equals(hornet.getName()))
                .findFirst()
                .orElseThrow()
                .label());
        assertTrue(options.stream()
                .filter(option -> option.card().getName().startsWith("Sky Striker Test Spell"))
                .allMatch(option -> option.label().equals("ender")));
        assertTrue(options.stream().allMatch(option -> option.destination().equals("Hand")));
    }

    @Test
    void quotedFamilySearchWorksWhenTheSourceHasNoArchetype() {
        String effect = "Once per turn: You can add 1 \"Toon\" card, or 1 card that mentions a "
                + "\"Toon\" card's name, from your Deck to your hand.";
        Card source = card(1, "Toon World the Perfect World", effect, "Spell Card", "");
        Card toonMonster = card(2, "Toon Dark Magician", "A Toon monster.", "Effect Monster", "Toon");
        Card unrelated = card(3, "Unrelated Card", "Draw 1 card.", "Spell Card", "");
        when(repository.getCardByName(source.getName())).thenReturn(source);
        when(repository.findByNameContainingIgnoreCase("toon")).thenReturn(List.of(toonMonster));
        when(repository.findByTypeContainingIgnoreCaseAndDescriptionContainingIgnoreCase("", "toon"))
                .thenReturn(List.of(toonMonster, unrelated));

        List<ComboService.ComboOption> options = service.getPossibleCombos(source.getName(), null, effect);

        assertTrue(options.stream().anyMatch(option ->
                option.card().getName().equals(toonMonster.getName())
                        && option.destination().equals("Hand")));
        assertFalse(options.stream().anyMatch(option -> option.card().getName().equals(unrelated.getName())));
    }

    @Test
    void summonRouteIncludesMonstersWhoseTextMentionsTheNamedCard() {
        String effect = "You can Tribute this card; Special Summon from your hand or Deck, "
                + "1 \"Ancient Gear Golem\" or 1 monster that mentions it, except \"Ancient Gear Statue\", "
                + "ignoring its Summoning conditions.";
        Card statue = card(1, "Ancient Gear Statue", effect, "Effect Monster", "Ancient Gear");
        Card golem = card(2, "Ancient Gear Golem", "Cannot be Special Summoned.", "Effect Monster", "Ancient Gear");
        Card commander = card(3, "Ancient Gear Commander",
                "If you control \"Ancient Gear Golem\": You can set 1 \"Ancient Gear\" Trap directly from your Deck.",
                "Effect Monster", "Ancient Gear");
        Card unrelated = card(4, "Ancient Gear Box", "If this card is added from the Deck to your hand: add 1 EARTH Machine monster.",
                "Effect Monster", "Ancient Gear");

        when(repository.getCardByName(statue.getName())).thenReturn(statue);
        when(repository.findByArchetypeContainingIgnoreCase("Ancient Gear"))
                .thenReturn(List.of(golem, commander, unrelated));
        when(repository.findByNameContainingIgnoreCase("Ancient Gear Golem")).thenReturn(List.of(golem));
        when(repository.findByTypeContainingIgnoreCaseAndDescriptionContainingIgnoreCase(
                "monster", "Ancient Gear Golem")).thenReturn(List.of(commander));

        List<ComboService.ComboOption> options = service.getPossibleCombos(statue.getName(), null, effect);

        assertTrue(options.stream().anyMatch(option ->
                option.card().getName().equals(golem.getName())
                        && option.destination().contains("Monster Zone")));
        assertTrue(options.stream().anyMatch(option ->
                option.card().getName().equals(commander.getName())
                        && option.destination().contains("Monster Zone")));
        assertFalse(options.stream().anyMatch(option -> option.card().getName().equals(unrelated.getName())));
    }

    @Test
    void marksTrapAndEndPhaseRoutesAsDelayed() {
        Card trap = card(1, "Test Trap",
                "Special Summon 1 \"Test\" monster from your hand.", "Trap Card", "Test");
        Card extender = card(2, "Test Extender",
                "You can Special Summon this card from your hand.", "Effect Monster", "Test");
        stubRelatedCards(trap, List.of(extender));

        List<ComboService.ComboOption> trapOptions = service.getPossibleCombos(trap.getName());
        assertTrue(trapOptions.get(0).timing().contains("After being Set"));

        Card endPhaseSpell = card(3, "Test End Phase Spell",
                "During the End Phase: Add 1 \"Test\" card from your Deck to your hand.",
                "Spell Card", "Test");
        stubRelatedCards(endPhaseSpell, List.of(extender));

        List<ComboService.ComboOption> endPhaseOptions = service.getPossibleCombos(endPhaseSpell.getName());
        assertTrue(endPhaseOptions.get(0).timing().contains("End Phase"));
    }

    @Test
    void marksPendulumPlacementDestination() {
        Card release = card(1, "Enneacraft Release",
                "Place 1 \"Enneacraft\" Pendulum Monster from your Deck in your Pendulum Zone.",
                "Spell Card", "Enneacraft");
        Card pendulum = card(2, "Enneacraft Test",
                "[ Pendulum Effect ]\nAdd 1 \"Enneacraft\" card from your Deck to your hand.\n[ Monster Effect ]\nFLIP: Draw 1 card.",
                "Pendulum Effect Monster", "Enneacraft");
        stubRelatedCards(release, List.of(pendulum));

        List<ComboService.ComboOption> options = service.getPossibleCombos(release.getName());

        assertEquals("Pendulum Zone", options.get(0).destination());
        assertTrue(options.get(0).reason().contains("place this card from the Deck"));
    }

    @Test
    void selectedEffectPlacesMonstersInTheSpellTrapZoneAsContinuousTraps() {
        String placementEffect = "During your Main Phase: You can place this card you control and 1 "
                + "\"Centur-Ion\" monster from your hand or Deck in your Spell & Trap Zones as face-up "
                + "Continuous Traps.";
        String summonEffect = "During the Main Phase, if this card is a Continuous Trap: "
                + "You can Special Summon this card.";
        Card trudea = card(1, "Centur-Ion Trudea",
                placementEffect + " " + summonEffect,
                "Effect Monster", "Centur-Ion");
        Card primera = card(2, "Centur-Ion Primera",
                "If this card is Special Summoned: Add 1 \"Centur-Ion\" card from your Deck to your hand. "
                        + "During the Main Phase, if this card is a Continuous Trap: You can Special Summon this card.",
                "Tuner Monster", "Centur-Ion");
        stubRelatedCards(trudea, List.of(primera));

        List<ComboService.ComboOption> placementOptions =
                service.getPossibleCombos(trudea.getName(), null, placementEffect);
        List<ComboService.ComboOption> summonOptions =
                service.getPossibleCombos(trudea.getName(), null, summonEffect);

        assertTrue(placementOptions.stream().anyMatch(option ->
                option.card().getName().equals(primera.getName())
                        && option.destination().equals("Spell & Trap Zone as Continuous Trap")));
        assertTrue(placementOptions.stream().noneMatch(option -> option.destination().contains("Monster Zone")));
        assertTrue(summonOptions.isEmpty());
    }

    @Test
    void fusionEffectsDiscoverAllLegalExtraDeckTargetsAndKeepTheirMaterialMovement() {
        String fusionEffect = "If this card is Fusion Summoned: You can Fusion Summon 1 Level 8 or lower "
                + "Fusion Monster from your Extra Deck, except \"Albion the Branded Dragon\", by banishing "
                + "Fusion Materials mentioned on it from your hand, field, and/or GY.";
        Card albion = card(1, "Albion the Branded Dragon",
                "\"Fallen of Albaz\" + 1 LIGHT monster\n" + fusionEffect,
                "Fusion Monster", "Branded");
        albion.setLevel(8);
        Card legalTarget = card(2, "Mirrorjade the Iceblade Dragon",
                "\"Fallen of Albaz\" + 1 Fusion, Synchro, Xyz, or Link Monster\nMust be Fusion Summoned.",
                "Fusion Monster", "Branded");
        legalTarget.setLevel(8);
        Card tooHigh = card(3, "High Level Fusion",
                "2 monsters\nMust be Fusion Summoned.",
                "Fusion Monster", "Other");
        tooHigh.setLevel(10);

        when(repository.getCardByName(albion.getName())).thenReturn(albion);
        when(repository.findByArchetypeContainingIgnoreCase(albion.getArchetype())).thenReturn(List.of());
        when(repository.findByTypeContainingIgnoreCase("fusion"))
                .thenReturn(List.of(albion, legalTarget, tooHigh));

        List<ComboService.ComboOption> options =
                service.getPossibleCombos(albion.getName(), "monsterZone", fusionEffect);

        assertEquals(1, options.size());
        assertEquals(legalTarget.getName(), options.get(0).card().getName());
        assertTrue(options.get(0).cost().toLowerCase().contains("by banishing"));
        assertFalse(options.stream().anyMatch(option -> option.card().getName().equals(tooHigh.getName())));
    }

    @Test
    void brandedFusionFindsLegalTargetsAcrossArchetypes() {
        Card brandedFusion = card(1, "Branded Fusion",
                "Fusion Summon 1 Fusion Monster that mentions \"Fallen of Albaz\" as material from your Extra Deck, using 2 monsters from your hand, Deck, or field.",
                "Spell Card", "Branded");
        Card albion = card(2, "Albion the Branded Dragon",
                "\"Fallen of Albaz\" + 1 LIGHT monster\nIf this card is Fusion Summoned: You can Fusion Summon 1 Level 8 or lower Fusion Monster.",
                "Fusion Monster", "Branded");
        Card lubellion = card(3, "Lubellion the Searing Dragon",
                "1 DARK monster + \"Fallen of Albaz\"\nIf this card is Fusion Summoned: You can discard 1 card.",
                "Fusion Monster", "Albaz");
        Card tooManyMaterials = card(4, "Three Material Albaz Fusion",
                "\"Fallen of Albaz\" + 2 Dragon monsters\nMust be Fusion Summoned.",
                "Fusion Monster", "Albaz");
        Card effectOnlyMention = card(5, "Unrelated Fusion",
                "2 DARK monsters\nThis card is unaffected by effects that mention \"Fallen of Albaz\".",
                "Fusion Monster", "Other");

        when(repository.getCardByName(brandedFusion.getName())).thenReturn(brandedFusion);
        when(repository.findByArchetypeContainingIgnoreCase(brandedFusion.getArchetype())).thenReturn(List.of());
        when(repository.findByNameContainingIgnoreCase(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        when(repository.findByTypeContainingIgnoreCaseAndDescriptionContainingIgnoreCase(
                "Fusion",
                "Fallen of Albaz"))
                .thenReturn(List.of(albion, lubellion, tooManyMaterials, effectOnlyMention));

        List<ComboService.ComboOption> options = service.getPossibleCombos(brandedFusion.getName());

        assertEquals(2, options.size());
        assertTrue(options.stream().anyMatch(option -> option.card().getName().equals(albion.getName())));
        assertTrue(options.stream().anyMatch(option -> option.card().getName().equals(lubellion.getName())));
        assertFalse(options.stream().anyMatch(option -> option.card().getName().equals(tooManyMaterials.getName())));
        assertFalse(options.stream().anyMatch(option -> option.card().getName().equals(effectOnlyMention.getName())));
        assertTrue(options.stream().allMatch(option -> option.cost().contains("Use 2 monsters from your hand, Deck, or field")));
        assertTrue(options.stream()
                .filter(option -> option.card().getName().equals(albion.getName()))
                .allMatch(option -> option.cost().contains("\"Fallen of Albaz\" + 1 LIGHT monster")));
    }

    @Test
    void albionFusionPlanUsesOnlyCurrentZoneCompatibleMaterialTypes() {
        String effect = "If this card is Fusion Summoned: You can Fusion Summon 1 Level 8 or lower "
                + "Fusion Monster from your Extra Deck, except \"Albion the Branded Dragon\", by banishing "
                + "Fusion Materials mentioned on it from your hand, field, and/or GY.";
        Card albion = card(1, "Albion the Branded Dragon", effect, "Fusion Monster", "Branded");
        Card mirrorjade = card(2, "Mirrorjade the Iceblade Dragon",
                "\"Fallen of Albaz\" + 1 Fusion, Synchro, Xyz, or Link Monster\nMust be Fusion Summoned.",
                "Fusion Monster", "Branded");
        mirrorjade.setLevel(8);
        Card fallenOfAlbaz = card(3, "Fallen of Albaz", "A required monster.",
                "Effect Monster", "Branded");
        Card synchroMaterial = card(4, "Legal Synchro Material", "A legal material.",
                "Synchro Monster", "Other");
        Card unrelatedMonster = card(5, "Unrelated Monster", "Not an Extra Deck material.",
                "Effect Monster", "Other");

        when(repository.getCardByName(albion.getName())).thenReturn(albion);
        when(repository.getCardByName(mirrorjade.getName())).thenReturn(mirrorjade);
        when(repository.findByTypeContainingIgnoreCase("monster"))
                .thenReturn(List.of(fallenOfAlbaz, synchroMaterial, unrelatedMonster));

        ComboService.FusionMaterialPlan plan = service.getFusionMaterialPlan(
                albion.getName(), mirrorjade.getName(), effect);

        assertEquals("Hand / Field / Graveyard", plan.availableFrom());
        assertEquals("Banished", plan.destination());
        assertEquals(List.of(fallenOfAlbaz.getName()), plan.slots().get(0).eligibleCards().stream()
                .map(ComboService.MaterialCardOption::name).toList());
        assertTrue(plan.slots().get(1).eligibleCards().stream()
                .anyMatch(candidate -> candidate.name().equals(synchroMaterial.getName())));
        assertFalse(plan.slots().get(1).eligibleCards().stream()
                .anyMatch(candidate -> candidate.name().equals(unrelatedMonster.getName())));
    }

    @Test
    void fusionCostPreservesCannotBeUsedAsFusionMaterialRestriction() {
        Card fusionEffectMonster = card(1, "Restricted Fusion Caster",
                "This card cannot be used as Fusion Material. You can Fusion Summon 1 \"Test\" Fusion Monster from your Extra Deck, using monsters from your hand or field.",
                "Effect Monster", "Test");
        Card fusionTarget = card(2, "Test Fusion",
                "2 \"Test\" monsters\nMust be Fusion Summoned.", "Fusion Monster", "Test");
        stubRelatedCards(fusionEffectMonster, List.of(fusionTarget));

        List<ComboService.ComboOption> options = service.getPossibleCombos(fusionEffectMonster.getName());

        assertEquals(1, options.size());
        assertTrue(options.get(0).cost().contains("Materials: 2 \"Test\" monsters"));
        assertTrue(options.get(0).cost().contains(
                "Restriction: This card cannot be used as Fusion Material"));
    }

    @Test
    void fusionMaterialPlanFiltersProhibitedCardsAndTracksDestination() {
        Card source = card(1, "Test Fusion Spell",
                "Fusion Summon 1 Fusion Monster from your Extra Deck, using monsters from your hand, Deck, or field.",
                "Spell Card", "Test");
        Card target = card(2, "Test Fusion Dragon",
                "\"Named Material\" + 1 LIGHT monster\nMust be Fusion Summoned.",
                "Fusion Monster", "Test");
        Card namedMaterial = card(3, "Named Material", "A required monster.", "Normal Monster", "Test");
        Card lightMaterial = card(4, "Legal Light", "A legal material.", "Effect Monster", "Test");
        lightMaterial.setAttribute("LIGHT");
        Card prohibitedMaterial = card(5, "Blocked Light",
                "This card cannot be used as Fusion Material.", "Effect Monster", "Test");
        prohibitedMaterial.setAttribute("LIGHT");

        when(repository.getCardByName(source.getName())).thenReturn(source);
        when(repository.getCardByName(target.getName())).thenReturn(target);
        when(repository.findByTypeContainingIgnoreCase("monster"))
                .thenReturn(List.of(namedMaterial, lightMaterial, prohibitedMaterial));

        ComboService.FusionMaterialPlan plan =
                service.getFusionMaterialPlan(source.getName(), target.getName());

        assertEquals("Graveyard", plan.destination());
        assertTrue(plan.availableFrom().contains("Hand"));
        assertEquals(2, plan.slots().size());
        assertEquals(List.of(namedMaterial.getName()), plan.slots().get(0).eligibleCards().stream()
                .map(ComboService.MaterialCardOption::name).toList());
        assertTrue(plan.slots().get(1).eligibleCards().stream()
                .anyMatch(candidate -> candidate.name().equals(lightMaterial.getName())));
        assertFalse(plan.slots().get(1).eligibleCards().stream()
                .anyMatch(candidate -> candidate.name().equals(prohibitedMaterial.getName())));
    }

    @Test
    void fusionMaterialPlanSendsBanishingFusionCostsToBanishedZone() {
        Card source = card(1, "Grave Fusion",
                "Fusion Summon 1 Fusion Monster by banishing Fusion Materials from your field or GY.",
                "Effect Monster", "Test");
        Card target = card(2, "Generic Fusion",
                "2 monsters\nMust be Fusion Summoned.", "Fusion Monster", "Test");
        Card material = card(3, "Material", "A legal material.", "Effect Monster", "Test");

        when(repository.getCardByName(source.getName())).thenReturn(source);
        when(repository.getCardByName(target.getName())).thenReturn(target);
        when(repository.findByTypeContainingIgnoreCase("monster")).thenReturn(List.of(material));

        ComboService.FusionMaterialPlan plan =
                service.getFusionMaterialPlan(source.getName(), target.getName());

        assertEquals("Banished", plan.destination());
        assertTrue(plan.availableFrom().contains("Graveyard"));
        assertEquals(2, plan.slots().get(0).count());
    }

    @Test
    void graveyardComboRequestsUseGraveyardEffectTextAndSourceZone() {
        Card graveyardCard = card(1, "Test Graveyard Card",
                "While this card is on the field: It gains 500 ATK. If this card is sent to the GY: Add 1 \"Test\" card from your Deck to your hand.",
                "Effect Monster", "Test");
        Card searchTarget = card(2, "Test Search Target",
                "You can Special Summon this card from your hand.", "Effect Monster", "Test");
        stubRelatedCards(graveyardCard, List.of(searchTarget));

        List<ComboService.ComboOption> options =
                service.getPossibleCombos(graveyardCard.getName(), "graveyard");

        assertEquals(1, options.size());
        assertEquals("Graveyard", options.get(0).sourceZone());
        assertTrue(options.get(0).reason().contains(graveyardCard.getName()));
    }

    @Test
    void genericDiscardCostCreatesAHandToGraveyardPaymentPlan() {
        Card source = card(1, "Discard Starter",
                "You can discard 1 card; Add 1 \"Test\" monster from your Deck to your hand.",
                "Spell Card", "Test");
        Card target = card(2, "Test Target",
                "You can Special Summon this card from your hand.", "Effect Monster", "Test");
        Card payment = card(3, "Discarded Card", "A card.", "Effect Monster", "Other");

        when(repository.getCardByName(source.getName())).thenReturn(source);
        when(repository.getCardByName(target.getName())).thenReturn(target);
        when(repository.findAll()).thenReturn(List.of(payment));

        ComboService.FusionMaterialPlan plan =
                service.getCardCostPlan(source.getName(), target.getName());

        assertEquals("Hand", plan.availableFrom());
        assertEquals("Graveyard", plan.destination());
        assertEquals(1, plan.slots().get(0).count());
        assertTrue(plan.slots().get(0).eligibleCards().stream()
                .anyMatch(candidate -> candidate.name().equals(payment.getName())));
    }

    @Test
    void revealingACardDoesNotCreateASearchOrMovementRoute() {
        Card source = card(1, "Reveal Only",
                "Reveal 1 \"Test\" card in your hand; this card gains 500 ATK.",
                "Effect Monster", "Test");
        Card revealedCard = card(2, "Test Revealed Card",
                "A card that stays in its original location.", "Effect Monster", "Test");
        stubRelatedCards(source, List.of(revealedCard));
        when(repository.findByNameContainingIgnoreCase("Test")).thenReturn(List.of(revealedCard));

        List<ComboService.ComboOption> options = service.getPossibleCombos(source.getName());

        assertTrue(options.isEmpty());
    }

    @Test
    void selfSummoningTextDoesNotUnlockOtherArchetypeCards() {
        Card source = card(1, "Test Self Summoner",
                "If you control no monsters: You can Special Summon this card from your hand.",
                "Effect Monster", "Test");
        Card unrelatedExtender = card(2, "Test Other Extender",
                "You can Special Summon this card from your hand.", "Effect Monster", "Test");
        stubRelatedCards(source, List.of(unrelatedExtender));

        List<ComboService.ComboOption> options = service.getPossibleCombos(source.getName());

        assertTrue(options.isEmpty());
    }

    @Test
    void unsupportedExtraDeckMechanicsAreSuppressedInsteadOfGuessed() {
        Card source = card(1, "Test Synchro Effect",
                "Immediately after this effect resolves, Synchro Summon 1 \"Test Synchro\" monster.",
                "Effect Monster", "Test");
        Card synchro = card(2, "Test Synchro",
                "1 Tuner + 1+ non-Tuner monsters", "Synchro Monster", "Test");
        stubRelatedCards(source, List.of(synchro));
        when(repository.findByNameContainingIgnoreCase("Test Synchro")).thenReturn(List.of(synchro));

        List<ComboService.ComboOption> options = service.getPossibleCombos(source.getName());

        assertTrue(options.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void importsOnlyMissingCardsWithAllApplicableData() {
        Card existingCard = card(1, "Existing Card", "Original description", "Spell Card", "Existing");
        when(repository.findAll()).thenReturn(List.of(existingCard));

        YugiohService.CardData existingApiCard = new YugiohService.CardData(
                "existing card",
                "Changed API description",
                "Spell Card",
                null,
                null,
                null,
                "Normal",
                null,
                null,
                "Existing",
                null,
                null,
                List.of(new YugiohService.CardImage("https://example.com/existing.jpg")));
        YugiohService.CardData newLinkCard = new YugiohService.CardData(
                "New Link Card",
                "A newly released Link Monster.",
                "Link Monster",
                2500,
                null,
                null,
                "Cyberse",
                "LIGHT",
                3,
                "New Archetype",
                new String[]{"Top", "Bottom-Left", "Bottom-Right"},
                null,
                List.of(new YugiohService.CardImage("https://example.com/new-link.jpg")));

        int imported = cardCatalogService.importNewCards(
                List.of(existingApiCard, newLinkCard),
                Set.of("New Link Card"));

        ArgumentCaptor<Iterable<Card>> savedCardsCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(savedCardsCaptor.capture());
        List<Card> savedCards = StreamSupport.stream(
                        savedCardsCaptor.getValue().spliterator(), false)
                .toList();

        assertEquals(1, imported);
        assertEquals(1, savedCards.size());
        assertEquals("Original description", existingCard.getDescription());

        Card savedCard = savedCards.get(0);
        assertEquals("New Link Card", savedCard.getName());
        assertEquals("A newly released Link Monster.", savedCard.getDescription());
        assertEquals("Link Monster", savedCard.getType());
        assertEquals(0, savedCard.getWeight());
        assertEquals(2500, savedCard.getAtk());
        assertNull(savedCard.getDef());
        assertNull(savedCard.getLevel());
        assertEquals("Cyberse", savedCard.getRace());
        assertEquals("LIGHT", savedCard.getAttribute());
        assertEquals("New Archetype", savedCard.getArchetype());
        assertEquals(3, savedCard.getLinkvalue());
        assertEquals(List.of("Top", "Bottom-Left", "Bottom-Right"), savedCard.getLinkmarkers());
        assertEquals("https://example.com/new-link.jpg", savedCard.getCardImageUrl());
        assertTrue(savedCard.isStaple());
    }

    private void stubRelatedCards(Card source, List<Card> related) {
        when(repository.getCardByName(source.getName())).thenReturn(source);
        when(repository.findByArchetypeContainingIgnoreCase(source.getArchetype())).thenReturn(related);
        when(repository.findByNameContainingIgnoreCase(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
    }

    private Card card(long id, String name, String description, String type, String archetype) {
        Card card = new Card(name, description, type, 0);
        card.setArchetype(archetype);
        try {
            Field idField = Card.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(card, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        return card;
    }
}
