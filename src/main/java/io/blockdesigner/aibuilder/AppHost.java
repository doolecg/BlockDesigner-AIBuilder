package io.blockdesigner.aibuilder;

import io.blockdesigner.aibuilder.agent.Assistant;
import io.blockdesigner.aibuilder.agent.SceneWorld;
import io.blockdesigner.aibuilder.agent.Scope;
import io.blockdesigner.aibuilder.build.OverlayWorld;
import io.blockdesigner.core.model.Box;
import io.blockdesigner.core.model.Layer;
import io.blockdesigner.plugin.PluginContext;
import javafx.application.Platform;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** The assistant's view of BlockDesigner: the scene through the plugin context, edits as undo steps. */
final class AppHost implements Assistant.Host {
    private final PluginContext ctx;

    AppHost(PluginContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public <T> T onUi(Callable<T> task) throws Exception {
        if (Platform.isFxApplicationThread()) return task.call();
        CompletableFuture<T> f = new CompletableFuture<>();
        ctx.runOnUiThread(() -> {
            try {
                f.complete(task.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        try {
            return f.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        }
    }

    @Override
    public List<Layer> layers() {
        return List.copyOf(ctx.scene().layers());
    }

    @Override
    public Optional<Box> selection() {
        return ctx.selection();
    }

    @Override
    public Assistant.Applied apply(String label, Scope scope, OverlayWorld overlay, String layerName) {
        if (scope.newLayer()) {
            // The blocks are in world coordinates and the new layer sits at the origin: one undo step, and it becomes active.
            Layer layer = ctx.addLayer(layerName, overlay.toStructure());
            return new Assistant.Applied(label, scope, Map.of(), overlay.changes(), layer);
        }
        Assistant.Applied a = Assistant.edit(label, scope, overlay, new SceneWorld(layers())::get);
        ctx.editWorld(label, w -> a.after().forEach(w::set));
        return a;
    }

    @Override
    public void undo(Assistant.Applied a) {
        if (a.layer() != null) {
            if (ctx.scene().layers().contains(a.layer())) ctx.editor().removeLayer(a.layer());
            return;
        }
        ctx.editWorld("Undo " + a.label(), w -> a.after().forEach((p, s) -> {
            if (w.get(p) == s) w.set(p, a.before().get(p));   // leave blocks changed since alone
        }));
    }
}
