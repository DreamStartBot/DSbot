package net.runelite.client.plugins.devtools;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Point;
import net.runelite.client.plugins.microbot.util.input.PointerState;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Bot 准星。样式是「白色轮廓圆 + 旋转风扇叶 + 移动轨迹」。
 *
 * <p>位置来源：
 *
 * <ul>
 *     <li>有 bot 位置时，跟随 bot 的目标位置，也就是 {@link PointerState#lastBotPoint()}；</li>
 *     <li>否则，跟随真实鼠标位置，也就是 {@link PointerState#get()}。</li>
 * </ul>
 *
 * <p>之所以要单独一份实现，而不是直接用 {@link MicrobotMouseOverlay}：
 * 后者的位置来源写死在 {@code PointerState.lastBotPoint()} 上，而那个值只有脚本移动过
 * 鼠标之后才会存在，所以客户端启动后、脚本没跑的时候看不到准星。
 * 更重要的是，{@code MicrobotMouseOverlay} 的 else 分支每帧都会清空
 * {@code Microbot.getMouse()} 的轨迹数据，即使它自己不画准星也一样，
 * 因此轨迹不能共用同一份存储，只能各画各的。
 */
@Slf4j
public class BotMouseOverlay extends Overlay
{
	/**
	 * 白色轮廓圆的直径（像素）。
	 */
	private static final int FIXED_CIRCLE_SIZE = 16;

	/**
	 * 轮廓圆的颜色。
	 */
	private static final Color COLOR_CIRCLE_STROKE = Color.WHITE;

	/**
	 * 轮廓圆的线宽。
	 */
	private static final float CIRCLE_STROKE_WIDTH = 1.5f;

	/**
	 * 风扇叶片数量。
	 */
	private static final int FAN_BLADE_COUNT = 3;

	/**
	 * 风扇转速。
	 *
	 * <p>实际转速是 {@code FAN_ROTATION_SPEED * 60} 度/秒，也就是 480 度/秒。
	 */
	private static final float FAN_ROTATION_SPEED = 8.0f;

	/**
	 * 风扇叶片的颜色。
	 */
	private static final Color FAN_BLADE_COLOR = new Color(255, 255, 255, 180);

	/**
	 * 移动轨迹的颜色。
	 */
	private static final Color COLOR_TRAIL = new Color(200, 200, 200, 120);

	/**
	 * 移动轨迹的线宽。
	 */
	private static final float TRAIL_STROKE_WIDTH = 2.0f;

	/**
	 * 轨迹最多保留的点数。
	 */
	private static final int MAX_TRAIL_POINTS = 64;

	/**
	 * 每个轨迹点的存活时间（毫秒）。
	 *
	 * <p>原版靠 {@code Mouse} 里的 {@code Timer(14ms)} 每 14 毫秒弹掉一个点来让轨迹收缩，
	 * 寿命取决于点数。这里改成给每个点记时间戳、到期就移除，寿命是确定的。
	 * 鼠标停下之后，轨迹会在这个时间之内收缩消失。
	 */
	private static final long TRAIL_LIFETIME_MS = 200;

	/**
	 * 持有的插件实例，用来读「是否有脚本在运行」。
	 *
	 * <p>刻意不用构造器注入：BotMousePlugin 注入本 overlay，本 overlay 又要注入
	 * BotMousePlugin，会形成构造器层面的循环依赖。改成由插件在 startUp 时注入，
	 * 避免 Guice 需要代理才能解开这个环。
	 */
	private BotMousePlugin plugin;

	/**
	 * 自己的轨迹存储。不能复用 Microbot.getMouse() 的那份，理由见类注释。
	 */
	private final Deque<TimedPoint> trail = new ArrayDeque<>();

	/**
	 * 上一帧的准星位置，用来判断是否产生了新的移动。
	 */
	private Point lastPosition;

	/**
	 * 上一帧的位置来源（true 表示来自 bot，false 表示来自真实鼠标）。
	 *
	 * <p>来源切换时要清空轨迹，否则会把真实鼠标的位置和 bot 的位置连成一条线。
	 */
	private Boolean lastSource;

	/**
	 * 上一次更新风扇角度的时刻。
	 */
	private long lastUpdateTime = System.currentTimeMillis();

	/**
	 * 风扇当前的旋转角度。
	 */
	private double fanRotationAngle = 0.0;

	public BotMouseOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		// 与 MicrobotMouseOverlay 一致：右键菜单打开时准星不能被盖住，
		// 因为真正的光标是画在菜单上面的。
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(Overlay.PRIORITY_LOW);
		setNaughty();
	}

	/**
	 * 由 {@link BotMousePlugin} 在 startUp 时调用，注入持有者。
	 */
	void setPlugin(BotMousePlugin plugin)
	{
		this.plugin = plugin;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		Point cursor = resolveCursor();
		if (cursor == null)
		{
			// 位置从「有」变「无」时要把时间基准重置掉，
			// 否则恢复显示时风扇会突然转过一大圈。
			lastUpdateTime = System.currentTimeMillis();
			trail.clear();
			lastPosition = null;
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		recordTrail(cursor);

		// 顺序与旧版一致：先轨迹，再更新角度，最后画圆圈和扇叶。
		drawMouseTrail(g);
		updateFanRotation();
		drawCoreElements(g, cursor);

		return null;
	}

	/**
	 * 决定准星这一帧画在哪里。
	 *
	 * @return 画布坐标；没有可用位置时返回 null
	 */
	private Point resolveCursor()
	{
		// 有 bot 点才跟 bot，否则一律跟真实鼠标。
		//
		// 之所以不只看「脚本在不在跑」：有些脚本在跑的时候并不会移动鼠标
		// （比如某些脚本在等条件满足），那种情况下 bot 位置并不存在，
		// 如果按脚本状态硬切，准星就会莫名其妙地消失。
		boolean fromBot = plugin != null
			&& plugin.isScriptRunning()
			&& PointerState.hasBotPoint();

		// 来源切换时清空轨迹，避免把真实鼠标的位置和 bot 的位置连成一条线。
		if (lastSource == null || lastSource != fromBot)
		{
			trail.clear();
			lastPosition = null;
			lastSource = fromBot;
		}

		if (fromBot)
		{
			// 脚本在跑且移动过鼠标：跟 bot 的目标位置，保持原有语义。
			return PointerState.lastBotPoint();
		}

		// 空闲：跟真实鼠标位置。
		// PointerState.get() 在真实鼠标移动时由 setFromReal 写入，
		// 脚本移动时由 setFromBot 写入，所以它始终是「当前有效的光标位置」。
		// 没有设置过时是 (-1, -1)，此时不画。
		Point real = PointerState.get();
		if (real.getX() < 0 || real.getY() < 0)
		{
			return null;
		}
		return real;
	}

	/**
	 * 位置变了就记一个点，用来画轨迹。
	 *
	 * <p>同时把过期的点清掉，这样鼠标停下来之后轨迹会自己收缩消失。
	 */
	private void recordTrail(Point cursor)
	{
		long now = System.nanoTime();

		if (lastPosition == null
			|| lastPosition.getX() != cursor.getX()
			|| lastPosition.getY() != cursor.getY())
		{
			trail.addLast(new TimedPoint(cursor, now));
			lastPosition = cursor;
		}

		// 移除已经过期的点
		long lifetimeNanos = TRAIL_LIFETIME_MS * 1_000_000L;
		while (!trail.isEmpty() && now - trail.peekFirst().getTimestampNanos() > lifetimeNanos)
		{
			trail.removeFirst();
		}

		// 兜底上限，避免高频移动时堆积过多点
		while (trail.size() > MAX_TRAIL_POINTS)
		{
			trail.removeFirst();
		}
	}

	/**
	 * 画移动轨迹。用中点法做二次贝塞尔，让线条平滑。
	 */
	private void drawMouseTrail(Graphics2D g)
	{
		if (trail.size() < 2)
		{
			return;
		}

		Point[] pointArray = trail.stream()
			.map(TimedPoint::getPoint)
			.toArray(Point[]::new);

		Point firstPoint = pointArray[0];
		Point lastPoint = pointArray[pointArray.length - 1];
		if (firstPoint == null || lastPoint == null)
		{
			return;
		}

		Path2D path = new Path2D.Double();
		path.moveTo(firstPoint.getX(), firstPoint.getY());

		for (int i = 1; i < pointArray.length - 2; i++)
		{
			Point pCurrent = pointArray[i];
			Point pNext = pointArray[i + 1];

			double midX = (pCurrent.getX() + pNext.getX()) / 2.0;
			double midY = (pCurrent.getY() + pNext.getY()) / 2.0;

			path.quadTo(pCurrent.getX(), pCurrent.getY(), midX, midY);
		}

		Point secondLast = pointArray[pointArray.length - 2];
		path.quadTo(secondLast.getX(), secondLast.getY(), lastPoint.getX(), lastPoint.getY());

		g.setColor(COLOR_TRAIL);
		g.setStroke(new BasicStroke(TRAIL_STROKE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(path);
	}

	/**
	 * 更新风扇的旋转角度。
	 *
	 * <p>按实际经过的时间累加，这样帧率变化时转速不会变。
	 */
	private void updateFanRotation()
	{
		long currentTime = System.currentTimeMillis();
		double elapsedSeconds = (currentTime - lastUpdateTime) / 1000.0;
		lastUpdateTime = currentTime;

		fanRotationAngle += FAN_ROTATION_SPEED * 60 * elapsedSeconds;
	}

	/**
	 * 画主体：白色轮廓圆 + 里面的风扇叶。
	 */
	private void drawCoreElements(Graphics2D g, Point mousePos)
	{
		int x = mousePos.getX();
		int y = mousePos.getY();
		int radius = FIXED_CIRCLE_SIZE / 2;

		// 16px 白色轮廓圆，只描边不填充
		g.setColor(COLOR_CIRCLE_STROKE);
		g.setStroke(new BasicStroke(CIRCLE_STROKE_WIDTH));
		g.draw(new Ellipse2D.Double(x - radius, y - radius, FIXED_CIRCLE_SIZE, FIXED_CIRCLE_SIZE));

		// 扇叶画在圆圈里面
		AffineTransform fanTransform = g.getTransform();
		g.translate(x, y);
		g.rotate(Math.toRadians(fanRotationAngle % 360));
		drawFanBlades(g, radius - 1);
		g.setTransform(fanTransform);
	}

	/**
	 * 画 3 片风扇叶。
	 */
	private void drawFanBlades(Graphics2D g, int bladeRadius)
	{
		g.setColor(FAN_BLADE_COLOR);
		g.setStroke(new BasicStroke(1.2f));

		double bladeAngleStep = 360.0 / FAN_BLADE_COUNT;
		for (int i = 0; i < FAN_BLADE_COUNT; i++)
		{
			AffineTransform bladeTransform = g.getTransform();
			g.rotate(Math.toRadians(i * bladeAngleStep));

			Path2D blade = new Path2D.Double();
			blade.moveTo(0, 0);
			blade.lineTo(bladeRadius, -1);
			blade.lineTo(bladeRadius, 1);
			blade.closePath();
			g.fill(blade);
			g.draw(blade);

			g.setTransform(bladeTransform);
		}

		// 中心的小圆点
		g.fill(new Ellipse2D.Double(-1, -1, 2, 2));
	}

	/**
	 * 带时间戳的轨迹点。
	 *
	 * <p>有了时间戳才能判断一个点是否过期，鼠标停下来之后轨迹才会收缩消失。
	 */
	@Getter
	private static class TimedPoint
	{
		private final Point point;
		private final long timestampNanos;

		private TimedPoint(Point point, long timestampNanos)
		{
			this.point = point;
			this.timestampNanos = timestampNanos;
		}
	}
}
