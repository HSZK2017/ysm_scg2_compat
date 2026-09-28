package com.ysm.scg2.compat;

import com.mojang.logging.LogUtils;
import com.ysm.scg2.compat.ysm.YsmFork;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLLoader;
import org.slf4j.Logger;

/**
 * Runs the deferred client setup once mod loading has finished - on the right bus, with a fallback
 * path if that event is missed, plus a heartbeat probe so a silent failure cannot hide.
 *
 * <h2>The bus mistake this exists to avoid repeating</h2>
 * <p>{@link FMLLoadCompleteEvent} is a <b>mod lifecycle</b> event: it is posted on the mod event bus
 * ({@code FMLJavaModLoadingContext.get().getModEventBus()}), not on {@code MinecraftForge.EVENT_BUS}.
 * Registering a listener for it on the Forge bus produces no error and no warning - the method is
 * simply never called, which is indistinguishable from "called, but the condition was false".</p>
 *
 * <h2>Three paths plus a heartbeat</h2>
 * <ol>
 *   <li>the mod-bus {@code FMLLoadCompleteEvent};</li>
 *   <li>the client tick, which fires a few times between loading and the world being ready;</li>
 *   <li>the first player render, after a world definitely exists and YSM has applied the local
 *       player's model;</li>
 *   <li>a heartbeat: for the first minute after loading, one probe line every five seconds with the
 *       bridge state. This is what makes "it never fired" and "it fired but the state was wrong"
 *       different observations instead of the same silence.</li>
 * </ol>
 * <p>{@link DeferredInit} is one-shot, so the extra paths cost a boolean check.</p>
 */
public final class LoadCompleteHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Heartbeat window and period: a minute at 5s covers "did anything ever run?". */
    private static final int HEARTBEAT_TICKS = 20 * 60;
    private static final int HEARTBEAT_PERIOD = 20 * 5;

    private static volatile boolean fired;

    private int tickCounter;

    public LoadCompleteHandler() {
    }

    /** Registers on the <b>mod</b> bus for the lifecycle event. Called from the mod constructor. */
    public void register() {
        try {
            FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onLoadComplete);
        } catch (Throwable t) {
            ProbeLog.log("deferred", "could not register on the mod bus: " + ProbeLog.describe(t));
        }
    }

    /** {@code FMLLoadCompleteEvent}, mod bus. The normal path. */
    private void onLoadComplete(FMLLoadCompleteEvent event) {
        ProbeLog.log("deferred", "FMLLoadCompleteEvent received on the mod bus");
        if (!FMLLoader.getDist().isClient()) {
            return;
        }
        fire("load-complete event");
    }

    /** Client tick, Forge bus. Fires a handful of times before the world is up. */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        fire("client tick");
        heartbeat();
    }

    private void fire(String via) {
        if (fired) {
            return;
        }
        fired = true;
        ProbeLog.log("deferred", "firing deferred setup via " + via);

        try {
            YsmFork.onLoadComplete();
        } catch (Throwable t) {
            ProbeLog.log("deferred", "YsmFork.onLoadComplete threw " + ProbeLog.describe(t));
        }
        if (FMLLoader.getDist().isClient()) {
            DeferredInit.runAll();
        }
    }

    /**
     * One probe line every five seconds for the first minute of play.
     *
     * <p>Reports the two facts that decide which half of the problem this is: whether the deferred
     * setup ran at all, and whether the guard has ever been reached. Without it, a run where nothing
     * happens produces a log with nothing in it, and "nothing in the log" has been read as three
     * different causes on three separate occasions.</p>
     */
    private void heartbeat() {
        this.tickCounter++;
        if (this.tickCounter > HEARTBEAT_TICKS || this.tickCounter % HEARTBEAT_PERIOD != 0) {
            return;
        }
        try {
            ProbeLog.log("tick", "heartbeat t=" + (this.tickCounter / 20) + "s"
                    + " deferredFired=" + fired
                    + " guardReached=" + com.ysm.scg2.compat.client.GunAnimationDecision.hasBeenReached()
                    + " bridgeAvailable=" + com.ysm.scg2.compat.client.YsmBridge.isAvailable());
        } catch (Throwable t) {
            ProbeLog.log("tick", "heartbeat failed: " + ProbeLog.describe(t));
        }
    }
}
