# Installation

## Download A Release

Download the latest shaded release jar from https://github.com/chsami/microbot/releases.

Nightly builds are development builds. Use a release build unless you are intentionally testing new changes.

## Java

Install Java 17+ and run the shaded jar:

```bash
java -jar client-<version>-SNAPSHOT-shaded.jar
```

## Proxy

The client routes both the game connection and its HTTP traffic through a SOCKS5 proxy. Configure it at launch:

```bash
java -jar client-<version>-SNAPSHOT-shaded.jar --proxy-type socks5 --proxy-host 1.2.3.4 --proxy-port 1080 --proxy-user user --proxy-pass pass
```

| Option | Meaning |
|---|---|
| `--proxy-type` | `socks` or `socks5`. Defaults to `socks5` when omitted. HTTP(S) proxies are refused. |
| `--proxy-host` | Proxy host or IP. Required. |
| `--proxy-port` | Proxy port. Required. |
| `--proxy-user` | Optional username. |
| `--proxy-pass` | Optional password. |

These options do not go through URI parsing, so a password may contain `@`, `#`, `:` or `/` without URL encoding.

The older single-option form still works, and is used whenever `--proxy-host` is absent:

```bash
java -jar client-<version>-SNAPSHOT-shaded.jar --proxy=socks://user:pass@1.2.3.4:1080
```

A URI password that contains `@` or `#` must be URL-encoded.

On start the client fetches the external IP through the proxy. If it cannot be detected, startup stops instead of falling back to a direct connection, and the client shows `Proxy enabled (IP x.x.x.x)` in the Microbot panel.

A proxy is fixed for the lifetime of the process. The option is read at start, so changing it requires a restart.

## Jagex Session Login

The client can log in with a Jagex session id and character id directly, without the Jagex Launcher, the OAuth flow or the interactive character picker:

```bash
java -jar client-<version>-SNAPSHOT-shaded.jar --session-id <session id> --character-id <character id>
```

| Option | Meaning |
|---|---|
| `--session-id` | The Jagex session id. Required. |
| `--character-id` | The character id to log in with. |
| `--display-name` | The character name. Optional. |

The credentials are injected into the game client during startup, so the login screen shows that character's login button. The login itself is not submitted for you; press the button or Enter.

The character name is only needed by the client, not by the login. When `--display-name` is omitted, the character list is read from `https://auth.jagex.com/game-session/v1/accounts` with the session id and matched against the character id, which costs one request at startup. Passing the display name skips that request. If the character id does not match, the client logs the characters the account does have.

`--character-id` is not the same as the upstream `--character` option. `--character` matches a character name against the sessions stored on this machine, while `--session-id` uses the credentials given on the command line. When both are given, `--session-id` wins.

A session id is a short lived credential. Microbot does not store the refresh token, so an expired session id cannot be renewed and has to be obtained again.

The session id appears in the process command line. On a shared machine, other local users can read it from the process list, so treat it like a password.

## Window Title

The client window title can be overridden at launch. This is useful when several clients run side by side and you need to tell them apart:

```bash
java -jar client-<version>-SNAPSHOT-shaded.jar --index 12
```

| Option | Meaning |
|---|---|
| `--index` | The final window title. Optional, must have a value. |

The value is used as-is: no template substitution, no index mapping, no truncation. `Account 1` becomes the title `Account 1`.

The title is applied once the game engine has started, not when the window first appears, so a failed game load does not leave a misleading title. If the engine does not start within 60 seconds, the option is dropped and the default title is kept.

While the override is in effect the title is no longer combined with the player name, and changing any client setting does not reset it.

**Known issue:** Microbot's break handler captures the current window title when it starts and puts its own suffix on it while resting. Depending on the timing it may end up as `12 - Break: 00:30`, and the title may fall back to the default when the break ends.

## Jagex Account Flow

1. Log in with the Jagex Launcher once. This creates the account token used by RuneLite-compatible clients.
2. Close the launcher/client after a successful login.
3. Open the Microbot shaded jar. It should prompt with the Jagex account login flow.

Video walkthroughs:
- Jagex account setup: https://www.youtube.com/watch?v=ga-lg1oAnhM
- Java/client launch: https://www.youtube.com/watch?v=EbtdZnxq5iw

## Jagex Launcher Replacement

Replace the official `RuneLite.jar` with the Microbot jar and keep the filename expected by the launcher. Then start the Jagex Launcher and select RuneLite.

## Linux

- Install Java 17+ from your package manager, then run the shaded jar with `java -jar`.
- For Jagex accounts, Bolt can launch a custom RuneLite jar: https://github.com/Adamcake/Bolt
- You can also extract the RuneLite AppImage and replace `RuneLite.jar` with the Microbot jar.

## Reporting A Problem

Click the bug icon in the client's right-hand toolbar and choose **Report a problem...**, or use the bug icon in a plugin's settings header. Describe what went wrong, check what will be sent, and click **Send**. Microbot tells you when the report is fixed or when the team has a question; follow your reports under **My reports** in the same menu.

The report always includes the Microbot/RuneLite versions, commit, build origin, Java/OS, safe mode, GPU state, walker planner and settings, and loaded external plugin versions. You can also include the recent log and errors, the plugin's settings, the game state and a screenshot. Account and player names found in the text, chat, credentials/tokens, and file paths are removed; a screenshot is sent as it is.

To paste details into Discord instead, choose **Copy client details (for Discord)**.

## Recovering From A Freeze Or Crash

You do not need to rename or delete `.runelite/microbot-plugins`.

- **After a freeze, crash or force-close:** the next start shows a recovery prompt. It lists what Microbot recorded, such as a plugin that was still starting, plugin start or compatibility failures with their versions, and the external plugins that were running. If nothing was recorded, the prompt says the cause is unknown. You can:
  - **Start normally.** This also happens automatically after 20 seconds.
  - **Start in safe mode.** External Hub and sideloaded plugins and the GPU plugin stay off for this one session.
  - **Disable \<plugin\> and start.** This option appears when a plugin was identified. It turns that plugin off in the plugin list and starts with your other plugins.
  - **Copy details** to share in a bug report.
- **While the client still responds:** click the bug icon in the right-hand toolbar and choose **Safe mode next start**, then restart. Choose **Cancel safe mode next start** in the same menu to cancel.
- **From the command line:** `--safe-mode` turns on safe mode for every start that uses the option. Unlike the one-time options above, it also switches the GPU plugin off in your settings.

The one-time safe mode keeps your plugin files and settings, including GPU, and the following start is normal. A plugin disabled from the prompt keeps its files; turn it back on in the plugin list. Set `-Dmicrobot.recovery.prompt=false` to skip the prompt, or `-Dmicrobot.recovery.promptSeconds=<n>` to change the countdown.

**Are you stuck? Join our [Discord](https://discord.gg/zaGrfqFEWE) server.**
