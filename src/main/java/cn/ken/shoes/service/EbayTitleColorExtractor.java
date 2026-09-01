package cn.ken.shoes.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

final class EbayTitleColorExtractor {

    private static final List<ColorTerm> TERMS = List.of(
            new ColorTerm("black", "Black"),
            new ColorTerm("white", "White"),
            new ColorTerm("blue", "Blue"),
            new ColorTerm("pink", "Pink"),
            new ColorTerm("red", "Red"),
            new ColorTerm("gray", "Gray"),
            new ColorTerm("grey", "Gray"),
            new ColorTerm("green", "Green"),
            new ColorTerm("orange", "Orange"),
            new ColorTerm("purple", "Purple"),
            new ColorTerm("yellow", "Yellow"),
            new ColorTerm("brown", "Brown"),
            new ColorTerm("beige", "Beige"),
            new ColorTerm("silver", "Silver"),
            new ColorTerm("gold", "Gold"),
            new ColorTerm("navy", "Blue"),
            new ColorTerm("lime", "Green"),
            new ColorTerm("grape", "Purple"),
            new ColorTerm("xuanwu", "Black"),
            new ColorTerm("dodgers", "Blue"),
            new ColorTerm("cement", "Gray"),
            new ColorTerm("plum", "Purple"),
            new ColorTerm("birch", "Beige"),
            new ColorTerm("peacoat", "Blue"),
            new ColorTerm("kill bill", "Yellow"),
            new ColorTerm("tokyo t23", "Yellow"),
            new ColorTerm("citrus", "Orange"),
            new ColorTerm("vanilla ice", "White"),
            new ColorTerm("ivory", "White"),
            new ColorTerm("sail", "White"),
            new ColorTerm("cream", "White"),
            new ColorTerm("multi color", "Multicolor"),
            new ColorTerm("multicolor", "Multicolor")
    );

    private EbayTitleColorExtractor() {
    }

    static String extract(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        String normalized = rawText.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return null;
        }

        String padded = " " + normalized + " ";
        List<ColorMatch> matches = new ArrayList<>();
        for (int i = 0; i < TERMS.size(); i++) {
            ColorTerm term = TERMS.get(i);
            int position = padded.indexOf(" " + term.alias() + " ");
            if (position >= 0) {
                matches.add(new ColorMatch(position, i, term.color()));
            }
        }
        matches.sort(Comparator.comparingInt(ColorMatch::position)
                .thenComparingInt(ColorMatch::termOrder));

        LinkedHashSet<String> colors = new LinkedHashSet<>();
        matches.forEach(match -> colors.add(match.color()));
        return colors.isEmpty() ? null : String.join("/", colors);
    }

    private record ColorTerm(String alias, String color) {
    }

    private record ColorMatch(int position, int termOrder, String color) {
    }
}
