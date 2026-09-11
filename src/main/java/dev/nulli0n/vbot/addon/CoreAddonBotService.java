package dev.nulli0n.vbot.addon;

import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import dev.nulli0n.vbot.Bots4VeloPlugin;
import dev.nulli0n.vbot.addon.api.AddonBotEvent;
import dev.nulli0n.vbot.addon.api.AddonBotService;
import dev.nulli0n.vbot.addon.api.AddonBotSnapshot;
import dev.nulli0n.vbot.addon.api.AddonBotState;
import dev.nulli0n.vbot.addon.api.AddonServerSwitchResult;
import dev.nulli0n.vbot.addon.api.AddonServerSwitchStatus;
import dev.nulli0n.vbot.bot.BotEvent;
import dev.nulli0n.vbot.bot.BotSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

public final class CoreAddonBotService implements AddonBotService {
    private final Bots4VeloPlugin plugin;
    private final ProxyServer proxy;
    private final ConcurrentMap<Consumer<AddonBotEvent>, Consumer<BotEvent>> listeners = new ConcurrentHashMap<>();

    public CoreAddonBotService(Bots4VeloPlugin plugin, ProxyServer proxy) {
        this.plugin = plugin;
        this.proxy = proxy;
    }

    @Override
    public List<AddonBotSnapshot> bots() {
        return plugin.bots().stream().map(CoreAddonBotService::snapshot).toList();
    }

    @Override
    public Optional<AddonBotSnapshot> bot(String id) {
        return plugin.bot(id).map(CoreAddonBotService::snapshot);
    }

    @Override
    public boolean start(String id) {
        return plugin.start(id);
    }

    @Override
    public boolean stop(String id) {
        return plugin.stop(id);
    }

    @Override
    public boolean reconnect(String id) {
        return plugin.reconnect(id);
    }

    @Override
    public Optional<String> currentServer(String id) {
        return plugin.currentServer(id);
    }

    @Override
    public CompletionStage<AddonServerSwitchResult> switchServer(String id, String server) {
        return plugin.switchBotServer(id, server).thenApply(result -> new AddonServerSwitchResult(
            AddonServerSwitchStatus.valueOf(result.status().name()), result.botId(), result.username(),
            result.server(), result.detail()));
    }

    @Override
    public boolean isPlayerOnline(UUID playerId) {
        return playerId != null && proxy.getPlayer(playerId).isPresent();
    }

    @Override
    public boolean sendPlayerMessage(UUID playerId, String message) {
        return playerId != null && proxy.getPlayer(playerId).map(player -> {
            player.sendMessage(Component.text(message == null ? "" : message));
            return true;
        }).orElse(false);
    }

    @Override
    public void addEventListener(Consumer<AddonBotEvent> listener) {
        if (listener == null) {
            return;
        }
        Consumer<BotEvent> adapter = event -> listener.accept(new AddonBotEvent(
            event.at(), event.botId(), event.type(), event.detail()));
        Consumer<BotEvent> previous = listeners.putIfAbsent(listener, adapter);
        if (previous == null) {
            plugin.addEventListener(adapter);
        }
    }

    @Override
    public void removeEventListener(Consumer<AddonBotEvent> listener) {
        Consumer<BotEvent> adapter = listeners.remove(listener);
        if (adapter != null) {
            plugin.removeEventListener(adapter);
        }
    }

    private static AddonBotSnapshot snapshot(BotSnapshot snapshot) {
        return new AddonBotSnapshot(snapshot.id(), snapshot.username(),
            AddonBotState.valueOf(snapshot.state().name()), snapshot.authenticationComplete());
    }
}
