package io.blockdesigner.aibuilder.ui;

import io.blockdesigner.aibuilder.AiBuilder;
import io.blockdesigner.aibuilder.AiSettings;
import io.blockdesigner.aibuilder.models.DownloadJob;
import io.blockdesigner.aibuilder.models.Engine;
import io.blockdesigner.aibuilder.models.LocalServer;
import io.blockdesigner.aibuilder.models.ModelCatalog;
import io.blockdesigner.aibuilder.models.ModelCatalog.ModelEntry;
import io.blockdesigner.aibuilder.models.Sizes;
import io.blockdesigner.plugin.PanelContext;
import io.blockdesigner.plugin.PluginPanel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Models page: which AI answers, the built-in models with download progress, the engine, the background model's
 * state, and (under Advanced) a custom model, a local server and API keys.
 */
public final class ModelsPanel implements PluginPanel {
    private final AiBuilder app;
    private final List<Runnable> updaters = new ArrayList<>();
    private VBox modelList;
    private Label diskUse;

    public ModelsPanel(AiBuilder app) {
        this.app = app;
    }

    @Override
    public String id() {
        return "models";
    }

    @Override
    public String title() {
        return "Models";
    }

    @Override
    public String icon() {
        return "M3 4 H13 V12 H3 Z M5 6 V10 M8 6 V10 M11 6 V10 M6 14 H10";
    }

