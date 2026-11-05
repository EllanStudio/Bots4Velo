package dev.nulli0n.vbot.adapter.modern;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Components that the stock plain serializer rejects must not disconnect a bot (seen on spawn with 26.3). */
class ModernBotTransportPlainTextTest {
    @Test
    void scoreComponentDoesNotThrow() {
        Component message = Component.text("Welcome ").append(Component.score("@p", "kills"));

        assertThatCode(() -> ModernBotTransport.plain(message)).doesNotThrowAnyException();
        assertThat(ModernBotTransport.plain(message)).startsWith("Welcome ");
    }

    @Test
    void selectorAndNbtComponentsDoNotThrow() {
        assertThat(ModernBotTransport.plain(Component.selector("@a"))).isEqualTo("@a");
        assertThatCode(() -> ModernBotTransport.plain(
            Component.blockNBT().nbtPath("Items").pos(net.kyori.adventure.text.BlockNBTComponent.Pos.fromString("~0 ~0 ~0")).build()))
            .doesNotThrowAnyException();
    }

    @Test
    void ordinaryTextAndTranslatableStillWork() {
        assertThat(ModernBotTransport.plain(Component.text("请登录 ").append(Component.text("/login")))).isEqualTo("请登录 /login");
        assertThat(ModernBotTransport.plain(Component.translatable("disconnect.genericReason"))).isEqualTo("disconnect.genericReason");
        assertThat(ModernBotTransport.plain(null)).isEmpty();
    }
}
