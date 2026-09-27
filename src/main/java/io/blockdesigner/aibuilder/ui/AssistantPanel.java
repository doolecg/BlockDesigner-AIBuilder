package io.blockdesigner.aibuilder.ui;

import io.blockdesigner.aibuilder.AiBuilder;
import io.blockdesigner.aibuilder.AiSettings;
import io.blockdesigner.aibuilder.agent.Assistant;
import io.blockdesigner.aibuilder.agent.SceneSummary;
import io.blockdesigner.aibuilder.agent.Scope;
import io.blockdesigner.aibuilder.build.BuildRunner;
import io.blockdesigner.aibuilder.build.Styles;
import io.blockdesigner.aibuilder.image.Attachment;
import io.blockdesigner.aibuilder.image.ImageHints;
import io.blockdesigner.aibuilder.llm.Cancel;
import io.blockdesigner.aibuilder.llm.ChatClient;
import io.blockdesigner.aibuilder.models.DownloadJob;
import io.blockdesigner.aibuilder.models.ModelCatalog.ModelEntry;
import io.blockdesigner.aibuilder.models.Sizes;
import io.blockdesigner.plugin.PanelContext;
import io.blockdesigner.plugin.PluginPanel;
import io.blockdesigner.plugin.SceneEvent;
import io.blockdesigner.plugin.Subscription;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Assistant page: a chat. Each request is one turn: the reply streams in, the commands it ran are listed (and
 * can be opened), and "Undo this" takes the turn back. A picture can be attached or dropped on the page.
 */
public final class AssistantPanel implements PluginPanel {
    private final AiBuilder app;
    private final List<Subscription> subscriptions = new ArrayList<>();
    private PanelContext panel;
    private VBox transcript;
    private ScrollPane scroll;
    private TextArea input;
    private Button send, stop, attach;
    private Label scopeLine, status, attachmentChip;
    private VBox banner;
    private ComboBox<Scope.Choice> scopeChoice;
    private Attachment attachment;
    private Cancel running;

    public AssistantPanel(AiBuilder app) {
        this.app = app;
    }

    @Override
    public String id() {
        return "assistant";
    }

    @Override
    public String title() {
        return "Assistant";
    }

    @Override
    public String icon() {
        return "M2 3 H14 V11 H8 L5 14 V11 H2 Z M5 7 H11";
    }

