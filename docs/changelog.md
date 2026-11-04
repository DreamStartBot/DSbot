# Changelog

New entries go on top. Record the exact change date and keep the summary short; details belong in the owning doc.

## 2026-11-03

### Added: log lines are colored by their level

- Every line in the log panel used to be the same terminal green, so there was no way to tell an error from an ordinary message.
- Lines are now colored by level: ERROR in red, WARN in yellow, INFO in green (the existing terminal green), DEBUG and TRACE in dark grey.
- Output that has no level of its own — a `System.out.println` from a script — is shown in green, the same as INFO.
- The panel was switched from a `JTextArea` to a `JTextPane`, because a `JTextArea` can only have one foreground color for the whole component. The line wrapping behaviour is unchanged.
- The log level is now passed along with the log text, so the panel knows which color to use.

### Fixed: the window stayed tall after hiding the console

- Clicking "Hide console" removed the log panel but left the window at its taller size, and the game canvas was stretched by the extra 160px so the picture looked distorted.
- The cause was in the window layout: the height is computed as `Math.max(content height, client minimum height)`, and the content height only ever grew. Showing the console pushed the window up by 160px and hiding it never brought that height back down. Upstream had no code to restore the size and no field to remember it.
- Hiding the console now shrinks the window back to the height it had before the console was shown. The width is left alone.
- The layout that runs after a Swing repaint is asynchronous, so it could push the window back up again. The window is therefore restored twice: once directly, and once after the layout has run.
- The canvas gets its full height back, so it is no longer stretched.
- The window is not resized while it is maximized, but the content height is still corrected so the canvas does not distort.

### Changed: the log panel shows less by default

- Every log line used to read `14:18:37 INFO  n.r.c.p.m.t.ToolbarButtonsPlugin - ToolbarButtons: 已恢复用户输入`. The level and the abbreviated class name made the lines hard to scan.
- The log panel now shows only the time and the message. The level and the class name are optional.
- Two checkboxes in the right-click menu of the log panel turn them back on: "Show log level" and "Show logger name".
- The state is remembered between restarts.
- Turning a switch on only affects the lines logged after that point. Lines already in the panel are left alone.
- This only affects the log panel. The log file in `~/.runelite/logs/` and the console output keep their own format, which comes from `logback.xml`.

### Fixed: the client UI test was broken and did not run

- `ClientUITest` looked up a `ClientUI` constructor that no longer exists, so every test in the class failed with `NoSuchMethodException` instead of testing anything. It now passes the extra constructor argument.

### Added: a plugin that sets how fast the mouse travels

- A new "Mouse Speed" plugin puts a 1 to 100 slider on the mouse travel speed. 1 is the slowest, 100 is the fastest.
- The slider only changes how long the mouse takes to reach its target. It does not change the shape of the trajectory, and it does not change the antiban strength.
- Upstream bound the travel time and the trajectory shape to the same antiban intensity level, and that level also decides how aggressive the antiban is. Adjusting the mouse on its own was not possible before this plugin.
- The slider maps to the base travel time on a logarithmic scale: 1 is 1000ms, 50 is 90ms, 100 is 50ms. The base time is not the actual time — the actual time is a random value between the base time and twice the base time.
- 50 maps to 90ms, which is exactly what the default antiban level uses. Leaving the slider at its default therefore behaves the same as not having the plugin at all.
- "Follow antiban intensity" is on by default and makes the mouse follow the antiban level. Turning it off hands the control to the slider.
- The setting applies immediately, without a restart.
- Scripts can read and write the speed through `MouseSpeed` without touching the plugin panel: `setSpeed(int)`, `getSpeed()`, `setFollowAntibanIntensity(boolean)`, `isFollowAntibanIntensity()`, `getBaseTimeMs()`, `toBaseTimeMs(int)` and `reset()`.
- `MouseSpeed.setSpeed()` means "I want this speed", so it also turns off the follow mode. Without that, the slider would be ignored while the follow mode is on.
- The script API writes back to the configuration, so the slider in the panel always shows what the scripts actually use.
- The script API also works when the plugin is not enabled. The speed then only applies for the session and is not persisted.
- Details are in `docs/LOCAL_PATCHES.md`, section 14.

## 2026-10-31

### Added: `--script` starts a side-load script from the command line

