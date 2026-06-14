# Termtastic

Termtastic is a terminal replacement suitable for the modern age of agentic software development.

See: https://termtastic.soderbjorn.se/

## Features

### Comprehensive UI

* [Electron](https://www.electronjs.org/)-based app intended for use on macOS.
* Flexible tab and pane management system to organise your windows, including support for mirroring window content in other windows.
* Hideable overview pane to observe running windows.
* Customisable font sizes and color schemes, and support for dark mode.

### Agent Support

* Status detection highlights panes and tabs where agents work or wait for input — a red glow or dot means input is needed, while a blue glow or dot means an agent is working.
* Notifications when an agent is done or waiting for input.
* Optionally shows Claude Code usage data. Green color means lots of tokens remain, yellow means we are approching session limit and red means session limit is very near.

### Multiplexing

* An embedded server implements session management and multiplexing functionality (similar to [tmux](https://github.com/tmux/tmux) or [Zellij](https://zellij.dev/)), allowing sessions to survive even if the UI dies.
* If the server dies (because your computer is restarted), the ring buffer from each terminal window is also restored so you can see what you were doing in every terminal window.
* Optional support for accepting connections from other devices (which must be explicitly authorised).

### Web & Mobile Apps

* Full support for running the app in a web browser (local or remote).
* Android and iOS apps mirror most of the functionality but in limited form, allowing you to observe and type in terminal windows and operate the file and change viewers in a rudimentary way.

### Bonus Features

* Built-in file viewer showing the folders and files of your project and their contents. Supports filters, sorting, syntax highlighting for popular file types, and custom display of Markdown documents.
* Built-in Git diff viewer shows the changes to your files that have not yet been committed. Optionally shows a two-pane layout, and optionally uses graphical display inspired by [Helix P4Merge](https://www.perforce.com/products/helix-merge).

## Build & Run

### Electron (macOS)

Inside the project root folder, run:

```sh
./gradlew clean electron:dist
```

You will find the built binary in `electron/dist/mac-arm64` and can move it to your Applications folder.

You can use the hotkey <kbd>Ctrl</kbd>+<kbd>Option</kbd>+<kbd>Cmd</kbd>+<kbd>Space</kbd> to open it if it's running.

The server is embedded into the Electron app and starts automatically if not already running.

### Android

Load the project root folder into [Android Studio](https://developer.android.com/studio) and run it on your device.

### iOS

Load the `iosApp` folder into [Xcode](https://developer.apple.com/xcode/) and run it on your device. You need to have the appropriate development credentials (e.g. a development team) to install on a physical device.

## Architecture

* Everything except third-party dependencies and iOS UI code is written in [Kotlin](https://kotlinlang.org/).
* [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) ensures code sharing between the server and clients, and between the clients themselves.
* [SQLDelight](https://cashapp.github.io/sqldelight/) is used by the server for persistence and ring buffer scrollback storage.
* WebSockets are used for communication between the server and clients.
* Each client creates a persisted random unique token on first use, which is passed to the server which shows a dialog asking the user to approve or deny.
* The primary UI is web-based, using Kotlin's DOM API to render content.
* Mobile apps use native UIs ([Jetpack Compose](https://developer.android.com/jetpack/compose) and [SwiftUI](https://developer.apple.com/swiftui/)).

### Dependencies

| Component | Library | Purpose |
|-----------|---------|---------|
| Server | [pty4j](https://github.com/JetBrains/pty4j) | Shell process management |
| Server | [JediTerm](https://github.com/JetBrains/jediterm) | Headless terminal emulation |
| Web | [xterm.js](https://xtermjs.org/) | Terminal rendering |
| Android | [Termux](https://termux.dev/) | Terminal emulation (vendored code) |
| iOS | [SwiftTerm](https://github.com/migueldeicaza/SwiftTerm) | Terminal emulation |

An assortment of other more conventional dependencies are also used.

## License

Termtastic is released under the [MIT License](LICENSE).

Third-party dependencies and vendored code are used under their respective licenses (Apache 2.0, LGPL 3.0, OFL 1.1, MIT, BSD).
