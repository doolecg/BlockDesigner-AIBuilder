package io.blockdesigner.aibuilder.ui;

import io.blockdesigner.aibuilder.AiBuilder;
import io.blockdesigner.aibuilder.AiSettings;
import io.blockdesigner.aibuilder.agent.Assistant;
import io.blockdesigner.aibuilder.agent.SceneSummary;
import io.blockdesigner.aibuilder.agent.Scope;
import io.blockdesigner.aibuilder.build.BuildRunner;
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
import io.blockdesigner.plugin.ui.ActionBar;
import io.blockdesigner.plugin.ui.Banner;
import io.blockdesigner.plugin.ui.Controls;
import io.blockdesigner.plugin.ui.Icon;
import io.blockdesigner.plugin.ui.PanelScaffold;
import io.blockdesigner.plugin.ui.Section;
import io.blockdesigner.plugin.ui.Theme;
import io.blockdesigner.plugin.ui.Tone;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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
 * The Assistant page: what to work on at the top, then the chat. Each request is one turn: the reply streams in, the
 * commands it ran can be opened, and "Undo this" takes the turn back. At the bottom: errors, what it is doing, the
 * message box, and Attach · Stop · Send. A picture can be attached or dropped on the page. Until a model is there, the
 * page offers to download the recommended one.
 */
public final class AssistantPanel implements PluginPanel {
    private final AiBuilder app;
    private final List<Subscription> subscriptions = new ArrayList<>();
    private final Runnable listener = this::refreshGetModel;
    private PanelContext panel;
    private VBox transcript;
    private TextArea input;
    private Button send, stop, attach;
    private Label scopeLine, status, attachmentChip;
    private HBox attachmentRow;
    private VBox getModel;
    private Banner banner;
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

        // What to work on.
        scopeChoice = new ComboBox<>();
        scopeChoice.getItems().setAll(Scope.Choice.values());
        scopeChoice.setValue(Scope.Choice.AUTO);
        scopeChoice.setCellFactory(l -> choiceCell());
        scopeChoice.setButtonCell(choiceCell());
        scopeChoice.setOnAction(e -> refreshScope());
        scopeChoice.setMaxWidth(Double.MAX_VALUE);
        scopeChoice.setMinWidth(0);
        scopeChoice.setAccessibleText("Work on");
        HBox.setHgrow(scopeChoice, Priority.ALWAYS);
        Label workOn = new Label("Work on");
        workOn.setMinWidth(Region.USE_PREF_SIZE);
        workOn.setLabelFor(scopeChoice);
        HBox scopeRow = new HBox(Theme.SM, workOn, scopeChoice, Controls.iconButton(Icon.EDIT, "New chat", this::newChat));
        scopeRow.setAlignment(Pos.CENTER_LEFT);
        scopeLine = Controls.caption("");
        scopeLine.setWrapText(true);

        // The chat.
        getModel = new VBox(Theme.SM);
        getModel.getStyleClass().add("bd-card");
        transcript = new VBox(Theme.MD);
        ScrollPane scroll = new ScrollPane(transcript);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().addAll("bd-scroll", "edge-to-edge");
        transcript.heightProperty().addListener((o, a, b) -> scroll.setVvalue(1));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox chat = new VBox(Theme.MD, getModel, scroll);

