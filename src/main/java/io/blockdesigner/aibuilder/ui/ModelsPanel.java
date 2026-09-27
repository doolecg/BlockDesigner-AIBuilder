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
import io.blockdesigner.plugin.ui.Controls;
import io.blockdesigner.plugin.ui.Form;
import io.blockdesigner.plugin.ui.Icon;
import io.blockdesigner.plugin.ui.PanelScaffold;
import io.blockdesigner.plugin.ui.Section;
import io.blockdesigner.plugin.ui.StatusBadge;
import io.blockdesigner.plugin.ui.Theme;
import io.blockdesigner.plugin.ui.Tone;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Models page: which AI answers; with the built-in one, its models (download, use, delete), the engine and the
 * background model; with a local server, where it is; with Claude or OpenAI, the API key (kept encrypted); a custom
 * model; and at the bottom, how the chosen one stands. Connection details, the engine kind and when the model runs are
 * in BlockDesigner's Settings window.
 */
public final class ModelsPanel implements PluginPanel {
    private final AiBuilder app;
    private final List<Runnable> updaters = new ArrayList<>();
    private final Runnable listener = this::update;
    private VBox modelList;
    private Label diskUse;
    private ComboBox<AiSettings.Provider> provider;
    private Section models, engine, background, localServer, claudeKey, openaiKey;
    private StatusBadge state;
    private Label stateDetail;
    private boolean updating;

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
        // Where answers come from: the same value as in the Settings window.
        provider = new ComboBox<>();
        provider.getItems().setAll(AiSettings.Provider.values());
        provider.setButtonCell(providerCell());
        provider.setCellFactory(l -> providerCell());
        provider.setOnAction(e -> {
            if (updating || provider.getValue() == null || provider.getValue() == app.settings.provider) return;
            String label = provider.getValue().label;
            app.updateSettings(v -> v.with("provider", label));
        });
        Form answersForm = new Form();
        answersForm.row("Answers from", provider);
        Section answers = new Section("Answers come from", answersForm,
                Controls.link("Connection settings…", app.ctx::openSettings));

        // Built-in models.
        diskUse = Controls.pathCaption("");
        modelList = new VBox(Theme.SM);
        rebuildModels();
        models = new Section("Built-in models", diskUse, modelList)
                .actions(Controls.iconButton(Icon.FOLDER, "Open the models folder", this::openModelsFolder));
        engine = engineSection();
        background = backgroundSection();
        localServer = localServerSection();
        claudeKey = keySection("Claude API key", "anthropic", "sk-ant-…");
        openaiKey = keySection("OpenAI API key", "openai", "sk-…");

        // How the chosen one stands, at the bottom.
        state = new StatusBadge(Tone.NEUTRAL, "");
        stateDetail = Controls.caption("");
        stateDetail.setWrapText(true);

