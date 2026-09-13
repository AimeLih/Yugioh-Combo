package com.aimestart.yugiohsearch;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface CardRepository extends JpaRepository<Card, Long> {
    interface CostCardView {
        Long getId();
        String getName();
        String getType();
        String getArchetype();
    }

    Card getCardByName(String name);
    List<Card> findByNameContainingIgnoreCase(String name);
    List<Card> findTop50ByNameContainingIgnoreCaseOrderByNameAsc(String name);
    List<Card> findByArchetypeContainingIgnoreCase(String archetype);
    List<Card> findByTypeContainingIgnoreCase(String type);
    List<Card> findAllByNameIn(Collection<String> names);
    List<Card> findByTypeContainingIgnoreCaseAndDescriptionContainingIgnoreCase(String type, String description);
    List<CostCardView> findAllProjectedBy();

    @Query("select lower(card.name) from Card card")
    Set<String> findAllNormalizedNames();
}
