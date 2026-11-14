# Ideas

Features seen in other Rex clients or in Rex's API that Rhubarb doesn't have. Parked, not planned.
Collected 2026-11-14.

## Needs a newer Rex

- **Open a Mac dev server in the phone's browser** (port forwarding through Rex). Needs API
  revision 14. Rexling does it (Apache 2.0; see its `rexling-android/PROTOCOL.md`, "TCP forwarding").

## Small

- **Tap a file path in output to preview it** (image, PDF, text) using `download_begin`. Hatch (MIT,
  `FilePaths.kt`, `FilePreview.kt`).
- **Jump between commands; Copy command / Copy output** from OSC 133 prompt marks (Ghostty
  already tracks them). Rexling, rex-rune.
- **Insert a path or `cd`** by browsing the Mac with `terminal.list_dir`. rex-rune.
- **Reopen the last pane per host** on launch. Rexling.
- **Server page extras:** devices attached to each session (`session.list_clients`), a QR of the
  server address for pairing another phone, running time. rex-rune, Raptor.
- **Send the pane's colours to Rex** (`terminal.set_theme`) while the phone owns it. Rax, Rexling.
- **Diagnostics report** with redacted names, to attach to bug reports. RexPocket.
- **Update check** against GitHub releases for APK installs. RexPocket, Rax.
- **Reachability check** when adding a host. Hatch, Rexling.

## Medium

- **Remote control:** answer `client_perform_requested` with `client.reply`, opt-in per host and
  limited to navigation, so `rex` or an agent can open a pane on the phone. rex-rune.
- **Rex actions as a menu** via `client.keymap` and `client.action`. rexc, rex-rune.
- **More keys:** F1–F12, a hold-and-slide arrow pad, a Neovim shortcut grid. Rexling, Hatch.
- **Floating panes** (`session.float_block`, layers). Rexling.

## Large

- **Split panes**, side by side on tablets and foldables (`session.new_split`, `swap_blocks`,
  `move_block`). Hatch's version is the best (MIT, `PullToSplit.kt`).
- **Tailscale inside the app** (official tsnet; Headscale). Rexling, RexPocket.
- **Connect over SSH** (forward Rex's socket, start it if missing). Rexling, rex-rune.
- **Chat view of Claude Code sessions** from the transcript in `~/.claude/projects/`. Fetching whole
  files is heavy until Rex supports ranged downloads.
- **Quick Settings tile, app lock, launcher shortcuts** (Hatch has `Shortcuts.kt`), pane thumbnails,
  usage/cost per pane.
