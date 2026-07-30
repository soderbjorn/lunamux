# Lunamux

Lunamux is a terminal replacement suitable for the modern age of agentic software development.

**Please go to [www.lunamux.dev](https://www.lunamux.dev) for much more information about the features, look and behaviour!** The website also has a comprehensive manual.

## Introduction
The project comprises a Mac terminal app hosted in an Electron shell with flexible tab and window management and an optional,
experimental sci-fi-like 3D mode. It has a built-in server which hosts the terminal sessions (which outlive
the UI). Terminal scrollback is persisted to a local database in case the server goes down for some reason
(e.g. computer restart). The app has agent-awareness, for example to illustrate what sessions are working or
waiting for an answer.

It's also possible to connect to a remote terminal through the web and get the same
look and experience. It furthermore has companion mobile apps for Android and iOS on App Store and Google Play (you can find links on the [website](https://www.lunamus.dev)).

Note that there is no cloud component - remote access requires being on the same network or VPN.

If you want information about installation / deployment, usage or features, check out the comprehensive [manual](https://www.lunamux.dev/#/docs).

## Architecture

I use Kotlin for this project because I really like the language, and Kotlin Multiplatform makes it easy to share code across components. I however do **not** use Compose Multiplatform because I generally want platforms to have a native UI (which is not to say that there are no situations where that might be appropriate -- decide on a case-by-case basis, not religiously). For this project, I have made an exception for the Mac app, choosing to go with an embedded web app inside Electron, using Kotlin's DOM APIs.

In projects on multiple platforms, I try to have common view models across all clients that expose a single state object per screen/view, with thin
wrappers where needed on each platform. I also re-use the Kotlin networking and serialisation layer across platforms and components wherever possible. If you want to know more about how I'm thinking about architecture in a Kotlin Multiplatform world, you can check out [my KMP principles](https://www.soderbjorn.se/#/kmp-sharing-architecture).

In addition, I provide a dedicated UI toolkit called [Lunula](https://github.com/soderbjorn/lunula) which I use for this app and other personal apps as well so that they all gain the same look and feel, customisable theming and flexible tab and window layouts.

I have deliberately not manually documented anything more than the above guidance and the [manual](https://www.lunamux.dev/#/docs) itself, although I instruct my agents to write code comments in-line. This is a fast-moving, agent-first software development project. If I put too much detail here, it would really quickly become obsolete. If you want more implementation details, ask your agent! :)

### Dependencies

| Component | Library | Purpose |
|-----------|---------|---------|
| Web | [Lunula](https://github.com/soderbjorn/lunula) | UI toolkit
| Web | [three.js](https://threejs.org/) | 3D rendering |
| Server | [pty4j](https://github.com/JetBrains/pty4j) | Shell process management |
| Server | [JediTerm](https://github.com/JetBrains/jediterm) | Headless terminal emulation |
| Web | [xterm.js](https://xtermjs.org/) | Terminal rendering |
| Android | [Termux](https://termux.dev/) | Terminal emulation |
| iOS | [SwiftTerm](https://github.com/migueldeicaza/SwiftTerm) | Terminal emulation |

An assortment of other more conventional dependencies are also used.

## Author

[Robert Söderbjörn](https://www.soderbjorn.se) is the creator and maintainer of this project. If you would like to contribute, you are more than welcome! You can reach out at lunamux@soderbjorn.se. 

## Development

We use the [Lunicle issue tracker](https://issues.lunicle.dev/?projectId=1) for managing development - it's one of my sister projects (it's also embedded on the Lunamux website [here](https://lunamux.dev/#/issues)). You can see all issues without signing in. Contact me if you would like edit rights to the board so that you can create, move and comment on tickets and to add pull requests on GitHub. Before embarking on huge re-work (rather than bug fixes or small features), you might want to talk to me first. I'm very open to significant changes as well, I just want us to agree on the UX and make sure it's done in a way that fits the vision and all platforms.

## License

Lunamux is released under the [MIT License](LICENSE).

Third-party dependencies are used under their respective licenses.