- A new `--script` command line argument starts a script by name while the client boots: `--script "Auto Banker"`.
- Only scripts loaded from the side-load directory are considered. The plugins that ship with the client are not.
- The name is matched exactly and case insensitively. Both the plain display name and the raw `@PluginDescriptor` name are accepted, so the HTML prefix that scripts usually carry does not have to be typed out.
- Nothing is started when no script matches, and nothing is started either when several scripts share the name. Both cases only log an error and the client keeps running.
- The script is not written to the configuration. It runs for that session only, and the next client start does not pick it up again.
- The script starts as soon as the side-load jars are loaded, without waiting for the login.
- Passing `--script` more than once starts the last value and logs a warning.

## 2026-11-02

### Added: the user input is locked while a script runs

- The user input is now locked whenever an external script is running, so the mouse and the keyboard no longer reach the game canvas while the script is in control.
- The lock reuses the existing "Disable Input" switch in the top right corner instead of a separate interception layer. `InputSelector` became a `@Singleton` and is now injected instead of being created and dropped, so `MicrobotPlugin` and `ToolbarButtonsPlugin` share one instance.
- The input is released when the script stops and when it is paused, and locked again when the script is resumed from a pause.
- Scripts that start on their own count too, not only the ones started from the launcher dialog.
- A manual unlock is not overwritten. The plugin only touches the switch when its own intent changes, so the 500ms state refresh does not undo what the user did by hand.
- The toolbar stays clickable while the input is locked, so Pause and Stop are always reachable.

## 2026-11-01

### Changed: the script launcher dialog closes after starting

- Clicking Start in the script launcher dialog now closes the dialog once the script has started.
- A failed start keeps the dialog open so the reason stays visible.

### Added: the Restart button reloads the jars and restarts the script
- The Restart button now reloads every jar in the side-load directory and starts the script that was started last.
- Reloading goes through `MicrobotPluginManager.reloadSideLoadPlugins()`, which stops and unloads all side-load scripts and then loads the directory again. Stopping the running script is part of that reload, not a separate step, because the jar cannot be loaded while the old class loader still holds it.
- The reloaded plugin is a brand new instance, so it is looked up again by name instead of reusing the old reference.
- The script is identified by its fully qualified class name and by the internal name of the jar it came from. The class name separates scripts that share a simple name, the jar name separates scripts that share a fully qualified class name but live in different jars.
- `MicrobotPluginManager.findSideLoadJarName(Plugin)` was added for this. The jar internal name survives a reload, while the class loader does not.
- The last started script is remembered whenever a script is started from the launcher dialog. When nothing was remembered but a script is running, that script becomes the restart target.
- When no script with the same name can be found, for example after renaming the class or replacing the jar, nothing is started and the launcher dialog opens instead.
- All four buttons are disabled while the restart is in progress and are re-enabled when it finishes.

### Added: the Pause button pauses the running scripts

- The Pause button now freezes the running scripts. Its icon switches to a double triangle and its tooltip to `Resume`, and clicking it again resumes.
- Both microbot pause flags are set through `PluginPauseEvent.setPausedAll()`: `Microbot.pauseAllScripts`, which the `Script.run()` guard reads, and `PluginPauseEvent.isPaused`, which `BlockingEventManager` registers globally.
- The pause only takes effect for scripts that extend microbot's `Script` or `StateMachineScript`.
- The toolbar keeps its own user-pause flag instead of reading the global one. Antiban, the break handler, the cannon helper and the inventory setup all toggle that global flag temporarily, and following it directly would make the button flicker.
- The pause is restored by the existing 500ms timer when something else clears the global flag, for example when the antiban plugin finishes and resets `pauseAllScripts`.
- Stopping a script clears the pause flag, so the next script does not start out paused.

### Added: the Stop button stops the running scripts

- The Stop button now stops every running external script.
- Only plugins with `isExternal = true` are stopped. Built-in client plugins are left alone because they are shared with other plugins.
- The plugin is disabled in the config before it is stopped, so it does not get started again automatically on the next client launch.
- Stopping goes through `PluginManager.stopPlugin()`, which runs the plugin's `shutDown()`, unregisters it from the event bus and posts `PluginChanged`, which is what drives the button state.
- Stopping does not unload the plugin. It stays in the plugin manager and can still be picked in the launcher dialog. The next press of Start does a full reload, which unloads it and loads the jar again.
- The button state is refreshed even when there was nothing to stop, so a script that was stopped elsewhere is picked up by pressing Stop.

### Fixed: toolbar buttons did not react to script start and stop

