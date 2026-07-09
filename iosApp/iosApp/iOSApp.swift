import SwiftUI

@main
struct LunamuxApp: App {
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootNavigationView()
        }
        .onChange(of: scenePhase) { _, phase in
            // Re-validate the `/window` connection whenever the app returns
            // to the foreground. iOS kills TCP connections while the app is
            // suspended without surfacing an error — the socket's read loop
            // just hangs, so the tree and state badges silently stop
            // updating. `reconnectIfStale` only kicks the connection when
            // it has actually been quiet longer than the server's ~3 s
            // state-poll cadence allows, so quick app switches are free.
            if phase == .active {
                ConnectionHolder.shared.windowSocket?.reconnectIfStale(maxQuietMillis: 8000)
            }
        }
    }
}
