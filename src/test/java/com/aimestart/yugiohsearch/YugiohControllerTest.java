package com.aimestart.yugiohsearch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class YugiohControllerTest {

    private final CardCatalogService cardCatalogService = mock(CardCatalogService.class);
    private final CardAnalysisService cardAnalysisService = mock(CardAnalysisService.class);
    private final ComboService comboService = mock(ComboService.class);
    private final YugiohController controller = new YugiohController(
            cardCatalogService,
            cardAnalysisService,
            comboService);

    @BeforeEach
    void configureImportToken() {
        ReflectionTestUtils.setField(controller, "importToken", "test-import-token");
    }

    @Test
    void databaseChangingEndpointsRejectMissingOrInvalidTokens() {
        assertUnauthorized(() -> controller.importAllCards(null));
        assertUnauthorized(() -> controller.updatingCards("wrong-token"));
        assertUnauthorized(() -> controller.updatingExistingCards(null));
        assertUnauthorized(() -> controller.allCardWeightZero("wrong-token"));
        assertUnauthorized(() -> controller.importNewCards(null));

        verifyNoInteractions(cardCatalogService, cardAnalysisService, comboService);
    }

    @Test
    void databaseChangingEndpointsAcceptTheConfiguredToken() {
        when(cardCatalogService.importNewCards()).thenReturn(4);

        controller.importAllCards("test-import-token");
        controller.updatingCards("test-import-token");
        controller.updatingExistingCards("test-import-token");
        controller.allCardWeightZero("test-import-token");
        YugiohController.ImportResult result = controller.importNewCards("test-import-token");

        verify(cardCatalogService).importAllCards();
        verify(cardCatalogService).updateExistingCardsWeight();
        verify(cardCatalogService).updateExistingCards();
        verify(cardCatalogService).allCardWeightZero();
        verify(cardCatalogService).importNewCards();
        assertEquals(4, result.cardsAdded());
    }

    private void assertUnauthorized(Executable request) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, request);
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }
}