    @Override
    public Node create(PanelContext context) {
        VBox root = new VBox(10);
        root.setPadding(new Insets(10));

        // Where answers come from.
        ComboBox<AiSettings.Provider> provider = new ComboBox<>();
        provider.getItems().setAll(AiSettings.Provider.values());
        provider.setValue(app.settings.provider);
        provider.setButtonCell(providerCell());
        provider.setCellFactory(l -> providerCell());
        provider.setMaxWidth(Double.MAX_VALUE);
        provider.setOnAction(e -> {
            app.settings.provider = provider.getValue();
            app.saveSettings();
        });
        root.getChildren().addAll(Ui.heading("ANSWERS COME FROM"), provider);

        diskUse = Ui.muted("");
        Button open = Ui.button("Open folder", () -> openFolder(app.store.root()));
        HBox modelsHead = new HBox(8, Ui.heading("BUILT-IN MODELS"), Ui.grow(), open);
        modelsHead.setAlignment(Pos.CENTER_LEFT);
        modelList = new VBox(8);
        rebuildModels();
        root.getChildren().addAll(modelsHead, diskUse, modelList);

        root.getChildren().addAll(Ui.heading("ENGINE"), engineCard(), Ui.heading("BACKGROUND MODEL"), serverCard());

        TitledPane advanced = new TitledPane("Advanced", advanced());
        advanced.setExpanded(false);
        root.getChildren().add(advanced);

        app.onChange(this::update);
        context.onShown(this::update);
        update();
        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private static javafx.scene.control.ListCell<AiSettings.Provider> providerCell() {
        return new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(AiSettings.Provider p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? null : p.label);
            }
        };
    }

    private void update() {
        updaters.forEach(Runnable::run);
        diskUse.setText("On disk: " + Sizes.bytes(app.store.diskUse()) + " in " + app.store.root());
    }

    private void rebuildModels() {
        modelList.getChildren().clear();
        updaters.removeIf(r -> r instanceof ModelCard);
        for (ModelEntry e : app.catalog.all()) {
            ModelCard card = new ModelCard(e);
            updaters.add(card);
            modelList.getChildren().add(card.node);
        }
    }

    /** One model: name and badges, what it's good for, size and RAM, status and the buttons. */
    private final class ModelCard implements Runnable {
        final ModelEntry entry;
        final VBox node = Ui.card();
        final Label status = new Label();
        final ProgressBar bar = new ProgressBar(0);
        final Button download, cancel, use, delete;

        ModelCard(ModelEntry e) {
            this.entry = e;
            HBox head = new HBox(6, Ui.title(e.name()));
            head.setAlignment(Pos.CENTER_LEFT);
            if (!e.tag().isBlank()) head.getChildren().add(Ui.badge(e.tag(), e.recommended() ? "-color-accent-subtle" : "-color-bg-inset"));
            if (e.vision()) head.getChildren().add(Ui.badge("Sees pictures", "-color-bg-inset"));
            String size = e.totalSize() > 0 ? Sizes.gb(e.totalSize()) : "size shown when downloading";
            Label facts = Ui.muted(size + (e.ram().isBlank() ? "" : " · " + e.ram()) + (e.licence().isBlank() ? "" : " · " + e.licence()));
            bar.setMaxWidth(Double.MAX_VALUE);
            download = Ui.accent("Download", () -> app.download(entry));
            cancel = Ui.button("Cancel", () -> Optional.ofNullable(app.jobs.get(entry.id())).ifPresent(DownloadJob::cancel));
            use = Ui.button("Use", () -> {
                app.settings.activeModel = entry.id();
                app.settings.provider = AiSettings.Provider.BUILT_IN;
                app.saveSettings();
            });
            delete = Ui.button("Delete", this::delete);
            HBox buttons = new HBox(6, status, Ui.grow(), download, cancel, use, delete);
            buttons.setAlignment(Pos.CENTER_LEFT);
            node.getChildren().addAll(head, Ui.muted(e.blurb()), facts, bar, buttons);
        }

        private void delete() {
            String what = entry.custom() ? "Delete " + entry.name() + "'s files and remove it from the list?" : "Delete the downloaded files of " + entry.name() + "?";
            Alert a = new Alert(Alert.AlertType.CONFIRMATION, what, ButtonType.OK, ButtonType.CANCEL);
            a.setHeaderText(null);
            if (a.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            Optional.ofNullable(app.jobs.remove(entry.id())).ifPresent(DownloadJob::cancel);
            try {
                if (app.settings.activeModel.equals(entry.id())) app.server.stop();
                app.store.delete(entry);
                if (entry.custom()) {
                    app.catalog.removeCustom(entry.id());
                    rebuildModels();
                }
            } catch (IOException ex) {
                app.ctx.toast("Couldn't delete it: " + ex.getMessage());
            }
            app.changed();
        }

        @Override
        public void run() {
            DownloadJob job = app.jobs.get(entry.id());
            boolean downloading = job != null && !job.finished();
            boolean installed = app.store.installed(entry);
            boolean active = app.settings.provider == AiSettings.Provider.BUILT_IN && app.settings.activeModel.equals(entry.id());
            String text;
            if (downloading) {
                text = "Downloading " + job.progress().describe();
                bar.setProgress(job.progress().fraction() >= 0 ? job.progress().fraction() : ProgressBar.INDETERMINATE_PROGRESS);
            } else if (installed) {
                text = active ? "Active" : "Installed";
            } else if (job != null && job.progress().state() == DownloadJob.State.FAILED) {
                text = "Failed: " + job.progress().message();
            } else {
                long have = app.store.downloadedBytes(entry);
                text = have > 0 ? "Paused at " + Sizes.bytes(have) + " (Download resumes)" : "Not installed";
            }
            status.setText(text);
            status.setWrapText(true);
            status.setStyle(active && installed ? "-fx-text-fill: -color-success-fg; -fx-font-weight: bold;"
                    : text.startsWith("Failed") ? "-fx-text-fill: -color-danger-fg;" : "");
            bar.setVisible(downloading);
            bar.setManaged(downloading);
            show(download, !downloading && !installed);
            show(cancel, downloading);
            show(use, installed && !active);
            show(delete, !downloading && (installed || app.store.downloadedBytes(entry) > 0 || entry.custom()));
        }
    }

    private static void show(Node n, boolean visible) {
        n.setVisible(visible);
        n.setManaged(visible);
    }

    private Node engineCard() {
        VBox card = Ui.card();
        ComboBox<Engine.Variant> variant = new ComboBox<>();
        variant.getItems().setAll(Engine.Variant.values());
        variant.setValue(app.variant());
        variant.setMaxWidth(Double.MAX_VALUE);
        variant.setCellFactory(l -> variantCell());
        variant.setButtonCell(variantCell());
        variant.setOnAction(e -> {
            app.settings.engineVariant = variant.getValue().id;
            app.saveSettings();
        });
        Label version = new Label();
        ProgressBar bar = new ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);
        Button install = Ui.button("Install", app::installEngine);
        Button update = Ui.button("Check for update", () -> checkEngineUpdate(version));
        HBox row = new HBox(6, version, Ui.grow(), install, update);
        row.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().addAll(Ui.muted("llama.cpp runs the built-in models. It is downloaded the first time a model starts (about 20–260 MB, "
                + "depending on the kind). If the graphics card build won't start, the CPU build is used."), variant, bar, row);
        updaters.add(() -> {
            DownloadJob job = app.jobs.get("engine");
            boolean busy = job != null && !job.finished();
            Optional<Engine.Installed> inst = app.engine.installed(app.variant());
            version.setText(busy ? "Installing: " + job.progress().describe()
                    : inst.map(i -> "Installed: " + i.tag()).orElse("Not installed yet"));
            bar.setProgress(busy && job.progress().fraction() >= 0 ? job.progress().fraction() : 0);
            show(bar, busy);
            install.setText(inst.isPresent() ? "Reinstall" : "Install");
            install.setDisable(busy);
            update.setDisable(busy || inst.isEmpty());
        });
        return card;
    }

    private static javafx.scene.control.ListCell<Engine.Variant> variantCell() {
        return new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Engine.Variant v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? null : v.label);
            }
        };
    }

    private void checkEngineUpdate(Label version) {
        Engine.Variant v = app.variant();
        version.setText("Checking…");
        app.workers.execute(() -> {
            String msg;
            try {
                Engine.Pick latest = app.engine.findLatest(v);
                String have = app.engine.installed(v).map(Engine.Installed::tag).orElse("none");
                msg = latest.tag().equals(have) ? "Up to date (" + have + ")" : "Newer: " + latest.tag() + " (you have " + have + "); Reinstall to update";
            } catch (IOException e) {
                msg = "Couldn't check: " + e.getMessage();
            }
            String m = msg;
            app.ctx.runOnUiThread(() -> app.ctx.toast(m));
        });
    }

    private Node serverCard() {
        VBox card = Ui.card();
        Label status = new Label();
        status.setWrapText(true);
        Button start = Ui.button("Start", () -> app.workers.execute(() -> {
            try {
                app.startLocal(m -> app.ctx.runOnUiThread(() -> status.setText(m)));
            } catch (IOException e) {
                app.ctx.runOnUiThread(() -> app.ctx.toast(e.getMessage()));
            }
            app.changed();
        }));
        Button stop = Ui.button("Stop", () -> {
            app.server.stop();
            app.changed();
        });
        CheckBox atLaunch = new CheckBox("Start it when BlockDesigner starts");
        atLaunch.setSelected(app.settings.startAtLaunch);
        atLaunch.setOnAction(e -> {
            app.settings.startAtLaunch = atLaunch.isSelected();
            app.saveSettings();
        });
        ComboBox<String> idle = new ComboBox<>();
        idle.getItems().setAll("Never", "10 minutes", "30 minutes", "60 minutes");
        idle.setValue(app.settings.idleMinutes == 0 ? "Never" : app.settings.idleMinutes + " minutes");
        if (!idle.getItems().contains(idle.getValue())) idle.getItems().add(idle.getValue());
        idle.setOnAction(e -> {
            String v = idle.getValue();
            app.settings.idleMinutes = v.equals("Never") ? 0 : Integer.parseInt(v.replaceAll("\\D", ""));
            app.saveSettings();
        });
        HBox idleRow = new HBox(6, new Label("Stop it when unused for"), idle);
        idleRow.setAlignment(Pos.CENTER_LEFT);
        HBox row = new HBox(6, status, Ui.grow(), start, stop);
        row.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().addAll(row, atLaunch, idleRow,
                Ui.muted("The model runs in the background only while needed, and stops when the plugin is turned off or BlockDesigner closes."));
        updaters.add(() -> {
            LocalServer.Status s = app.server.status();
            String name = app.activeModel().map(ModelEntry::name).orElse("No model");
            status.setText(switch (s) {
                case STOPPED -> name + ": stopped";
                case STARTING -> name + ": starting…";
                case READY -> name + ": ready";
                case FAILED -> name + ": failed. " + Optional.ofNullable(app.server.problem()).orElse("");
            });
            status.setStyle(s == LocalServer.Status.READY ? "-fx-text-fill: -color-success-fg;" : s == LocalServer.Status.FAILED ? "-fx-text-fill: -color-danger-fg;" : "");
            boolean installed = app.activeModel().map(app.store::installed).orElse(false);
            start.setDisable(s == LocalServer.Status.STARTING || s == LocalServer.Status.READY || !installed);
            stop.setDisable(s != LocalServer.Status.READY && s != LocalServer.Status.STARTING);
        });
        return card;
    }

    private Node advanced() {
        VBox box = new VBox(8);

        // Custom model.
        TextField name = new TextField();
        name.setPromptText("Name (optional)");
        TextField model = new TextField();
        model.setPromptText("owner/repo/file.gguf or a link");
        TextField mmproj = new TextField();
        mmproj.setPromptText("Vision projector (mmproj) file, optional");
        Button add = Ui.button("Add to the list", () -> {
            try {
                ModelEntry e = ModelCatalog.customEntry(name.getText(), model.getText(), mmproj.getText());
                app.catalog.addCustom(e);
                rebuildModels();
                name.clear();
                model.clear();
                mmproj.clear();
                app.changed();
            } catch (IllegalArgumentException | IOException ex) {
                app.ctx.toast(ex.getMessage());
            }
        });
        VBox custom = Ui.card();
        custom.getChildren().addAll(Ui.title("Custom model (GGUF from Hugging Face)"), name, model, mmproj, add,
                Ui.muted("It appears with the built-in models, to download and use the same way."));

        // Local server.
        TextField url = new TextField(app.settings.localServerUrl);
        TextField serverModel = new TextField(app.settings.localServerModel);
        serverModel.setPromptText("Model name, e.g. gemma3:12b");
        CheckBox sees = new CheckBox("The model sees pictures");
        sees.setSelected(app.settings.localServerVision);
        Button saveServer = Ui.button("Save", () -> {
            app.settings.localServerUrl = url.getText().strip();
            app.settings.localServerModel = serverModel.getText().strip();
            app.settings.localServerVision = sees.isSelected();
            app.saveSettings();
            app.ctx.toast("Local server saved");
        });
        VBox server = Ui.card();
        server.getChildren().addAll(Ui.title("My local server"), Ui.muted("Any OpenAI-compatible address: Ollama is http://localhost:11434/v1, "
                + "LM Studio http://localhost:1234/v1."), url, serverModel, sees, saveServer);

        // API keys.
        VBox claude = keyCard("Claude", "anthropic", "sk-ant-…", app.settings.claudeModel, v -> app.settings.claudeModel = v, null, null);
        VBox openai = keyCard("OpenAI or compatible", "openai", "sk-…", app.settings.openaiModel, v -> app.settings.openaiModel = v,
                app.settings.openaiUrl, v -> app.settings.openaiUrl = v);
        box.getChildren().addAll(custom, server, Ui.heading("API KEYS"),
                Ui.muted("Keys are encrypted for your Windows account (DPAPI) and kept in the plugin's folder. Using them sends the scene "
                        + "summary and your messages to that company."), claude, openai);
        return box;
    }

    private VBox keyCard(String title, String keyName, String prompt, String modelValue, java.util.function.Consumer<String> setModel,
                         String urlValue, java.util.function.Consumer<String> setUrl) {
        VBox card = Ui.card();
        PasswordField key = new PasswordField();
        key.setPromptText(app.secrets.has(keyName) ? "Saved (type to replace)" : prompt);
        TextField model = new TextField(modelValue);
        model.setPromptText("Model");
        TextField url = urlValue == null ? null : new TextField(urlValue);
        Label state = Ui.muted(app.secrets.has(keyName) ? "Key saved" : "No key yet");
        Button save = Ui.button("Save", () -> {
            String typed = key.getText();
            setModel.accept(model.getText().strip());
            if (url != null) setUrl.accept(url.getText().strip());
            app.saveSettings();
            if (typed.isBlank()) {
                state.setText(app.secrets.has(keyName) ? "Key saved" : "No key yet");
                return;
            }
            state.setText("Saving…");
            app.workers.execute(() -> {
                String msg;
                try {
                    app.secrets.save(keyName, typed);
                    msg = "Key saved (encrypted)";
                } catch (IOException e) {
                    msg = "Not saved: " + e.getMessage();
                }
                String m = msg;
                app.ctx.runOnUiThread(() -> {
                    state.setText(m);
                    key.clear();
                    key.setPromptText(app.secrets.has(keyName) ? "Saved (type to replace)" : prompt);
                });
            });
        });
        Button clear = Ui.button("Remove key", () -> {
            try {
                app.secrets.delete(keyName);
                state.setText("No key yet");
                key.setPromptText(prompt);
            } catch (IOException e) {
                state.setText("Couldn't remove it: " + e.getMessage());
            }
        });
        HBox buttons = new HBox(6, save, clear, state);
        buttons.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(Ui.title(title));
        if (url != null) card.getChildren().addAll(Ui.muted("Address"), url);
        card.getChildren().addAll(Ui.muted("API key"), key, Ui.muted("Model"), model, buttons);
        return card;
    }

    private void openFolder(java.nio.file.Path dir) {
        try {
            java.nio.file.Files.createDirectories(dir);
            new ProcessBuilder("explorer.exe", dir.toAbsolutePath().toString()).start();
        } catch (IOException e) {
            app.ctx.toast("Couldn't open the folder: " + e.getMessage());
        }
    }
}