        PanelScaffold page = new PanelScaffold()
                .add(answers, models, engine, background, localServer, claudeKey, openaiKey, customSection())
                .footer(new VBox(Theme.XS, state, stateDetail));
        app.onChange(listener);
        context.onShown(this::update);
        update();
        return page;
    }

    @Override
    public void dispose() {
        app.removeListener(listener);
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
        AiSettings.Provider p = app.settings.provider;
        updating = true;
        try {
            provider.setValue(p);
        } finally {
            updating = false;
        }
        boolean builtIn = p == AiSettings.Provider.BUILT_IN;
        Controls.show(models, builtIn);
        Controls.show(engine, builtIn);
        Controls.show(background, builtIn);
        Controls.show(localServer, p == AiSettings.Provider.LOCAL_SERVER);
        Controls.show(claudeKey, p == AiSettings.Provider.CLAUDE);
        Controls.show(openaiKey, p == AiSettings.Provider.OPENAI);
        updaters.forEach(Runnable::run);
        diskUse.setText("On disk: " + Sizes.bytes(app.store.diskUse()) + " in " + app.store.root());
        showState();
    }

    /** The footer: whether the chosen source can answer. */
    private void showState() {
        switch (app.settings.provider) {
            case BUILT_IN -> {
                LocalServer.Status s = app.server.status();
                String name = app.activeModel().map(ModelEntry::name).orElse("No model picked");
                switch (s) {
                    case READY -> state.set(Tone.SUCCESS, "Model running");
                    case STARTING -> state.set(Tone.WARNING, "Starting the model…");
                    case FAILED -> state.set(Tone.DANGER, "The model failed");
                    default -> state.set(Tone.NEUTRAL, "Stopped");
                }
                boolean installed = app.activeModel().map(app.store::installed).orElse(false);
                stateDetail.setText(s == LocalServer.Status.FAILED ? Optional.ofNullable(app.server.problem()).orElse(name)
                        : installed ? name + " on this PC. It starts with the first question." : name + " is not downloaded yet.");
            }
            case LOCAL_SERVER -> {
                state.set(Tone.NEUTRAL, "Local server");
                stateDetail.setText(app.settings.localServerUrl + (app.settings.localServerModel.isBlank() ? "" : " · " + app.settings.localServerModel));
            }
            case CLAUDE, OPENAI -> {
                String key = app.settings.provider == AiSettings.Provider.CLAUDE ? "anthropic" : "openai";
                boolean has = app.secrets.has(key);
                state.set(has ? Tone.SUCCESS : Tone.WARNING, has ? "Key saved" : "No key");
                stateDetail.setText(has ? "Your messages and a scene summary go to " + (key.equals("anthropic") ? "Anthropic." : "that service.")
                        : "Add your API key above to use it.");
            }
        }
    }

    // ---- built-in models ---------------------------------------------------------------------------------------

    private void rebuildModels() {
        modelList.getChildren().clear();
        updaters.removeIf(r -> r instanceof ModelCard);
        for (ModelEntry e : app.catalog.all()) {
            ModelCard card = new ModelCard(e);
            updaters.add(card);
            modelList.getChildren().add(card.node);
        }
    }

    private void openModelsFolder() {
        try {
            java.nio.file.Files.createDirectories(app.store.root());
            app.ctx.ui().open(app.store.root());
        } catch (IOException e) {
            app.ctx.toast("Couldn't open the folder: " + e.getMessage());
        }
    }

    /** One model: name and badges, what it's good for, size and memory, then its status and at most three buttons. */
    private final class ModelCard implements Runnable {
        final ModelEntry entry;
        final VBox node = new VBox(Theme.XS);
        final Label status = Controls.caption("");
        final ProgressBar bar = new ProgressBar(0);
        final Button download, cancel, use, delete;

        ModelCard(ModelEntry e) {
            this.entry = e;
            node.getStyleClass().add("bd-card");
            Label name = new Label(e.name());
            name.getStyleClass().add("bd-row-title");
            FlowPane head = new FlowPane(Theme.SM, Theme.XS, name);
            head.setAlignment(Pos.CENTER_LEFT);
            if (!e.tag().isBlank()) head.getChildren().add(new StatusBadge(e.recommended() ? Tone.ACCENT : Tone.NEUTRAL, e.tag()));
            if (e.vision()) head.getChildren().add(new StatusBadge(Tone.NEUTRAL, "Sees pictures"));
            String size = e.totalSize() > 0 ? Sizes.gb(e.totalSize()) : "size shown when downloading";
            Label facts = Controls.caption(size + (e.ram().isBlank() ? "" : " · " + e.ram()) + (e.licence().isBlank() ? "" : " · " + e.licence()));
            facts.setWrapText(true);
            bar.getStyleClass().add("bd-progress");
            bar.setMaxWidth(Double.MAX_VALUE);
            download = Controls.primary("Download", () -> app.download(entry));
            cancel = Controls.button("Cancel", "Stop the download (it resumes later)",
                    () -> Optional.ofNullable(app.jobs.get(entry.id())).ifPresent(DownloadJob::cancel));
            use = Controls.button("Use", "Answer with this model", () -> {
                app.settings.activeModel = entry.id();
                app.saveSettings();
                app.updateSettings(v -> v.with("provider", AiSettings.Provider.BUILT_IN.label));
            });
            delete = Controls.button("Delete…", "Delete its downloaded files", this::delete);
            for (Button b : List.of(download, cancel, use, delete)) b.getStyleClass().add("small");
            status.setWrapText(true);
            HBox.setHgrow(status, javafx.scene.layout.Priority.ALWAYS);
            HBox buttons = new HBox(Theme.XS, status, cancel, use, delete, download);
            buttons.setAlignment(Pos.CENTER_LEFT);
            node.getChildren().addAll(head, Controls.hint(e.blurb()), facts, bar, buttons);
        }

        private void delete() {
            String what = entry.custom() ? "Delete " + entry.name() + "'s files and remove it from the list?"
                    : "Delete the downloaded files of " + entry.name() + "? You can download it again later.";
            if (!app.ctx.ui().confirm("Delete model", what, "Delete", true)) return;
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
            Tone tone = Tone.NEUTRAL;
            if (downloading) {
                text = "Downloading " + job.progress().describe();
                bar.setProgress(job.progress().fraction() >= 0 ? job.progress().fraction() : ProgressBar.INDETERMINATE_PROGRESS);
            } else if (installed) {
                text = active ? "In use" : "Downloaded";
                if (active) tone = Tone.SUCCESS;
            } else if (job != null && job.progress().state() == DownloadJob.State.FAILED) {
                text = "Failed: " + job.progress().message();
                tone = Tone.DANGER;
            } else {
                long have = app.store.downloadedBytes(entry);
                text = have > 0 ? "Paused at " + Sizes.bytes(have) + " (Download resumes)" : "Not downloaded";
            }
            status.setText(text);
            Tone.apply(status, tone);
            Controls.show(bar, downloading);
            Controls.show(download, !downloading && !installed);
            Controls.show(cancel, downloading);
            Controls.show(use, installed && !active);
            Controls.show(delete, !downloading && (installed || app.store.downloadedBytes(entry) > 0 || entry.custom()));
        }
    }

    // ---- engine and background model -------------------------------------------------------------------------

    private Section engineSection() {
        Label kind = Controls.caption("");
        kind.setWrapText(true);
        Label version = Controls.caption("");
        ProgressBar bar = new ProgressBar(0);
        bar.getStyleClass().add("bd-progress");
        bar.setMaxWidth(Double.MAX_VALUE);
        Button install = Controls.button("Install", "Download llama.cpp for this kind of engine", app::installEngine);
        Button update = Controls.button("Check for update", "Look for a newer llama.cpp", () -> checkEngineUpdate(version));
        Section s = new Section("Engine", kind, version, bar, new HBox(Theme.SM, install, update),
                Controls.hint("llama.cpp runs the built-in models. It is downloaded the first time a model starts; if the graphics card "
                        + "build won't start, the processor build is used."));
        updaters.add(() -> {
            DownloadJob job = app.jobs.get("engine");
            boolean busy = job != null && !job.finished();
            Optional<Engine.Installed> inst = app.engine.installed(app.variant());
            kind.setText(app.variant().label + " · change it in Settings");
            version.setText(busy ? "Installing: " + job.progress().describe()
                    : inst.map(i -> "Installed: " + i.tag()).orElse("Not installed yet"));
            bar.setProgress(busy && job.progress().fraction() >= 0 ? job.progress().fraction() : 0);
            Controls.show(bar, busy);
            install.setText(inst.isPresent() ? "Reinstall" : "Install");
            install.setDisable(busy);
            update.setDisable(busy || inst.isEmpty());
        });
        return s;
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
            app.ctx.runOnUiThread(() -> {
                version.setText(m);
                app.ctx.toast(m);
            });
        });
    }

    private Section backgroundSection() {
        StatusBadge badge = new StatusBadge(Tone.NEUTRAL, "");
        Label detail = Controls.caption("");
        detail.setWrapText(true);
        Button start = Controls.button("Start", "Start the model now, so the first answer comes sooner", () -> app.workers.execute(() -> {
            try {
                app.startLocal(m -> app.ctx.runOnUiThread(() -> detail.setText(m)));
            } catch (IOException e) {
                app.ctx.runOnUiThread(() -> app.ctx.toast(e.getMessage()));
            }
            app.changed();
        }));
        Button stop = Controls.button("Stop", "Stop the model and free its memory", () -> {
            app.server.stop();
            app.changed();
        });
        Section s = new Section("Background model", detail, new HBox(Theme.SM, start, stop),
                Controls.hint("It runs only while needed and stops when the plugin is turned off or BlockDesigner closes. "
                        + "When it starts and stops by itself is in Settings.")).badge(badge);
        updaters.add(() -> {
            LocalServer.Status st = app.server.status();
            String name = app.activeModel().map(ModelEntry::name).orElse("No model");
            switch (st) {
                case READY -> badge.set(Tone.SUCCESS, "Running");
                case STARTING -> badge.set(Tone.WARNING, "Starting…");
                case FAILED -> badge.set(Tone.DANGER, "Failed");
                default -> badge.set(Tone.NEUTRAL, "Stopped");
            }
            detail.setText(st == LocalServer.Status.FAILED ? name + ": " + Optional.ofNullable(app.server.problem()).orElse("") : name);
            boolean installed = app.activeModel().map(app.store::installed).orElse(false);
            start.setDisable(st == LocalServer.Status.STARTING || st == LocalServer.Status.READY || !installed);
            stop.setDisable(st != LocalServer.Status.READY && st != LocalServer.Status.STARTING);
        });
        return s;
    }

    // ---- other sources ------------------------------------------------------------------------------------------

    private Section localServerSection() {
        Label where = Controls.pathCaption("");
        Section s = new Section("Local server", where,
                Controls.hint("Any OpenAI-compatible server on this PC or your network, such as Ollama or LM Studio."),
                Controls.button("Change in Settings…", "Its address and model are in the Settings window", app.ctx::openSettings));
        updaters.add(() -> where.setText(app.settings.localServerUrl
                + (app.settings.localServerModel.isBlank() ? " · no model named" : " · " + app.settings.localServerModel)));
        return s;
    }

    /** An API key: typed once, saved encrypted when you press Enter or leave the field. */
    private Section keySection(String title, String keyName, String prompt) {
        PasswordField key = new PasswordField();
        key.setAccessibleText(title);
        Label saved = Controls.caption("");
        Runnable showSaved = () -> {
            boolean has = app.secrets.has(keyName);
            saved.setText(has ? "Saved (encrypted for your Windows account)" : "Not set");
            Tone.apply(saved, has ? Tone.SUCCESS : Tone.NEUTRAL);
            key.setPromptText(has ? "Saved: type a new one to replace it" : prompt);
        };
        Runnable save = () -> {
            String typed = key.getText();
            if (typed.isBlank()) return;
            key.clear();
            saved.setText("Saving…");
            app.workers.execute(() -> {
                String error = null;
                try {
                    app.secrets.save(keyName, typed.strip());
                } catch (IOException e) {
                    error = e.getMessage();
                }
                String err = error;
                app.ctx.runOnUiThread(() -> {
                    showSaved.run();
                    if (err != null) saved.setText("Not saved: " + err);
                    app.changed();
                });
            });
        };
        key.setOnAction(e -> save.run());
        key.focusedProperty().addListener((o, was, is) -> {
            if (!is) save.run();
        });
        Button remove = Controls.button("Remove key", "Delete the saved key", () -> {
            try {
                app.secrets.delete(keyName);
            } catch (IOException e) {
                saved.setText("Couldn't remove it: " + e.getMessage());
                return;
            }
            showSaved.run();
            app.changed();
        });
        remove.getStyleClass().addAll("flat", "small");
        Form f = new Form();
        f.row("API key", key);
        Section s = new Section(title, f, new HBox(Theme.SM, saved, Controls.spacer(), remove),
                Controls.hint("Keys are encrypted for your Windows account (DPAPI) and kept in the plugin's folder. Using one sends your "
                        + "messages and a scene summary to that company. The model and address are in Settings."));
        updaters.add(showSaved);
        return s;
    }

    private Section customSection() {
        TextField name = new TextField();
        name.setPromptText("Name (optional)");
        TextField model = new TextField();
        model.setPromptText("owner/repo/file.gguf or a link");
        TextField mmproj = new TextField();
        mmproj.setPromptText("Vision projector (mmproj) file, optional");
        Button add = Controls.button("Add to the list", "Add it to the built-in models, to download and use the same way", () -> {
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
        Form f = new Form();
        f.row("Name", name);
        f.row("Model file", model);
        f.row("Projector", mmproj).help("Only for models that see pictures.");
        return new Section("Custom model", Controls.hint("A GGUF model from Hugging Face. It appears with the built-in models."), f, add)
                .collapsible(false);
    }
}
