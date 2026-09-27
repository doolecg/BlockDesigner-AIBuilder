package io.blockdesigner.aibuilder;

import io.blockdesigner.aibuilder.build.Styles;
import io.blockdesigner.aibuilder.models.Engine;
import io.blockdesigner.plugin.OptionValues;
import io.blockdesigner.plugin.Options;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The plugin's page in BlockDesigner's Settings window: where the answers come from, the built-in model's engine and
 * when it runs, the default style, and the limits. The values map onto {@link AiSettings} (which the rest of the
 * plugin reads); API keys stay on the Models page (encrypted), and the model in use is picked there too.
 */
public final class AiOptions {
    static final String AUTO_STYLE = "Auto";
    static final List<String> IDLE = List.of("Never", "10 minutes", "30 minutes", "60 minutes");

    private static final String LOCAL = AiSettings.Provider.LOCAL_SERVER.label, CLAUDE = AiSettings.Provider.CLAUDE.label,
            OPENAI = AiSettings.Provider.OPENAI.label;

    public static final Options OPTIONS = Options.builder()
            .group("Answers come from")
            .choice("provider", "Answers from", Arrays.stream(AiSettings.Provider.values()).map(p -> p.label).toList(),
                    AiSettings.Provider.BUILT_IN.label)
            .help("The built-in model runs on this PC. The others use your own server or API key (keys are on the Models page).")
            .text("claudeModel", "Claude model", "claude-opus-5-5")
            .showWhen("provider", CLAUDE)
            .text("openaiUrl", "Address", "https://api.openai.com/v1")
            .showWhen("provider", OPENAI)
            .text("openaiModel", "Model", "gpt-5")
            .showWhen("provider", OPENAI)
            .text("localServerUrl", "Address", "http://localhost:11434/v1")
            .showWhen("provider", LOCAL)
            .help("Any OpenAI-compatible address: Ollama is http://localhost:11434/v1, LM Studio http://localhost:1234/v1.")
            .text("localServerModel", "Model", "")
            .showWhen("provider", LOCAL)
            .help("The model's name on the server, such as gemma3:12b.")
            .toggle("localServerVision", "The model sees pictures", true)
            .showWhen("provider", LOCAL)
            .group("Built-in model")
            .choice("engineVariant", "Engine", Arrays.stream(Engine.Variant.values()).map(v -> v.label).toList(), Engine.Variant.GPU.label)
            .help("llama.cpp runs the built-in models; it is downloaded the first time a model starts.")
            .toggle("startAtLaunch", "Start it when BlockDesigner starts", false)
            .choice("idle", "Stop it when unused for", IDLE, "30 minutes")
            .group("Building")
            .choice("style", "Default style", styles(), AUTO_STYLE)
            .help("The look of new builds when you don't ask for one. Auto picks one that fits the request.")
            .advanced("Advanced")
            .integer("contextSize", "Context size", 16384, 2048, 131072).unit("tokens")
            .help("How much of the chat and scene the built-in model keeps in mind. More needs more memory.")
            .integer("maxBlocks", "Most blocks per answer", 250_000, 1000, 2_000_000).unit("blocks")
            .build();

    private AiOptions() {
    }

    private static List<String> styles() {
        List<String> out = new ArrayList<>();
        out.add(AUTO_STYLE);
        Styles.all().forEach(s -> out.add(s.name()));
        return out;
    }

    /** Copies the page's values into the plugin's settings. */
    public static void apply(OptionValues v, AiSettings s) {
        s.provider = Arrays.stream(AiSettings.Provider.values()).filter(p -> p.label.equals(v.choice("provider"))).findFirst()
                .orElse(AiSettings.Provider.BUILT_IN);
        s.claudeModel = v.text("claudeModel").strip();
        s.openaiUrl = v.text("openaiUrl").strip();
        s.openaiModel = v.text("openaiModel").strip();
        s.localServerUrl = v.text("localServerUrl").strip();
        s.localServerModel = v.text("localServerModel").strip();
        s.localServerVision = v.toggle("localServerVision");
        s.engineVariant = Arrays.stream(Engine.Variant.values()).filter(x -> x.label.equals(v.choice("engineVariant"))).findFirst()
                .orElse(Engine.Variant.GPU).id;
        s.startAtLaunch = v.toggle("startAtLaunch");
        String idle = v.choice("idle");
        s.idleMinutes = idle.equals("Never") ? 0 : Integer.parseInt(idle.replaceAll("\\D", ""));
        s.style = AUTO_STYLE.equals(v.choice("style")) ? "auto" : v.choice("style");
        s.contextSize = v.integer("contextSize");
        s.maxBlocks = v.integer("maxBlocks");
    }

    /** The page's values for these settings (moving settings from before 0.2.0 over, and after tests). */
    public static OptionValues values(OptionValues v, AiSettings s) {
        String idle = s.idleMinutes == 0 ? "Never" : s.idleMinutes + " minutes";
        String style = "auto".equals(s.style) || !styles().contains(s.style) ? AUTO_STYLE : s.style;
        return v.with("provider", s.provider.label)
                .with("claudeModel", s.claudeModel)
                .with("openaiUrl", s.openaiUrl)
                .with("openaiModel", s.openaiModel)
                .with("localServerUrl", s.localServerUrl)
                .with("localServerModel", s.localServerModel)
                .with("localServerVision", s.localServerVision)
                .with("engineVariant", Engine.Variant.fromId(s.engineVariant).label)
                .with("startAtLaunch", s.startAtLaunch)
                .with("idle", IDLE.contains(idle) ? idle : "30 minutes")
                .with("style", style)
                .with("contextSize", s.contextSize)
                .with("maxBlocks", s.maxBlocks);
    }
}
