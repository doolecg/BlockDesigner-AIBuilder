package io.blockdesigner.aibuilder.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Small building blocks for the pages, styled with the theme's colours so every theme works. */
final class Ui {
    private Ui() {
    }

    static VBox card() {
        VBox v = new VBox(6);
        v.setPadding(new Insets(10));
        v.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 6; -fx-border-color: -color-border-muted; "
                + "-fx-border-radius: 6;");
        return v;
    }

    static Label title(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        return l;
    }

    static Label heading(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-text-fill: -color-fg-muted; -fx-font-size: 11px; -fx-padding: 6 0 0 0;");
        return l;
    }

    static Label muted(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;");
        return l;
    }

    static Label wrap(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }

    /** A small rounded tag: "Recommended", "Sees pictures", "Installed". */
    static Label badge(String text, String colour) {
        Label l = new Label(text);
        l.setStyle("-fx-background-color: " + colour + "; -fx-background-radius: 8; -fx-padding: 1 7 1 7; -fx-font-size: 10px; "
                + "-fx-text-fill: -color-fg-default;");
        return l;
    }

    static Button button(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("small");
        b.setOnAction(e -> action.run());
        return b;
    }

    static Button accent(String text, Runnable action) {
        Button b = button(text, action);
        b.getStyleClass().add("accent");
        return b;
    }

    static Region grow() {
        Region r = new Region();
        javafx.scene.layout.HBox.setHgrow(r, javafx.scene.layout.Priority.ALWAYS);
        return r;
    }
}
