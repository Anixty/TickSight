package net.anixty.ticksight.forge;

import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBusInvokeDispatcher;
import net.minecraftforge.eventbus.api.IEventListener;
import net.anixty.ticksight.TickSight;

public class TransformHandler {
    public static void handleInvoke(IEventBusInvokeDispatcher dispatcher, IEventListener listener, Event event) {
        TickSight.INSTANCE.getLOGGER().info("Processing listener: " + listener.getClass().getName());
        dispatcher.invoke(listener, event);
    }
}

