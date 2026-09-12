package com.aimestart.yugiohsearch;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/yugioh")
@CrossOrigin(originPatterns = {"http://localhost:5173",
        "https://yugioh-combo.vercel.app"
})


public class YugiohController {

    private static final String IMPORT_TOKEN_HEADER = "X-Import-Token";
    //Reads import token that was received from the render environment variables
    @Value("${import.token}")
    private String importToken;

    private final CardCatalogService cardCatalogService;
    private final CardAnalysisService cardAnalysisService;
    private final ComboService comboService;

    public YugiohController(
            CardCatalogService cardCatalogService,
            CardAnalysisService cardAnalysisService,
            ComboService comboService
    ) {
        this.cardCatalogService = cardCatalogService;
        this.cardAnalysisService = cardAnalysisService;
        this.comboService = comboService;
    }
    //imports all cards from the yugioh api database into the Neon database
    @PostMapping("/import")
    public void importAllCards(
            @RequestHeader(value = IMPORT_TOKEN_HEADER, required = false) String providedToken
    ) {
        requireImportToken(providedToken);
        cardCatalogService.importAllCards();
    }
    //gets the specific image of a card
    @GetMapping("/card/image")
    public String getImage(@RequestParam String name){
        Card card = cardCatalogService.getCardByName(name);
        return cardCatalogService.getImage(card);
    }
    //returns the info of a specific card
    @Cacheable(value = "Card", key = "#name.toLowerCase()" )
    @GetMapping("/card")
    public Card getCardByName(@RequestParam String name){
        return cardCatalogService.getCardByName(name);
    }
    //Combo logic
    @GetMapping("/card/combos")
    public List<ComboService.ComboOption> getPossibleCombos(
            @RequestParam String name,
            @RequestParam(required = false) String zone,
            @RequestParam(required = false) String effect
    ) {
        return comboService.getPossibleCombos(name, zone, effect);
    }
    @PostMapping("/card/effect-prerequisites")
    public ComboService.EffectPrerequisiteResult checkEffectPrerequisites(
            @RequestBody ComboService.EffectPrerequisiteRequest request
    ) {
        return comboService.checkEffectPrerequisites(request);
    }
    //Fusion logic
    @GetMapping("/card/fusion-materials")
    public ComboService.FusionMaterialPlan getFusionMaterials(
            @RequestParam String source,
            @RequestParam String target,
            @RequestParam(required = false) String effect
    ) {
        return comboService.getFusionMaterialPlan(source, target, effect);
    }
    //card cost logic
    @GetMapping("/card/cost-materials")
    public ComboService.FusionMaterialPlan getCostMaterials(
            @RequestParam String source,
            @RequestParam String target,
            @RequestParam(required = false) String effect
    ) {
        return comboService.getCardCostPlan(source, target, effect);
    }
    //returns cards by substrings
    @Cacheable(value = "CardsBySubString", key = "#name.toLowerCase()" )
    @GetMapping("/card/substring")
    public List<Card> getCardBySubstring(@RequestParam String name){
        return cardCatalogService.getCardsBySubstring(name);
    }
    //returns all cards in the database
    @Cacheable("Cards")
    @GetMapping("/card/all")
    public List<Card> getAllCards(){
        return cardCatalogService.getAllCards();
    }
    //updates a card weight
    @PutMapping("/card/update")
    public void updatingCards(
            @RequestHeader(value = IMPORT_TOKEN_HEADER, required = false) String providedToken
    ) {
        requireImportToken(providedToken);
        cardCatalogService.updateExistingCardsWeight();
    }
    //updates cards if their info is outdated - very unlikely to ever happen
    @PutMapping("/card/update/database")
    public void updatingExistingCards(
            @RequestHeader(value = IMPORT_TOKEN_HEADER, required = false) String providedToken
    ) {
        requireImportToken(providedToken);
        cardCatalogService.updateExistingCards();
    }
    //checks if its an extender
    @GetMapping("/card/pattern")
    public String patternCard(@RequestParam String name){
        return cardAnalysisService.ifExtender(name);
    }
    //checks for onceperturn
    @GetMapping("/card/onceprturn")
    public String isOncePerTurn(@RequestParam String name){
        return cardAnalysisService.isOncePerTurn(name);
    }

    //puts all cards weight to 0
    @PutMapping("/card/update/zero")
    public void allCardWeightZero(
            @RequestHeader(value = IMPORT_TOKEN_HEADER, required = false) String providedToken
    ) {
        requireImportToken(providedToken);
        cardCatalogService.allCardWeightZero();
    }

    public record ImportResult(int cardsAdded) {}
    //imports new cards from the yugioh api database into the Neon database and also evicts all caches in memory
    @CacheEvict(value = {"Cards", "CardsBySubString", "Card"}, allEntries = true)
    @PostMapping("/admin/import")
    public ImportResult importNewCards(
            @RequestHeader(value = IMPORT_TOKEN_HEADER, required = false)
            String providedToken
    ) {
        requireImportToken(providedToken);
        return new ImportResult(cardCatalogService.importNewCards());
    }

    private void requireImportToken(String providedToken) {
        if (importToken.isBlank()
                || !importToken.equals(providedToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Invalid import token"
            );
        }
    }


}
