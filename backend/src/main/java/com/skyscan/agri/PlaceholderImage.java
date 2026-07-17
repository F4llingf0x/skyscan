package com.skyscan.agri;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * STUB evidence photos for simulated flights and demo seeds: a clearly-marked
 * placeholder card (label, kind/species, key numbers, "SIMULATED IMAGE"
 * watermark). Real flights replace these with actual close-ups uploaded by the
 * drone — the placeholder is only generated when no photo exists.
 */
public final class PlaceholderImage {

    private PlaceholderImage() {}

    public static Path plantCard(String label, String kind, double vigor) {
        Color base = "TREE".equals(kind) ? new Color(0x2f6e3e) : new Color(0xa8842c);
        return card(base, label, kind, String.format("vigor %.2f", vigor));
    }

    public static Path animalCard(String label, String species, String source, Double tempC) {
        Color base = "FUSED".equals(source) ? new Color(0x0a7d5c)
                : "THERMAL".equals(source) ? new Color(0xa25c00) : new Color(0x185fa5);
        String extra = tempC == null ? source : String.format("%s · %.1f °C", source, tempC);
        return card(base, label, species, extra);
    }

    private static Path card(Color base, String title, String line1, String line2) {
        try {
            int w = 480, h = 360;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // background gradient-ish: base color with a darker band
            g.setColor(base);
            g.fillRect(0, 0, w, h);
            g.setColor(base.darker());
            g.fillRect(0, h - 120, w, 120);
            g.setColor(new Color(255, 255, 255, 40));
            for (int i = 0; i < 6; i++) g.fillOval(30 + i * 75, 40 + (i % 3) * 60, 46, 46);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 44));
            g.drawString(title, 28, 70);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 26));
            g.drawString(line1, 28, h - 78);
            g.drawString(line2, 28, h - 44);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
            g.setColor(new Color(255, 255, 255, 170));
            g.drawString("SIMULATED IMAGE — no real photo for this entry", 28, h - 12);
            g.dispose();
            Path out = Files.createTempFile("placeholder-", ".png");
            ImageIO.write(img, "png", out.toFile());
            return out;
        } catch (Exception e) {
            throw new RuntimeException("placeholder image failed", e);
        }
    }
}
