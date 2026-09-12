package com.aimestart.yugiohsearch;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

// provides facts derived from a card's effect text
@Service
public class CardAnalysisService {

    private final CardRepository cardRepository;

    public CardAnalysisService(CardRepository cardRepository) {
        this.cardRepository = cardRepository;
    }

    public String ifExtender(String focusedCard) {
        String description = safeLower(requireCard(focusedCard).getDescription());
        if (Pattern.compile("summon\\s+\\d+").matcher(description).find()) {
            return "summon extender";
        }
        if (Pattern.compile("add\\s+\\d+").matcher(description).find()) {
            return "add extender";
        }
        return "not an extender";
    }

    public String isOncePerTurn(String focusedCard) {
        String description = normalizeCardText(requireCard(focusedCard).getDescription());

        if (Pattern.compile(
                "you can only use each (?:effect|of the following effects).*?once per turn",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(description).find()) {
            return "one of each";
        }
        if (Pattern.compile(
                "you can only use (?:1|one) of the following effects.*?(?:once per turn|only once that turn)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(description).find()) {
            return "one listed effect";
        }
        if (Pattern.compile(
                "you can only activate (?:1|one) .*? per turn",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(description).find()) {
            return "one activation";
        }
        if (Pattern.compile(
                "you can only use (?:this|the) effect.*?once per turn",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(description).find()) {
            return "one effect";
        }
        if (Pattern.compile(
                "you can only use .*?(?:once per turn|only once that turn)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(description).find()
                || description.toLowerCase().contains("once per turn")) {
            return "once per turn";
        }
        return "Not once per turn";
    }

    private Card requireCard(String name) {
        Card card = cardRepository.getCardByName(name);
        if (card == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Card not found: " + name);
        }
        return card;
    }

    private String normalizeCardText(String value) {
        return value == null ? "" : value
                .replace('\u2019', '\'')
                .replace('\u2018', '\'')
                .replace('\u201c', '"')
                .replace('\u201d', '"')
                .replace("\r\n", "\n")
                .trim();
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase();
    }
}