- The toolbar buttons stayed on the "Start only" state after a script was started, so Pause, Stop and Restart never showed up.
- The cause was the wrong `@Subscribe` annotation. RuneLite has its own `net.runelite.client.eventbus.Subscribe`, and its `EventBus` ignores methods annotated with the Guava one. `eventBus.register()` silently skipped the method, so no `PluginChanged` event ever reached the plugin.
- The plugin now uses the RuneLite annotation. Its subscriber method is named `onPluginChanged`, which the RuneLite `EventBus` requires (`on` + event class name).
- Button state is now refreshed from three places: the `PluginChanged` event, the startup callback from the launcher dialog, and the existing 500ms timer as a fallback.
- Button visibility changes now force a layout pass, so the buttons are painted immediately instead of waiting for the next repaint.
- A log line is written whenever the run state flips, to make this class of problem diagnosable.

### Added: toolbar buttons follow the script state

- The four toolbar buttons now hide and show depending on whether a script is running.
- Three states: nothing has run yet shows only Start; a running script shows Pause, Stop and Restart; a script that ran and then stopped shows Start and Restart.
- "A script is running" means `PluginManager.getActivePlugins()` contains a plugin with `isExternal = true`. Built-in client plugins do not count.
- "Has run" is tracked in memory only and is not written to the config, so restarting the client starts from a clean state again.
- State changes are driven by the `PluginChanged` event that `PluginManager` posts when a plugin starts or stops, so no polling is needed. The state is also checked once when the buttons are installed.
- Note that side-loaded scripts are enabled by default and `microbotPluginManager.loadSideLoadPlugins()` runs before `pluginManager.startPlugins()`, so those scripts are already running when the client starts.

### Added: Restart button in the toolbar

- The Toolbar Buttons plugin now shows a fourth button, `Restart`, next to Start, Pause and Stop.
- The icon is a broken circle with an arrow head, drawn in code like the other three so the style stays consistent.
- The button is a placeholder only. It has no action listener yet, so clicking it does nothing. The behaviour will be added separately.

### Added: richer script list in the launcher dialog

- The script launcher dialog now renders each script as a text card instead of a single line of text.
- A card shows the script name, its description, and a meta line with version, authors and tags. Everything comes from the script's own `@PluginDescriptor` annotation.
- Fields that were not filled in are left out rather than shown blank. The default author value `Unknown` is treated as "not filled in" and is not shown.
- Descriptions have their tags stripped, newlines collapsed to spaces, and are truncated by display width, where full-width characters count as two.
- The list is still a `JList`, only the cell renderer changed, so keyboard selection and the new Enter-to-start shortcut both work. Selected cards get a brand-coloured bar on the left.
- No icons and no run-state badges: there is no reliable source for those on locally packaged scripts.

### Fixed: loaded script jars stayed locked

- A loaded script jar stayed locked for as long as the client was running, so it could not be overwritten on Windows. `URLClassLoader` holds a file handle on the jar for the whole lifetime of the class loader.
- Scripts are now loaded from a runtime copy instead of the original file. The original jar is copied to `~/.runelite/microbot-plugins-runtime/` and the copy is what gets loaded, so the original is released as soon as the copy is made and can be overwritten at any time.
- A copy is used rather than closing the class loader right after loading, because closing it would break loading of classes that are resolved lazily at runtime (bundled dependencies) and reading of resources from the jar.
- The copy is removed when the script is unloaded, and the whole runtime directory is cleared on client shutdown.

### Fixed: side-load script reload dropped or duplicated plugins

- Reloading the side-load script jars could leave the plugin list in a wrong state: scripts disappearing from the default plugin list and the launcher dialog showing fewer scripts than were actually loaded.
- Root cause was three identifiers being mixed up in `MicrobotPluginManager`: the jar file name (the `internalName`), the plugin class name, and the jar's class loader. The rest of microbot assumes the jar file name equals the plugin class name, which does not hold when a single jar contains more than one script.
- Script ownership is now decided by class loader alone (`plugin.getClass().getClassLoader() == loader`), so every script in a jar is unloaded together and a same-named plugin from a different class loader is still left alone.
- "Has this jar already been loaded?" is now answered by `loaders.containsKey(internalName)` instead of comparing the jar name against the set of loaded class names, which prevented the jar from being loaded twice.
- The duplicate check in `loadPlugins()` now compares fully qualified class names instead of `Class` objects. A reload creates a new class loader, so the same class yields a new `Class` object and the old check could not stop duplicates.
- The reload path now logs the loaded external plugins before and after, how many plugins each jar unloaded, and the load result per jar, so a count mismatch can be diagnosed from the log.
- See [toolbar-buttons.md](toolbar-buttons.md).

### Added: Toolbar Buttons plugin

