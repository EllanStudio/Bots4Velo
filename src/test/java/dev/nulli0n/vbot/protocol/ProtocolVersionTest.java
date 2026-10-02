package dev.nulli0n.vbot.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolVersionTest {
    @Test
    void mapsAllRequiredVersions() {
        assertThat(ProtocolVersion.parse("1.16.5").protocolId()).isEqualTo(754);
        assertThat(ProtocolVersion.parse("1.21.11").protocolId()).isEqualTo(774);
        assertThat(ProtocolVersion.parse("26.1").protocolId()).isEqualTo(775);
        assertThat(ProtocolVersion.parse("26.1.2").protocolId()).isEqualTo(775);
        assertThat(ProtocolVersion.parse("26.2").protocolId()).isEqualTo(776);
        assertThat(ProtocolVersion.parse("26.3").protocolId()).isEqualTo(777);
    }

    @Test
    void rejectsUnknownVersion() {
        assertThatThrownBy(() -> ProtocolVersion.parse("1.20.4"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported");
    }
}
