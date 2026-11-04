package net.runelite.client.plugins.microbot;

import javax.inject.Inject;
import javax.inject.Singleton;

import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

import java.awt.image.BufferedImage;

/**
 * 右上角的「Enable Input / Disable Input」开关。
 *
 * <p>禁用输入时游戏画布收不到用户的鼠标键盘，客户端自己的界面
 * （工具栏、侧边栏）照常可用 —— 否则这个开关自己就点不回来了。
 */
// ===================== [MICROBOT 本地改动] 禁用输入开关可注入 =====================
// 上游原本是个一次性对象：MicrobotPlugin 里 `new InputSelector(clientToolbar);`
// 创建完就把引用丢了，构造里直接调 startUp() 把按钮挂到工具栏上。
// 外部拿不到它，就没法在别的时机（比如脚本启停）去开关「禁用输入」。
//
// 本地改成 @Singleton + @Inject 的可注入单例，startUp() 由调用方显式调用。
// 行为和上游完全一致 —— 右上角那两个按钮的图标、tooltip、点击效果都没动。
//
// 冲突时如何覆盖：保留 @Singleton、@Inject 构造、显式的 startUp() 调用这三处。
// 上游若也把本类改成可注入，保留上游的注入方式，本地只保留 startUp() 的拆分。
// ===================== [MICROBOT 本地改动 END] =====================
@Singleton
public class InputSelector {

    private static final BufferedImage ENABLED_IMAGE, DISABLED_IMAGE;

    static {
        ENABLED_IMAGE = ImageUtil.loadImageResource(Microbot.class, "enabled_small.png");
        DISABLED_IMAGE = ImageUtil.loadImageResource(Microbot.class, "disabled_small.png");
    }

    private final ClientToolbar clientToolbar;
    private NavigationButton enableButton;
    private NavigationButton disableButton;

    // ===================== [MICROBOT 本地改动] 禁用输入开关可注入 =====================
    // 上游原本是普通构造：创建即 startUp()，往工具栏上加按钮。
    // 改成 @Inject 构造且什么都不做，startUp() 交给调用方显式调。
    //
    // 为什么不能留在构造里：Guice 创建实例的时机不保证在 EDT 上，
    // 而往工具栏上加按钮要在 EDT 上做。
    //
    // 冲突时如何覆盖：保留 @Inject 注解和这个「构造里不做事」的约定，
    // 上游若改回构造即 startUp，本地要跟着把 startUp() 的调用去掉。
    // ===================== [MICROBOT 本地改动 END] =====================
    @Inject
    public InputSelector(ClientToolbar clientToolbar) {
        this.clientToolbar = clientToolbar;
    }

    public void startUp() {
        enableButton = NavigationButton.builder().tab(false).icon(ENABLED_IMAGE).tooltip("Enable Input").onClick(this::enableClick).build();
        disableButton = NavigationButton.builder().tab(false).icon(DISABLED_IMAGE).tooltip("Disable Input").onClick(this::disableClick).build();
        addAndRemoveButtons();
    }

    private void addAndRemoveButtons() {
        // ===================== [MICROBOT 本地改动] 禁用输入开关可注入 =====================
        // 上游原本直接 remove/add 三个按钮，没有保护。
        // 本地加一个空值检查：startUp() 可能还没被调用过（Guice 先创建了实例，
        // 但调用方还没来得及 startUp），此时两个按钮都是 null，
        // 往上传 null 会出问题。输入该禁还是禁，只是按钮图标晚点再切。
        //
        // 冲突时如何覆盖：保留这个 if 块。
        // ===================== [MICROBOT 本地改动 END] =====================
        if (enableButton == null || disableButton == null) {
            return;
        }
        clientToolbar.removeNavigation(enableButton);
        clientToolbar.removeNavigation(disableButton);
        clientToolbar.addNavigation(!ClientUI.getClient().isEnabled() ? enableButton : disableButton);
    }

    public void enableClick() {
        ClientUI.getClient().setEnabled(true);
        Microbot.getClient().getCanvas().setFocusable(true);
        addAndRemoveButtons();
    }

    public void disableClick() {
        ClientUI.getClient().setEnabled(false);
        Microbot.getClient().getCanvas().setFocusable(false);
        addAndRemoveButtons();
    }

    // ===================== [MICROBOT 本地改动] 禁用输入开关可注入 =====================
    // 本地新增：给外部（工具栏按钮组）用的一句式开关。
    //
    // 走的是和用户手动点那两个按钮完全一样的路径，所以行为一致，
    // 右上角的按钮图标也会跟着切换。
    //
    // 冲突时如何覆盖：保留整个方法。
    // ===================== [MICROBOT 本地改动 END] =====================
    public void setInputDisabled(boolean disabled) {
        if (disabled) {
            disableClick();
        } else {
            enableClick();
        }
    }
}
