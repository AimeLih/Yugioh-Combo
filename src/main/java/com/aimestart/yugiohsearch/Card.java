package com.aimestart.yugiohsearch;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "cards")
public class Card {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String type;

    @Column(nullable = false)
    private int weight;

    @Column
    private Integer atk;

    @Column
    private Integer def;

    @Column
    private Integer level;

    @Column(columnDefinition = "TEXT")
    private String race;

    @Column(columnDefinition = "TEXT")
    private String attribute;

    @Column(columnDefinition = "TEXT")
    private String archetype;

    @Column
    private Integer scale;

    @Column
    private Integer linkvalue;

    @Column(name = "LinkMarkers", columnDefinition = "text[]")
    private List<String> linkmarkers = new ArrayList<>();

    @Column
    private Boolean staple;

    @Column(name = "card_image_url", columnDefinition = "TEXT")
    private String cardImageUrl;
    public Card() {
    }

    public Card(String name, String description, String type, int weight) {
        this.name = name;
        this.description = description;
        this.type = type;
        this.weight = weight;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public Integer getAtk() {
        return atk;
    }

    public void setAtk(Integer atk) {
        this.atk = atk;
    }

    public Integer getDef() {
        return def;
    }

    public void setDef(Integer def) {
        this.def = def;
    }

    public String getRace() {
        return race;
    }

    public void setRace(String race) {
        this.race = race;
    }

    public String getAttribute() {
        return attribute;
    }

    public void setAttribute(String attribute) {
        this.attribute = attribute;
    }

    public String getArchetype() {
        return archetype;
    }

    public void setArchetype(String archetype) {
        this.archetype = archetype;
    }

    public Integer getScale() {
        return scale;
    }

    public void setScale(Integer scale) {
        this.scale = scale;
    }

    public Integer getLinkvalue() {
        return linkvalue;
    }

    public void setLinkvalue(Integer linkvalue) {
        this.linkvalue = linkvalue;
    }

    public List<String> getLinkmarkers() {
        return linkmarkers;
    }

    public void setLinkmarkers(List<String> linkmarkers) {
        this.linkmarkers = linkmarkers;
    }

    public Boolean isStaple() {
        return staple;
    }

    public void setStaple(Boolean staple) {
        this.staple = staple;
    }

    public String getCardImageUrl() {
        return cardImageUrl;
    }

    public void setCardImageUrl(String cardImageUrl) {
        this.cardImageUrl = cardImageUrl;
    }
}