- New `microbot Toolbar Buttons` plugin is enabled by default. It adds a group of three buttons to the top right toolbar, in a single container at the far left: Start (triangle), Pause (two bars) and Stop (square).
- Clicking Start fully reloads the side-load script jars before showing the script list: every script loaded from a jar is stopped, its class loader is closed, and the folder is scanned and loaded again. This picks up newly installed scripts, and it interrupts any script that was running.
- The reload runs on a background thread while the Start button is greyed out, so the UI stays responsive. Once it finishes, a modal dialog lists every script loaded from a jar (`isExternal = true`, not hidden). Selecting one and pressing Start runs it through `Microbot.startPlugin`, which enables the plugin, starts it, and brings up its dependencies.
- The dialog stays open after a launch attempt and shows the result, so it is clear whether the script started or was already running.
- The icons are drawn in code as 16px white glyphs, so no image resources are needed and the three stay visually consistent.
- Pause and Stop have no behaviour yet.
- The plugin is separate from `Minimal Toolbar` and does not interfere with it. The buttons live in a custom container rather than going through `ClientToolbar.addNavigation()`, because that would put them in the internal table that `Minimal Toolbar` scans and hide them again.
- `MicrobotPluginManager` gained `reloadSideLoadPlugins()` and `unloadSideLoadPlugins()` for this. A script is only unloaded when its name matches *and* it was loaded by that jar's class loader, so a same-named plugin from a different class loader is left alone.
- See [toolbar-buttons.md](toolbar-buttons.md).

### Added: Minimal Toolbar plugin

- New `microbot Minimal Toolbar` plugin is enabled by default. It hides the toolbar icons in the top right of the window, keeping only the input toggle (`Disable Input` / `Enable Input`), the console toggle (`Show console` / `Hide console`) and the sidebar toggle (`Close sidebar`).
- The plugin icon column on the left is not affected.
- Disabling the plugin restores every hidden button.
- See [minimal-toolbar.md](minimal-toolbar.md).

## 2026-10-31

### Added: Bot Mouse plugin

- New `microbot Bot Mouse` plugin is enabled by default. It draws a fan-style cursor (white outline circle with three rotating blades, plus a grey movement trail) and follows the real mouse whenever there is no bot position to follow, so the cursor is visible once the game is loaded instead of only appearing once a script moves the mouse.
- The cursor follows the bot position while a script is running and has moved the mouse, keeping the existing behaviour.
- The trail fades out on its own: each point expires after 200 ms instead of staying on screen forever.
- The plugin turns off the DevTools panel's `Bot Mouse` button so the two crosshairs do not overlap, and restores it when the plugin is disabled.
- The plugin does not modify `MicrobotMouseOverlay`; it draws its own overlay. See [bot-mouse.md](bot-mouse.md).

### Fixed: Bot Mouse plugin could not be restarted

- The plugin's scheduled executor was a `final` field created once in the constructor, while `shutDown()` terminated it. Since the plugin instance is reused after being disabled, starting it again submitted a task to an already-terminated executor and failed with `RejectedExecutionException`. The executor is now created in `startUp()` and destroyed in `shutDown()`.
- `Microbot.getPlugin()` is now called defensively, since it depends on the statically injected `PluginManager` which can be null before the client is fully assembled.
- Added `BotMousePluginLifecycleTest` covering repeated start/stop cycles.

### Fixed: `PluginManagerTest` no longer compiles

- `PluginManagerTest` constructed a `RuneLiteModule` with the upstream argument list, so it stopped compiling when the module gained local fields. The argument list is now kept in sync and documented inline.

### Added: `--index` custom window title

