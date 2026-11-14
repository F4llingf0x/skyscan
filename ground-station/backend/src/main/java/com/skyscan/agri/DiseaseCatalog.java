package com.skyscan.agri;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * Static agronomy knowledge base: disease id -> name, affected plant kinds,
 * possible cures, and what a human should check on the ground. In a real
 * deployment this would be data-driven (DB/CMS) and far larger; here it is a
 * curated seed sufficient to produce a genuinely useful report. NOT a
 * substitute for an agronomist — the report says so.
 */
@Component
public class DiseaseCatalog {

    public record Disease(String id, String name, List<String> affects,
                          List<String> cures, List<String> checks) {}

    private final Map<String, Disease> byId = new LinkedHashMap<>();

    public DiseaseCatalog() {
        add(new Disease("late_blight", "Late blight (Phytophthora infestans)",
                List.of("CROP"),
                List.of("Remove and destroy infected foliage immediately",
                        "Apply a copper-based or chlorothalonil fungicide on a preventive schedule",
                        "Improve airflow and avoid overhead irrigation late in the day"),
                List.of("Dark water-soaked lesions on leaves/stems with pale margins",
                        "White downy growth on leaf undersides in humid mornings",
                        "Rapid spread after cool, wet weather")));
        add(new Disease("powdery_mildew", "Powdery mildew",
                List.of("CROP", "TREE"),
                List.of("Apply sulphur or potassium-bicarbonate fungicide",
                        "Prune to open the canopy and increase light/airflow",
                        "Avoid excess nitrogen fertilization"),
                List.of("White powdery coating on upper leaf surfaces",
                        "Leaf curling or yellowing under the coating",
                        "Spread from lower/shaded leaves upward")));
        add(new Disease("leaf_rust", "Leaf rust (Puccinia spp.)",
                List.of("CROP", "TREE"),
                List.of("Apply a triazole fungicide at first sign",
                        "Remove volunteer hosts and heavily infected leaves",
                        "Favor resistant varieties in the next planting"),
                List.of("Orange-brown pustules on leaf undersides",
                        "Yellow flecking on the upper surface above pustules",
                        "Premature leaf drop")));
        add(new Disease("fire_blight", "Fire blight (Erwinia amylovora)",
                List.of("TREE"),
                List.of("Prune 30 cm below visible cankers, sterilizing tools between cuts",
                        "Apply copper at bud break; streptomycin during bloom where permitted",
                        "Remove severely infected trees to protect the block"),
                List.of("Blackened, shepherd's-crook wilted shoot tips",
                        "Oozing cankers on branches",
                        "Sudden wilting of blossoms and young shoots")));
        add(new Disease("nutrient_deficiency", "Nutrient deficiency (chlorosis)",
                List.of("CROP", "TREE"),
                List.of("Soil + tissue test to identify the limiting nutrient",
                        "Correct with the appropriate fertilizer (often N or Fe)",
                        "Adjust pH if it is locking out nutrient uptake"),
                List.of("Interveinal yellowing (Fe) or uniform paling (N)",
                        "Pattern on old vs new leaves indicates which nutrient",
                        "Stunted growth relative to neighbors")));
        add(new Disease("water_stress", "Water stress",
                List.of("CROP", "TREE"),
                List.of("Check and correct irrigation scheduling/coverage",
                        "Mulch to conserve soil moisture",
                        "Inspect for root damage or compaction"),
                List.of("Wilting during the hottest part of the day",
                        "Leaf-edge scorching/browning",
                        "Low canopy vigor in patches following soil type")));
    }

    private void add(Disease d) { byId.put(d.id(), d); }

    public Disease get(String id) { return byId.get(id); }
    public Collection<Disease> all() { return byId.values(); }
    public List<Disease> forKind(String kind) {
        List<Disease> out = new ArrayList<>();
        for (Disease d : byId.values()) if (d.affects().contains(kind)) out.add(d);
        return out;
    }
}