        // Writing.
        banner = new Banner();
        status = Controls.caption("");
        input = new TextArea();
        input.setPromptText("Ask for a build or a change: \"build a small castle\", \"add a tower on the east side\"…  (Enter sends, Shift+Enter for a new line)");
        input.setWrapText(true);
        input.setPrefRowCount(3);
        input.setAccessibleText("Message");
        input.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();
                send();
            }
        });
        attachmentChip = Controls.caption("");
        attachmentRow = new HBox(Theme.XS, Icon.PAPERCLIP.node(14), attachmentChip,
                Controls.iconButton(Icon.CLOSE, "Remove the picture", () -> setAttachment(null)));
        attachmentRow.setAlignment(Pos.CENTER_LEFT);
        ((Button) attachmentRow.getChildren().getLast()).getStyleClass().add("small");
        Controls.show(attachmentRow, false);
        send = Controls.primary("Send", this::send);
        stop = Controls.button("Stop", "Stop this answer (nothing is changed)", () -> {
            if (running != null) running.cancel();
        });
        stop.setDisable(true);
        attach = Controls.iconButton(Icon.PAPERCLIP, "Attach a picture…", this::chooseImage);

        PanelScaffold page = new PanelScaffold()
                .top(scopeRow, scopeLine)
                .grow(chat)
                .footer(banner, status, attachmentRow, input, new ActionBar(attach, Controls.spacer(), stop, send));
        // The label goes when the page is narrow; the choice says it well enough.
        page.narrowProperty().addListener((o, a, narrow) -> Controls.show(workOn, !narrow));

        // Pictures dropped on the page are attached.
        page.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles() && e.getDragboard().getFiles().stream().anyMatch(AssistantPanel::isImage)) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        page.setOnDragDropped(e -> {
            e.getDragboard().getFiles().stream().filter(AssistantPanel::isImage).findFirst().ifPresent(f -> loadImage(f.toPath()));
            e.setDropCompleted(true);
            e.consume();
        });

        subscriptions.add(app.ctx.on(SceneEvent.SelectionChanged.class, e -> refreshScope()));
        subscriptions.add(app.ctx.on(SceneEvent.LayersChanged.class, e -> refreshScope()));
        subscriptions.add(app.ctx.on(SceneEvent.ProjectOpened.class, e -> refreshScope()));
        app.onChange(listener);
        context.onShown(() -> {
            refreshScope();
            refreshGetModel();
        });
        refreshScope();
        refreshGetModel();
        greet();
        return page;
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

    /** The first-run call to action: download the recommended model (or its progress while downloading). */
    private void refreshGetModel() {
        if (getModel == null) return;
        getModel.getChildren().clear();
        ModelEntry e = app.activeModel().orElse(app.catalog.recommended());
        boolean need = app.settings.provider == AiSettings.Provider.BUILT_IN && !app.store.installed(e);
        Controls.show(getModel, need);
        if (!need) return;
        DownloadJob job = app.jobs.get(e.id());
        Label title = new Label(job != null && !job.finished() ? "Downloading " + e.name() : "Get the AI model");
        title.getStyleClass().add("bd-row-title");
        if (job != null && !job.finished()) {
            ProgressBar bar = new ProgressBar(Math.max(0, job.progress().fraction()));
            bar.getStyleClass().add("bd-progress");
            bar.setMaxWidth(Double.MAX_VALUE);
            getModel.getChildren().addAll(title, bar, Controls.caption(job.progress().describe()),
                    Controls.button("Cancel", "Stop the download (it resumes later)", job::cancel));
        } else {
            Button get = Controls.primary("Download " + e.name() + " (" + Sizes.gb(e.totalSize()) + ")", () -> app.download(e));
            Button models = Controls.button("Other models…", "The Models page: other built-in models, your own server or an API key",
                    () -> app.ctx.showPanel("models"));
            get.setMinWidth(Region.USE_PREF_SIZE);
            models.setMinWidth(Region.USE_PREF_SIZE);
            // Side by side, or one under the other on a narrow page.
            javafx.scene.layout.FlowPane buttons = new javafx.scene.layout.FlowPane(Theme.SM, Theme.SM, get, models);
            getModel.getChildren().addAll(title,
                    Controls.hint("AI Builder runs a model on your own PC. " + e.name() + " is free (" + e.licence() + ") and can look at "
                            + "pictures. It downloads once, in the background, and resumes if interrupted."), buttons);
            if (job != null && job.progress().state() == DownloadJob.State.FAILED) {
                Label failed = Controls.hint("Last try failed: " + job.progress().message());
                Tone.apply(failed, Tone.DANGER);
                getModel.getChildren().add(failed);
            }
        }
    }

    private void greet() {
        transcript.getChildren().add(bubble(Controls.hint("Describe what to build, or what to change. With something selected I work on the "
                + "selection; otherwise on everything visible, or on a new layer when the scene is empty. Each answer is one undo step "
                + "(Ctrl+Z, or Undo this)."), false));
    }

    private void newChat() {
        if (running != null) return;
        app.assistant.clear();
        transcript.getChildren().clear();
        banner.hide();
        greet();
    }

    private VBox bubble(Node content, boolean user) {
        VBox b = new VBox(Theme.SM, content);
        b.getStyleClass().add("bd-card");
        if (user) Tone.apply(b, Tone.ACCENT);
        b.setMaxWidth(Double.MAX_VALUE);
        return b;
    }

    private static Label text(String s) {
        Label l = new Label(s);
        l.setWrapText(true);
        l.setMinHeight(Region.USE_PREF_SIZE);
        return l;
    }

    private void chooseImage() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Attach a reference picture");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Pictures", Attachment.EXTENSIONS.stream().map(e -> "*." + e).toList()));
        File f = fc.showOpenDialog(app.ctx.ui().owner());
        if (f != null) loadImage(f.toPath());
    }

    private void loadImage(Path file) {
        app.workers.execute(() -> {
            try {
                Attachment a = Attachment.load(file);
                app.ctx.runOnUiThread(() -> setAttachment(a));
            } catch (IOException e) {
                app.ctx.runOnUiThread(() -> banner.show(Tone.DANGER, e.getMessage()));
            }
        });
    }

    private void setAttachment(Attachment a) {
        attachment = a;
        attachmentChip.setText(a == null ? "" : "Picture: " + a.describe());
        Controls.show(attachmentRow, a != null);
    }

    /** The dot on the Assistant page's button: working (with what), failed, or none. */
    private void pageStatus(Tone tone, String text) {
        app.ctx.setPanelStatus(id(), tone, text);
    }

    private void send() {
        if (running != null) return;
        String prompt = input.getText().strip();
        if (prompt.isEmpty() && attachment == null) return;
        Attachment image = attachment;
        input.clear();
        setAttachment(null);
        banner.hide();

        VBox userBubble = bubble(text(prompt.isEmpty() ? "(picture)" : prompt), true);
        if (image != null) userBubble.getChildren().add(Controls.caption("Picture: " + image.describe()));
        transcript.getChildren().add(userBubble);

        Label reply = text("");
        Label turnStatus = Controls.caption("");
        turnStatus.setWrapText(true);
        VBox aiBubble = bubble(new VBox(Theme.XS, reply, turnStatus), false);
        transcript.getChildren().add(aiBubble);

        Cancel cancel = new Cancel();
        running = cancel;
        setBusy(true);
        pageStatus(Tone.ACCENT, "Answering…");
        Scope.Choice choice = scopeChoice.getValue();
        String style = app.settings.style;
        int maxBlocks = app.settings.maxBlocks;
        Map<String, Integer> colours = image != null ? app.blockColours() : Map.of();
        StringBuilder streamed = new StringBuilder();

        app.workers.execute(() -> {
            try {
                ChatClient client = app.client(m -> ui(() -> working(m)));
                String hints = null;
                if (image != null) {
                    List<ImageHints.Hint> h = ImageHints.dominant(image.pixels(), 6, colours.keySet(), colours::get);
                    hints = ImageHints.describe(h);
                }
                Assistant.Turn t = app.assistant.run(prompt, image, hints, choice, style, maxBlocks, client, new Assistant.Listener() {
                    @Override
                    public void status(String message) {
                        ui(() -> working(message));
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
                ui(() -> {
                    finished(aiBubble, reply, turnStatus, t);
                    pageStatus(null, null);
                });
            } catch (Cancel.CancelledException e) {
                ui(() -> {
                    turnStatus.setText("Stopped. Nothing was changed.");
                    pageStatus(null, null);
                });
            } catch (IOException | RuntimeException e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                app.ctx.log("Assistant: " + msg);
                ui(() -> {
                    turnStatus.setText("No answer.");
                    banner.show(Tone.DANGER, msg, "Models…", () -> app.ctx.showPanel("models"));
                    pageStatus(Tone.DANGER, msg);
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

    /** What the answer is waiting for ("Starting the model…"), under the chat and on the page's dot. */
    private void working(String message) {
        status.setText(message);
        if (running != null) pageStatus(Tone.ACCENT, message == null || message.isBlank() ? "Answering…" : message);
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
        lines.getStyleClass().add("bd-mono");
        Section details = new Section("Commands (" + t.commands().size() + ")", lines).collapsible(false);

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
            Button undo = Controls.button("Undo this", "Take this answer's changes back", null);
            undo.getStyleClass().add("small");
            undo.setOnAction(e -> {
                undo.setDisable(true);
                app.workers.execute(() -> {
                    try {
                        app.assistant.undo(applied);
                        ui(() -> undo.setText("Undone"));
                    } catch (IOException ex) {
                        ui(() -> {
                            undo.setDisable(false);
                            banner.show(Tone.DANGER, "Couldn't undo: " + ex.getMessage());
                        });
                    }
                });
            });
            bubble.getChildren().add(undo);
        }
    }

    private void setBusy(boolean busy) {
        Controls.busy(send, busy);
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
        app.removeListener(listener);
    }
}