- New `--index <value>` launch option sets the client window title to the given string, as-is. It is meant for telling several clients apart when running them side by side.
- The title is applied once the game engine has started, not when the window first appears, so a failed game load does not leave a misleading title. If the engine does not start within 60 seconds the option is dropped and the default title is kept.
- While the override is in effect the title is no longer combined with the player name, and changing a client setting no longer resets it.
- Known issue: Microbot's break handler captures the window title when it starts and puts its own suffix on it while resting, so the title may end up as `12 - Break: 00:30` and fall back to the default when the break ends.
- Usage: [installation.md](installation.md#window-title).

### Added: Performance plugin

- New `microbot Performance` plugin is enabled by default. It enables the upstream `FPS Control` and `Low Detail` plugins, and mutes the game sounds.
- Only the upstream plugins get enabled. Their own settings are left alone, so the FPS limit switch and the target frame rate are still configured in the FPS Control panel.
- Muting is done through the client interface and the sound events, not by driving the in-game settings interface: the music volume is set to zero, sound effects and area sounds are consumed at the event level, and ambient sounds are cleared. This avoids the hardcoded widget coordinates that `Rs2Settings.turnOffMusic()` depends on, so it does not break after a game update and does not need the game to be logged in.
- Disabling the plugin restores the music volume. Sound effects and area sounds are restored automatically because they are implemented by consuming events.
- See [performance-plugin.md](performance-plugin.md).

### Removed: Microbot FPS Limit plugin

- The Microbot-specific FPS Limit plugin, its draw listener and the `Rs2Fps` script API have been removed. Frame rate limiting now uses the upstream `FPS Control` plugin, configured from its panel.
- `Script.shutdown()` no longer resets an FPS limit. The upstream plugin stores its settings in the profile configuration, so they persist across restarts and apply to every script.
- See [fps-control.md](fps-control.md) for the enable steps and how it interacts with the in-game FPS setting.

### Added: local patch inventory for upstream updates

- New `docs/LOCAL_PATCHES.md` lists every file that differs from upstream RuneLite, with the exact location, the change and how to resolve it when an upstream merge conflicts.
- Every local change is wrapped in a `[MICROBOT 本地改动]` comment marker with Chinese notes describing what upstream did, what changed locally and how to keep the local behaviour. Search for that marker to list all change points.
- `rg -n "MICROBOT 本地改动" runelite-client/` lists them from the command line.

### Added: runtime FPS limit for scripts

- New `Rs2Fps.setMaxFps(int)` and `Rs2Fps.getMaxFps()` let a script limit how many frames the client draws per second. The limit is runtime state only and is never written to a config file.
- `Script.shutdown()` removes the limit, so a limit never outlives the script that set it.
- Backed by the new `microbot FPS Limit` plugin, which is enabled by default and does nothing until a limit is set.
- Independent of the in-game "Game FPS Limit" setting and of the GPU plugin's "Unlock FPS" option. The effective frame rate is the strictest of the three.
- Usage: [api/Rs2Fps.md](api/Rs2Fps.md).
- **Superseded the same day**: this was replaced by the upstream `FPS Control` plugin, see the removal note above.

### Added: Jagex session login launch options

- New launch options inject a Jagex account into the game client without the Jagex Launcher, the OAuth flow or the interactive character picker: `--session-id`, `--character-id` and the optional `--display-name`.
- Once injected, the login screen shows that character's login button. Nothing is submitted automatically, so the login is still confirmed by hand.
- The character name is only needed by the client, not by the login itself. When `--display-name` is omitted, the character list is read from `https://auth.jagex.com/game-session/v1/accounts` with the session id and matched against the character id. Passing the display name avoids that request entirely.
- `--character-id` is distinct from the upstream `--character` option, which matches a character name against the sessions stored on the machine. When both are given, `--session-id` wins.
- `net.runelite.client.rs.auth.JagexSessionInjector` holds the logic and is shared by the normal and the debug startup path.
- A session id is a short lived credential. Microbot does not keep the refresh token, so an expired session id cannot be renewed and has to be obtained again. The client logs the available characters when the character id does not match.
- Usage: [installation.md](installation.md#jagex-session-login).

### Added: split proxy launch options

- New command-line options configure the SOCKS5 proxy without URI syntax: `--proxy-type`, `--proxy-host`, `--proxy-port`, `--proxy-user`, `--proxy-pass`.
- These options bypass URI parsing, so a proxy password may contain `@`, `#`, `:` or `/` without URL encoding.
- The existing single-option form `--proxy=<scheme://user:pass@host:port>` is unchanged and still works. `--proxy-host` takes precedence when both forms are supplied.
- `--proxy-type` is now a registered option instead of a rejected legacy one. It accepts `socks`/`socks5` only; HTTP(S) proxies are still refused so that game traffic stays on SOCKS.
- The external IP check and the `Proxy enabled (IP x.x.x.x)` status now also run in split mode, so a broken proxy still aborts startup instead of silently connecting directly.
- Usage: [installation.md](installation.md#proxy).

### Fixed: proxy was ignored by the debug launcher

- `RuneLiteDebug` parsed `--proxy` but never called `ProxyConfiguration.setupProxy`, so the proxy never took effect on that startup path. The call is now in place, before the HTTP client is built.

### Fixed: broken IDE run configuration

- `.run/Runelite with proxy.run.xml` used `-proxy=<host>:<port>` without a scheme plus the removed `-proxy-type=http` flag, which could only fail at startup. It now uses the split options.
