package io.blockdesigner.aibuilder.agent;

import io.blockdesigner.core.model.BlockPos;
import io.blockdesigner.core.model.BlockState;
import io.blockdesigner.core.model.Box;
import io.blockdesigner.core.model.Layer;
import io.blockdesigner.core.model.Structure;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SceneSummaryTest {
    private static Layer layer(String name, int size, int height, String block) {
        Structure s = new Structure();
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) for (int y = 0; y < height; y++) s.set(x, y, z, BlockState.parse("minecraft:" + block));
        return new Layer(name, s);
    }

    @Test
    void describesLayersPaletteAndAHeightMap() {
        Layer base = layer("Keep", 6, 1, "stone_bricks");
        Layer tower = layer("Tower", 2, 5, "cobblestone");
        tower.setOffset(new BlockPos(4, 1, 0));
        Layer hidden = layer("Hidden", 3, 3, "gold_block");
        hidden.setVisible(false);
        List<Layer> layers = List.of(base, tower, hidden);
        Box bounds = SceneSummary.bounds(layers).orElseThrow();
        assertThat(bounds).isEqualTo(Box.of(new BlockPos(0, 0, 0), new BlockPos(5, 5, 5)));
        Scope scope = Scope.choose(Scope.Choice.AUTO, Optional.empty(), Optional.of(bounds));
        assertThat(scope.mode()).isEqualTo(Scope.Mode.EVERYTHING);
        String text = SceneSummary.describe(layers, scope);
        assertThat(text).contains("SCOPE: everything visible").contains("\"Keep\": from 0 0 0 to 5 0 5").contains("36 blocks; stone_bricks 100%")
                .contains("\"Tower\"").doesNotContain("Hidden").contains("HEIGHT MAP").contains("BLOCKS IN SCOPE: stone_bricks");
        // Row z=0: x 0..3 are floor (0), x 4..5 are the tower's top (5).
        assertThat(text).contains("\n000055\n");
        assertThat(text).contains("\n000000\n");
    }

    @Test
    void bigAreasAreSampled() {
        Layer big = layer("Field", 100, 1, "grass_block");
        Scope scope = Scope.choose(Scope.Choice.EVERYTHING, Optional.empty(), SceneSummary.bounds(List.of(big)));
        String text = SceneSummary.describe(List.of(big), scope);
        assertThat(text).contains("each character is 4×4 blocks");
        String map = text.substring(text.indexOf("x=0):\n") + 6);
        assertThat(map.lines().findFirst().orElseThrow()).hasSize(25);
    }

    @Test
    void scopeChoices() {
        Optional<Box> sel = Optional.of(Box.of(new BlockPos(1, 2, 3), new BlockPos(4, 5, 6)));
        Optional<Box> scene = Optional.of(Box.of(new BlockPos(0, 0, 0), new BlockPos(9, 9, 9)));
        assertThat(Scope.choose(Scope.Choice.AUTO, sel, scene).mode()).isEqualTo(Scope.Mode.SELECTION);
        assertThat(Scope.choose(Scope.Choice.AUTO, sel, scene).describe()).isEqualTo("Editing: selection 4×4×4");
        assertThat(Scope.choose(Scope.Choice.AUTO, Optional.empty(), Optional.empty()).describe()).isEqualTo("New build at 0 0 0 (its own layer)");
        Scope beside = Scope.choose(Scope.Choice.NEW_BUILD, sel, scene);
        assertThat(beside.anchor()).isEqualTo(new BlockPos(15, 0, 0));
        assertThat(Scope.choose(Scope.Choice.SELECTION, Optional.empty(), scene).mode()).isEqualTo(Scope.Mode.EVERYTHING);
        String empty = SceneSummary.describe(List.of(), Scope.choose(Scope.Choice.AUTO, Optional.empty(), Optional.empty()));
        assertThat(empty).contains("the scene is empty").contains("ground level is y=0");
    }
}
