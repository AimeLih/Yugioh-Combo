package com.aimestart.yugiohsearch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Collection;
public interface CardRepository extends JpaRepository<Card, Long>{
    boolean existsByName(String name);
    Card getCardByName(String name);
    List<Card> findByNameContainingIgnoreCase(String name);
    List<Card> findByArchetypeContainingIgnoreCase(String archetype);
    List<Card> findByTypeContainingIgnoreCase(String type);
    List<Card> findAllByNameIn(Collection<String> names);
    List<Card> findByTypeContainingIgnoreCaseAndDescriptionContainingIgnoreCase(String type, String description);

}
