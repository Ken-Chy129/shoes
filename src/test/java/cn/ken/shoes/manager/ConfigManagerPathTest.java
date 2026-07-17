package cn.ken.shoes.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigManagerPathTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesKnownAccountInputInsideTheConfiguredDirectory() {
        Path resolved = ConfigManager.resolveAccountInputPath(tempDir, "account-a", "STANDARD");

        assertThat(resolved.getParent()).isEqualTo(tempDir.toAbsolutePath().normalize());
        assertThat(resolved.getFileName().toString()).isEqualTo("account-a__STANDARD.json");
    }

    @Test
    void rejectsPathTraversalAndUnknownInventoryTypes() {
        assertThatThrownBy(() -> ConfigManager.resolveAccountInputPath(tempDir, "../outside", "STANDARD"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfigManager.resolveAccountInputPath(tempDir, "account-a", "../../outside"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
