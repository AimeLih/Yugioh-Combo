package com.aimestart.yugiohsearch;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

// manages the locally persisted card catalog
@Service
public class CardCatalogService {

    private final YugiohService yugiohService;
    private final CardRepository cardRepository;

    public CardCatalogService(YugiohService yugiohService, CardRepository cardRepository) {
        this.yugiohService = yugiohService;
        this.cardRepository = cardRepository;
    }

    public void importAllCards() {
        List<Card> cardsToSave = yugiohService.fetchAllCards().stream()
                .map(card -> new Card(card.name(), card.desc(), card.type(), 0))
                .toList();
        cardRepository.saveAll(cardsToSave);
        updateExistingCards();
    }

    @Transactional
    public int importNewCards() {
        return importNewCards(
                yugiohService.fetchAllCards(),
                yugiohService.fetchStapleCardNames());
    }

    int importNewCards(List<YugiohService.CardData> apiCards, Set<String> stapleNames) {
        Set<String> existingNames = cardRepository.findAll().stream()
                .map(Card::getName)
                .map(this::normalizedCardName)
                .collect(Collectors.toSet());

        List<Card> newCards = apiCards.stream()
                .filter(apiCard -> existingNames.add(normalizedCardName(apiCard.name())))
                .map(apiCard -> createCard(apiCard, stapleNames))
                .toList();

        if (!newCards.isEmpty()) {
            cardRepository.saveAll(newCards);
        }
        return newCards.size();
    }

    public void updateExistingCardsWeight() {
        List<Card> cards = cardRepository.findAll();
        Map<String, String> cardTypes = yugiohService.fetchAllCards().stream()
                .collect(Collectors.toMap(
                        YugiohService.CardData::name,
                        YugiohService.CardData::type,
                        (first, ignored) -> first));

        for (Card card : cards) {
            String type = cardTypes.get(card.getName());
            if (type != null) {
                card.setType(type);
                card.setWeight(weightForType(type));
            }
        }
        cardRepository.saveAll(cards);
    }

    public void allCardWeightZero() {
        List<Card> cards = cardRepository.findAll();
        cards.forEach(card -> card.setWeight(0));
        cardRepository.saveAll(cards);
    }

    public void updateExistingCards() {
        List<Card> cards = cardRepository.findAll();
        Map<String, YugiohService.CardData> apiCards = yugiohService.fetchAllCards().stream()
                .collect(Collectors.toMap(
                        YugiohService.CardData::name,
                        Function.identity(),
                        (first, ignored) -> first));

        Set<String> stapleNames = yugiohService.fetchStapleCardNames();
        for (Card card : cards) {
            YugiohService.CardData apiCard = apiCards.get(card.getName());
            if (apiCard != null) {
                applyApiData(card, apiCard, stapleNames);
            }
        }
        cardRepository.saveAll(cards);
    }

    public List<Card> getAllCards() {
        return cardRepository.findAll();
    }

    public Card getCardByName(String name) {
        return cardRepository.getCardByName(name);
    }

    public List<Card> getCardsBySubstring(String name) {
        List<Card> cards = cardRepository.findByNameContainingIgnoreCase(name);
        if (cards.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No cards found with that name");
        }
        return cards;
    }

    public String getImage(Card card) {
        return card.getCardImageUrl();
    }

    private int weightForType(String type) {
        if (type.contains("Spell")) return 4;
        if (type.contains("Trap")) return 3;
        if (type.contains("Monster")) return type.equals("Normal Monster") ? 1 : 2;
        return 1;
    }

    private void applyApiData(
            Card card,
            YugiohService.CardData apiCard,
            Set<String> stapleNames
    ) {
        card.setStaple(stapleNames.contains(card.getName()));

        if (apiCard.cardImages() != null && !apiCard.cardImages().isEmpty()) {
            card.setCardImageUrl(apiCard.cardImages().get(0).imageUrl());
        }

        String cardType = apiCard.type() != null ? apiCard.type() : card.getType();
        if (cardType == null) return;

        card.setType(cardType);
        if (cardType.contains("Pendulum")) {
            card.setScale(apiCard.scale());
        }

        if (cardType.contains("Monster")) {
            card.setAtk(apiCard.atk());
            card.setDef(apiCard.def());
            card.setLevel(apiCard.level());
            card.setRace(apiCard.race());
            card.setAttribute(apiCard.attribute());

            if (cardType.contains("Link")) {
                card.setLinkvalue(apiCard.linkval());
                if (apiCard.linkmarkers() != null) {
                    card.setLinkmarkers(Arrays.asList(apiCard.linkmarkers()));
                }
            }
        } else if (cardType.contains("Spell") || cardType.contains("Trap")) {
            card.setRace(apiCard.race());
        }

        if (apiCard.archetype() != null && !apiCard.archetype().isBlank()) {
            card.setArchetype(apiCard.archetype());
        }
    }

    private Card createCard(YugiohService.CardData apiCard, Set<String> stapleNames) {
        Card card = new Card(apiCard.name(), apiCard.desc(), apiCard.type(), 0);
        applyApiData(card, apiCard, stapleNames);
        return card;
    }

    private String normalizedCardName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
