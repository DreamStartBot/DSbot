package net.runelite.client.plugins.microbot.util.mouse.naturalmouse;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.enums.ActivityIntensity;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.api.MouseInfoAccessor;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.api.MouseMotionFactory;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.api.SpeedManager;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.api.SystemCalls;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.support.DefaultMouseMotionNature;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.support.DefaultSpeedManager;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.support.Flow;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.support.MouseMotionNature;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.util.FactoryTemplates;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.util.FlowTemplates;
import net.runelite.client.plugins.microbot.util.mouse.naturalmouse.util.Pair;

import javax.inject.Inject;
import java.awt.*;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class NaturalMouse {
    public final MouseMotionNature nature;
    private final ThreadLocalRandom random = ThreadLocalRandom.current();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    @Inject
    private Client client;
    @Getter
    @Setter
    private List<Flow> flows = List.of(
            new Flow(FlowTemplates.variatingFlow()),
            new Flow(FlowTemplates.slowStartupFlow()),
            new Flow(FlowTemplates.slowStartup2Flow()),
            new Flow(FlowTemplates.jaggedFlow()),
            new Flow(FlowTemplates.interruptedFlow()),
            new Flow(FlowTemplates.interruptedFlow2()),
            new Flow(FlowTemplates.stoppingFlow()),
            new Flow(FlowTemplates.adjustingFlow()),
            new Flow(FlowTemplates.random())
    );

    private volatile MouseMotionFactory cachedFactory;
    private volatile ActivityIntensity cachedIntensity;

    // ===================== [MICROBOT 本地改动] 鼠标移动速度覆盖 =====================
    // 本地新增：允许外部直接指定一次移动要花多久，不受 ActivityIntensity 影响。
    //
    // 为什么要这个：
    // 上游把「移动快慢」和「轨迹形状」绑在同一个 ActivityIntensity 档位上 ——
    // 换档位会同时换掉轨迹模板（匀速/变速/慢启动/抖动）和移动耗时。
    // 而 ActivityIntensity 还被 antiban 拿去决定行为强度（PASSIVE ~ EXTREME_AGGRESSIVE），
    // 想单独调鼠标速度就只能连 antiban 一起改。
    //
    // 这里只覆盖耗时，形状和 antiban 的强度都不动。
    //
    // null 表示不覆盖，走档位自己的耗时。
    //
    // 覆盖值在每次 getFactory() 时重新应用 —— 因为换档位会重建 factory，
    // 新的 factory 自带一个默认耗时的 SpeedManager，不重新应用就会把设置丢掉。
    //
    // 冲突时如何覆盖：保留这个字段和下面两个方法。
    // ===================== [MICROBOT 本地改动 END] =====================
    private volatile Long movementTimeOverrideMs;

    /**
     * 覆盖鼠标移动耗时。传 null 表示取消覆盖，跟随当前的 ActivityIntensity 档位。
     *
     * @param movementTimeMs 一次移动的基准耗时（毫秒），实际耗时是这个值到两倍之间的随机数
     */
    public void setMovementTimeOverrideMs(Long movementTimeMs) {
        this.movementTimeOverrideMs = movementTimeMs;

        // 取消覆盖时把缓存的 factory 丢掉：覆盖是直接改在 factory 的 SpeedManager 上的，
        // 光把覆盖值清空，SpeedManager 里留着上一次写的耗时就回不去了。
        // 丢掉缓存之后，下次 getFactory() 会按当前档位重建一个，耗时自然是档位自己的。
        //
        // 换档位本来也会重建 factory，所以覆盖开着的时候不用担心残留 ——
        // 新 factory 建好后 applyMovementTimeOverride 会重新写一遍覆盖值。
        if (movementTimeMs == null) {
            cachedFactory = null;
        }
    }

    /**
     * 当前生效的移动耗时覆盖值。
     *
     * @return 覆盖值，null 表示没覆盖
     */
    public Long getMovementTimeOverrideMs() {
        return movementTimeOverrideMs;
    }

    /**
     * 把覆盖的耗时应用到 factory 上。
     *
     * <p>只有 {@link DefaultSpeedManager} 支持改耗时，其它 SpeedManager 实现（比如
     * 按距离线性算时间的那个）跳过。
     */
    private void applyMovementTimeOverride(MouseMotionFactory factory) {
        Long override = movementTimeOverrideMs;
        if (override == null || factory == null) {
            return;
        }

        SpeedManager speedManager = factory.getSpeedManager();
        if (speedManager instanceof DefaultSpeedManager) {
            ((DefaultSpeedManager) speedManager).setMouseMovementBaseTimeMs(override);
        }
    }

    @Inject
    public NaturalMouse() {
        nature = new DefaultMouseMotionNature();
        nature.setSystemCalls(new SystemCallsImpl());
        nature.setMouseInfo(new MouseInfoImpl());
    }

    public synchronized void moveTo(int dx, int dy) {
//		if(Rs2UiHelper.isStretchedEnabled())
//		{
//			dx = Rs2UiHelper.stretchX(dx);
//			dy = Rs2UiHelper.stretchY(dy);
//		}
        int finalDx = dx;
        int finalDy = dy;
        Point mousePos = Microbot.getMouse().getMousePosition();
        // check if current mouse position is already at the destination
        if (mousePos.x == finalDx && mousePos.y == finalDy) {
            return;
        }

        if (!Microbot.getClient().isClientThread()) {
            move(finalDx, finalDy);
        } else {

            executorService.submit(() -> move(finalDx, finalDy));
        }
    }

    private synchronized void move(int dx, int dy) {
        var motion = getFactory().build(dx, dy);
        try {
            motion.move();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public MouseMotionFactory getFactory() {
        ActivityIntensity intensity = Rs2Antiban.getActivityIntensity();
        if (cachedFactory != null && intensity == cachedIntensity) {
            // ===================== [MICROBOT 本地改动] 鼠标移动速度覆盖 =====================
            // 缓存命中时也要重新应用 —— 覆盖值可能在 factory 建好之后才被改过。
            // ===================== [MICROBOT 本地改动 END] =====================
            applyMovementTimeOverride(cachedFactory);
            return cachedFactory;
        }
        MouseMotionFactory factory;
        if (intensity == ActivityIntensity.VERY_LOW) {
            log.debug("Creating average computer user motion factory");
            factory = FactoryTemplates.createAverageComputerUserMotionFactory(nature);
        } else if (intensity == ActivityIntensity.LOW) {
            log.debug("Creating normal gamer motion factory");
            factory = FactoryTemplates.createNormalGamerMotionFactory(nature);
        } else if (intensity == ActivityIntensity.MODERATE) {
            log.debug("Creating fast gamer motion factory");
            factory = FactoryTemplates.createFastGamerMotionFactory(nature);
        } else if (intensity == ActivityIntensity.HIGH) {
            log.debug("Creating fast gamer motion factory");
            factory = FactoryTemplates.createFastGamerMotionFactory(nature);
        } else if (intensity == ActivityIntensity.EXTREME) {
            log.debug("Creating super fast gamer motion factory");
            factory = FactoryTemplates.createSuperFastGamerMotionFactory(nature);
        } else {
            log.debug("Default: Creating super fast gamer motion factory");
            factory = FactoryTemplates.createSuperFastGamerMotionFactory(nature);
        }
        cachedIntensity = intensity;
        cachedFactory = factory;
        // ===================== [MICROBOT 本地改动] 鼠标移动速度覆盖 =====================
        // 新建的 factory 自带一个默认耗时的 SpeedManager，不覆盖的话设置就丢了。
        // ===================== [MICROBOT 本地改动 END] =====================
        applyMovementTimeOverride(factory);
        return factory;

//		var factory = new MouseMotionFactory();
//		factory.setNature(nature);
//		factory.setRandom(random);
//
//		var manager = new SpeedManagerImpl(flows);
//		factory.setDeviationProvider(new SinusoidalDeviationProvider(15.0));
//		factory.setNoiseProvider(new DefaultNoiseProvider(2.0));
//		factory.getNature().setReactionTimeVariationMs(120);
//		manager.setMouseMovementBaseTimeMs(130);
//
//		var overshootManager = (DefaultOvershootManager) factory.getOvershootManager();
//		overshootManager.setOvershoots(2);
//		factory.setSpeedManager(manager);
//
//		return factory;
    }

    /**
     * Moves the mouse off screen with a default 100% chance.
     * This method will always move the mouse off screen when called.
     */
    public void moveOffScreen() {
        // Always move the mouse off screen (default behavior)
        moveOffScreen(100.0); // Calls the overloaded method with a 100% chance
    }

    /**
     * Moves the mouse off screen based on a given percentage chance.
     *
     * @param chancePercentage the chance (in percentage) to move the mouse off screen.
     *                         This value should be between 0.0 and 100.0 (inclusive).
     *                         Note: This parameter should not be a fractional value between
     *                         0.0 and 0.99; use values representing a whole percentage (e.g., 25.0, 50.0).
     */
    public void moveOffScreen(double chancePercentage) {
        if (chancePercentage < 100 && !Rs2Random.dicePercentage(chancePercentage)) {
            return;
        }

        int horizontal = random.nextBoolean() ? -1 : client.getCanvasWidth() + 1;
        int vertical = random.nextBoolean() ? -1 : client.getCanvasHeight() + 1;

        boolean exitHorizontally = random.nextBoolean();
        int targetX = exitHorizontally ? horizontal : random.nextInt(0, client.getCanvasWidth() + 1);
        int targetY = exitHorizontally ? random.nextInt(0, client.getCanvasHeight() + 1) : vertical;

        Runnable travelThenCross = () -> {
            // MouseMotion clamps to the canvas, so this reaches the edge and the next call crosses.
            move(targetX, targetY);
            Microbot.getMouse().move(targetX, targetY);
        };

        // One task: moveTo is async on the client thread, where two calls would cross first.
        if (Microbot.getClient().isClientThread()) {
            executorService.submit(travelThenCross);
        } else {
            travelThenCross.run();
        }
    }

    // Move to a random point on the screen
    public void moveRandom() {
        moveTo(random.nextInt(0, client.getCanvasWidth() + 1), random.nextInt(0, client.getCanvasHeight() + 1));
    }

    private static class SpeedManagerImpl extends DefaultSpeedManager {
        private SpeedManagerImpl(Collection<Flow> flows) {
            super(flows);
        }

        @Override
        public Pair<Flow, Long> getFlowWithTime(double distance) {
            var pair = super.getFlowWithTime(distance);
            return new Pair<>(pair.x, pair.y);
        }
    }

    private static class MouseInfoImpl implements MouseInfoAccessor {
        @Override
        public Point getMousePosition() {
            return Microbot.getMouse().getMousePosition();
        }
    }

    private class SystemCallsImpl implements SystemCalls {
        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public void sleep(long time) {
            Global.sleep((int) time);
        }

        @Override
        public Dimension getScreenSize() {
            return Microbot.getClient().getCanvas().getSize();
        }

        @Override
        public void setMousePosition(int x, int y) {
            Microbot.getMouse().move(x, y);

        }
    }
}