    @Override
    public Node create(PanelContext context) {
        this.panel = context;
        scopeLine = Ui.muted("");
        scopeChoice = new ComboBox<>();
        scopeChoice.getItems().setAll(Scope.Choice.values());
        scopeChoice.setValue(Scope.Choice.AUTO);
        scopeChoice.setCellFactory(l -> choiceCell());
        scopeChoice.setButtonCell(choiceCell());
        scopeChoice.setOnAction(e -> refreshScope());
        ComboBox<String> style = new ComboBox<>();
        style.getItems().add("auto");
        Styles.all().forEach(s -> style.getItems().add(s.name()));
        style.setValue(style.getItems().contains(app.settings.style) ? app.settings.style : "auto");
        style.setOnAction(e -> {
            app.settings.style = style.getValue();
            app.saveSettings();
        });
        Button clear = Ui.button("New chat", this::newChat);
        HBox controls = new HBox(6, new Label("Work on"), scopeChoice, new Label("Style"), style, Ui.grow(), clear);
        controls.setAlignment(Pos.CENTER_LEFT);

        banner = new VBox(6);
        transcript = new VBox(10);
        transcript.setPadding(new Insets(4));
        scroll = new ScrollPane(transcript);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        transcript.heightProperty().addListener((o, a, b) -> scroll.setVvalue(1));

        input = new TextArea();
        input.setPromptText("Ask for a build or a change: \"build a small castle\", \"add a tower on the east side\"…  (Enter sends, Shift+Enter for a new line)");
        input.setWrapText(true);
        input.setPrefRowCount(3);
        input.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();
                send();
            }
        });
        send = Ui.accent("Send", this::send);
        stop = Ui.button("Stop", () -> {
            if (running != null) running.cancel();
        });
        stop.setDisable(true);
        attach = Ui.button("Attach image", this::chooseImage);
        attachmentChip = new Label();
        attachmentChip.setOnMouseClicked(e -> setAttachment(null));
        status = Ui.muted("");
        HBox buttons = new HBox(6, attach, attachmentChip, Ui.grow(), stop, send);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox top = new VBox(6, controls, scopeLine, banner);
        VBox bottom = new VBox(6, status, input, buttons);
        BorderPane root = new BorderPane(scroll, top, null, bottom, null);
        root.setPadding(new Insets(10));
        BorderPane.setMargin(scroll, new Insets(8, 0, 8, 0));

        // Pictures dropped on the page are attached.
        root.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles() && e.getDragboard().getFiles().stream().anyMatch(AssistantPanel::isImage)) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        root.setOnDragDropped(e -> {
            e.getDragboard().getFiles().stream().filter(AssistantPanel::isImage).findFirst().ifPresent(f -> loadImage(f.toPath()));
            e.setDropCompleted(true);
            e.consume();
        });

        subscriptions.add(app.ctx.on(SceneEvent.SelectionChanged.class, e -> refreshScope()));
        subscriptions.add(app.ctx.on(SceneEvent.LayersChanged.class, e -> refreshScope()));
        subscriptions.add(app.ctx.on(SceneEvent.ProjectOpened.class, e -> refreshScope()));
        app.onChange(this::refreshBanner);
        context.onShown(() -> {
            refreshScope();
            refreshBanner();
        });
        refreshScope();
        refreshBanner();
        greet();
        return root;
    }

    private static javafx.scene.control.ListCell<Scope.Choice> choiceCell() {
        return new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Scope.Choice c, boolean empty) {
                super.updateItem(c, empty);
                setText(empty || c == null ? null : c.label);
            }
        };
    }

    private static boolean isImage(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return Attachment.EXTENSIONS.stream().anyMatch(ext -> n.endsWith("." + ext));
    }

    private void refreshScope() {
        if (scopeLine == null) return;
        var layers = app.ctx.scene().layers();
        Scope s = Scope.choose(scopeChoice.getValue(), app.ctx.selection(), SceneSummary.bounds(layers));
        scopeLine.setText(s.describe());
    }

    /** The first-run call to action: download the recommended model. */
    private void refreshBanner() {
        if (banner == null) return;
        banner.getChildren().clear();
        if (app.settings.provider != AiSettings.Provider.BUILT_IN) return;
        ModelEntry e = app.activeModel().orElse(app.catalog.recommended());
        if (app.store.installed(e)) return;
        DownloadJob job = app.jobs.get(e.id());
        VBox card = Ui.card();
        if (job != null && !job.finished()) {
            javafx.scene.control.ProgressBar bar = new javafx.scene.control.ProgressBar(Math.max(0, job.progress().fraction()));
            bar.setMaxWidth(Double.MAX_VALUE);
            card.getChildren().addAll(Ui.title("Downloading " + e.name()), bar, Ui.muted(job.progress().describe()),
                    Ui.button("Cancel", job::cancel));
        } else {
            Button get = Ui.accent("Download " + e.name() + " (" + Sizes.gb(e.totalSize()) + ")", () -> app.download(e));
            Button models = Ui.button("Other models…", () -> app.ctx.toast("Open the Models page at the top of this tab"));
            card.getChildren().addAll(Ui.title("Get the AI model"),
                    Ui.wrap("AI Builder runs a model on your own PC. " + e.name() + " is free (" + e.licence() + ") and can look at pictures. "
                            + "It downloads once, in the background, and resumes if interrupted. Or use your own local server or API key on the Models page."),
                    new HBox(6, get, models));
            if (job != null && job.progress().state() == DownloadJob.State.FAILED) card.getChildren().add(Ui.muted("Last try failed: " + job.progress().message()));
        }
        banner.getChildren().add(card);
    }

    private void greet() {
        transcript.getChildren().add(bubble(Ui.muted("Describe what to build, or what to change. With something selected I work on the selection; "
                + "otherwise on everything visible, or on a new layer when the scene is empty. Each answer is one undo step (Ctrl+Z, or Undo this)."), false));
    }

    private void newChat() {
        if (running != null) return;
        app.assistant.clear();
        transcript.getChildren().clear();
        greet();
    }

    private VBox bubble(Node content, boolean user) {
        VBox b = new VBox(6, content);
        b.setPadding(new Insets(8));
        b.setStyle(user ? "-fx-background-color: -color-accent-subtle; -fx-background-radius: 8;"
                : "-fx-background-color: -color-bg-subtle; -fx-background-radius: 8;");
        b.setMaxWidth(Double.MAX_VALUE);
        return b;
    }

    private void chooseImage() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Attach a reference picture");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Pictures", Attachment.EXTENSIONS.stream().map(e -> "*." + e).toList()));
        File f = fc.showOpenDialog(input.getScene() == null ? null : input.getScene().getWindow());
        if (f != null) loadImage(f.toPath());
    }

    private void loadImage(Path file) {
        app.workers.execute(() -> {
            try {
                Attachment a = Attachment.load(file);
                app.ctx.runOnUiThread(() -> setAttachment(a));
            } catch (IOException e) {
                app.ctx.runOnUiThread(() -> app.ctx.toast(e.getMessage()));
            }
        });
    }

    private void setAttachment(Attachment a) {
        attachment = a;
        attachmentChip.setText(a == null ? "" : "Picture: " + a.describe() + "  ✕");
        attachmentChip.setStyle(a == null ? "" : "-fx-background-color: -color-bg-inset; -fx-background-radius: 8; -fx-padding: 2 8 2 8; -fx-cursor: hand;");
    }

    private void send() {
        if (running != null) return;
        String prompt = input.getText().strip();
        if (prompt.isEmpty() && attachment == null) return;
        Attachment image = attachment;
        input.clear();
        setAttachment(null);

        VBox userBubble = bubble(Ui.wrap(prompt.isEmpty() ? "(picture)" : prompt), true);
        if (image != null) userBubble.getChildren().add(Ui.muted("Picture: " + image.describe()));
        transcript.getChildren().add(userBubble);

        Label reply = Ui.wrap("");
        Label turnStatus = Ui.muted("");
        VBox aiBubble = bubble(new VBox(4, reply, turnStatus), false);
        transcript.getChildren().add(aiBubble);

        Cancel cancel = new Cancel();
        running = cancel;
        setBusy(true);
        Scope.Choice choice = scopeChoice.getValue();
        String style = app.settings.style;
        int maxBlocks = app.settings.maxBlocks;
        Map<String, Integer> colours = image != null ? app.blockColours() : Map.of();
        StringBuilder streamed = new StringBuilder();

        app.workers.execute(() -> {
            try {
                ChatClient client = app.client(m -> ui(() -> status.setText(m)));
                String hints = null;
                if (image != null) {
                    List<ImageHints.Hint> h = ImageHints.dominant(image.pixels(), 6, colours.keySet(), colours::get);
                    hints = ImageHints.describe(h);
                }
                Assistant.Turn t = app.assistant.run(prompt, image, hints, choice, style, maxBlocks, client, new Assistant.Listener() {
                    @Override
                    public void status(String message) {
                        ui(() -> status.setText(message));
                    }

                    @Override
                    public void newReply(int round) {
                        synchronized (streamed) {
                            streamed.setLength(0);
                        }
                    }

                    @Override
                    public void text(String piece) {
                        String now;
                        synchronized (streamed) {
                            streamed.append(piece);
                            now = streamed.toString();
                        }
                        ui(() -> reply.setText(now));
                    }

                    @Override
                    public void tried(int round, BuildRunner.Report report) {
                        String msg = report.ok() ? "All commands worked" : report.errors().size() + " command(s) failed";
                        ui(() -> turnStatus.setText((round == 0 ? "" : "Try " + (round + 1) + ": ") + msg));
                    }
                }, cancel);
                ui(() -> finished(aiBubble, reply, turnStatus, t));
            } catch (Cancel.CancelledException e) {
                ui(() -> turnStatus.setText("Stopped. Nothing was changed."));
            } catch (IOException | RuntimeException e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                app.ctx.log("Assistant: " + msg);
                ui(() -> {
                    turnStatus.setText(msg);
                    turnStatus.setStyle("-fx-text-fill: -color-danger-fg; -fx-font-size: 11px;");
                });
            } finally {
                ui(() -> {
                    running = null;
                    setBusy(false);
                    status.setText("");
                    refreshScope();
                });
            }
        });
    }

    private void finished(VBox bubble, Label reply, Label turnStatus, Assistant.Turn t) {
        reply.setText(t.prose().isBlank() ? (t.commands().isEmpty() ? t.reply() : "Done.") : t.prose());
        if (t.commands().isEmpty()) {
            turnStatus.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (t.report() != null) {
            for (BuildRunner.LineResult r : t.report().lines()) {
                sb.append(r.ok() ? "✓ " : "✗ ").append(r.text());
                if (!r.ok()) sb.append("\n    → ").append(r.message());
                sb.append('\n');
            }
        } else {
            t.commands().forEach(c -> sb.append(c).append('\n'));
        }
        TextArea lines = new TextArea(sb.toString().strip());
        lines.setEditable(false);
        lines.setWrapText(true);
        lines.setPrefRowCount(Math.min(14, (int) sb.chars().filter(ch -> ch == '\n').count() + 1));
        lines.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        TitledPane details = new TitledPane("Commands (" + t.commands().size() + ")", lines);
        details.setExpanded(false);
        details.setAnimated(false);

        String result;
        if (t.applied() == null) {
            result = "Nothing changed.";
        } else {
            result = String.format("%,d block%s changed", t.applied().changed(), t.applied().changed() == 1 ? "" : "s")
                    + (t.scope().newLayer() ? " in a new layer" : "")
                    + (t.rounds() > 1 ? " · " + t.rounds() + " tries" : "")
                    + (t.report() != null && !t.report().ok() ? " · " + t.report().errors().size() + " command(s) still failed and were skipped" : "")
                    + (t.report() != null && t.report().stopped() ? " · stopped at the block limit" : "");
        }
        turnStatus.setText(result);
        bubble.getChildren().add(details);
        if (t.applied() != null) {
            Assistant.Applied applied = t.applied();
            Button undo = Ui.button("Undo this", null);
            undo.setOnAction(e -> {
                undo.setDisable(true);
                app.workers.execute(() -> {
                    try {
                        app.assistant.undo(applied);
                        ui(() -> undo.setText("Undone"));
                    } catch (IOException ex) {
                        ui(() -> {
                            undo.setDisable(false);
                            app.ctx.toast("Couldn't undo: " + ex.getMessage());
                        });
                    }
                });
            });
            bubble.getChildren().add(undo);
        }
    }

    private void setBusy(boolean busy) {
        send.setDisable(busy);
        stop.setDisable(!busy);
        attach.setDisable(busy);
    }

    private void ui(Runnable r) {
        app.ctx.runOnUiThread(r);
    }

    @Override
    public void dispose() {
        if (running != null) running.cancel();
        subscriptions.forEach(Subscription::cancel);
        subscriptions.clear();
    }
}
