package com.aimestart.yugiohsearch;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// Rest client for the official Yu-Gi-Oh API is used to fetch card data from the Yu-Gi-Oh API
@Service
public class YugiohService {

    private static final String API_BASE_URL = "https://db.ygoprodeck.com/api/v7";

    private final RestClient restClient;

    public record CardInfoResponse(List<CardData> data) {}

    public record CardImage(@JsonProperty("image_url") String imageUrl) {}

    public record CardData(
            String name,
            String desc,
            String type,
            Integer atk,
            Integer def,
            Integer level,
            String race,
            String attribute,
            Integer linkval,
            String archetype,
            String[] linkmarkers,
            Integer scale,
            @JsonProperty("card_images") List<CardImage> cardImages
    ) {}

    public YugiohService(RestClient.Builder builder) {
        this.restClient = builder.baseUrl(API_BASE_URL).build();
    }

    public List<CardData> fetchAllCards() {
        CardInfoResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/cardinfo.php").build())
                .retrieve()
                .body(CardInfoResponse.class);

        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new IllegalStateException("Cards not found in the Yu-Gi-Oh API response");
        }
        return response.data();
    }

    public Set<String> fetchStapleCardNames() {
        try {
            CardInfoResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/cardinfo.php")
                            .queryParam("staple", "yes")
                            .build())
                    .retrieve()
                    .body(CardInfoResponse.class);

            if (response != null && response.data() != null) {
                return response.data().stream()
                        .map(CardData::name)
                        .collect(Collectors.toSet());
            }
        } catch (Exception exception) {
            System.out.println("Could not fetch staples: " + exception.getMessage());
        }
        return Collections.emptySet();
    }
}
