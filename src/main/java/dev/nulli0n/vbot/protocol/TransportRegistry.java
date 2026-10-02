package dev.nulli0n.vbot.protocol;

import dev.nulli0n.vbot.transport.BotTransport;
import dev.nulli0n.vbot.transport.BotTransportFactory;
import dev.nulli0n.vbot.transport.TransportConfig;
import dev.nulli0n.vbot.transport.TransportListener;

import java.lang.reflect.InvocationTargetException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

public final class TransportRegistry {
    private final Map<ProtocolVersion, String> factories = new EnumMap<>(ProtocolVersion.class);

    public TransportRegistry() {
        factories.put(ProtocolVersion.MINECRAFT_1_21_11,
            "dev.nulli0n.vbot.adapter.v1_21_11.ModernTransportFactory");
        factories.put(ProtocolVersion.MINECRAFT_26_1_2,
            "dev.nulli0n.vbot.adapter.v26_1_2.ModernTransportFactory");
        factories.put(ProtocolVersion.MINECRAFT_26_2,
            "dev.nulli0n.vbot.adapter.v26_2.ModernTransportFactory");
        factories.put(ProtocolVersion.MINECRAFT_26_3,
            "dev.nulli0n.vbot.adapter.v26_3.ModernTransportFactory");
        factories.put(ProtocolVersion.MINECRAFT_1_16_5,
            "dev.nulli0n.vbot.adapter.v1_16_5.LegacyTransportFactory");
    }

    public BotTransport create(ProtocolVersion version, TransportConfig config, TransportListener listener,
                               ScheduledExecutorService executor) {
        String className = factories.get(version);
        if (className == null) {
            throw new IllegalArgumentException("No transport factory registered for " + version.displayName());
        }
        BotTransportFactory factory = instantiate(className);
        if (factory.protocolId() != version.protocolId()) {
            throw new IllegalStateException("Adapter " + className + " reports protocol " + factory.protocolId()
                + " but " + version.displayName() + " requires " + version.protocolId());
        }
        return factory.create(config, listener, executor);
    }

    public BotTransportFactory factory(ProtocolVersion version) {
        String className = factories.get(version);
        if (className == null) {
            throw new IllegalArgumentException("No transport factory registered for " + version.displayName());
        }
        return instantiate(className);
    }

    private static BotTransportFactory instantiate(String className) {
        try {
            Class<?> type = Class.forName(className, true, TransportRegistry.class.getClassLoader());
            return (BotTransportFactory) type.getConstructor().newInstance();
        }
        catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Protocol adapter is not packaged: " + className, exception);
        }
        catch (InstantiationException | IllegalAccessException | NoSuchMethodException
               | InvocationTargetException | ClassCastException exception) {
            throw new IllegalStateException("Could not initialize protocol adapter: " + className, exception);
        }
    }
}
