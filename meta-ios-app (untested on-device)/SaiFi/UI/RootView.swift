/* sai-fi — the app shell: the sign-in gate, and the bottom bar behind it. */

// Signed out, the gate is the whole app. The tabs are at the BOTTOM, they carry icons, and they
// exist in every build. Machine autoload lives HERE (the shell stays composed) not in Home.
//
// Ported from Android `ui/ConciergeScreen.kt`.

import SaiFiCore
import SwiftUI

struct RootView: View {
  @Environment(\.saiColors) private var colors
  @Bindable var app: AppModel
  @State private var selected: SaiTab = .home

  var body: some View {
    let s = app.call.state
    Group {
      if !app.signedIn && !s.active {
        SignInView(app: app)
      } else {
        let tab = coerceTab(selected, devMode: app.devMode)
        TabView(selection: Binding(
          get: { tab },
          set: { selected = $0 }
        )) {
          HomeView(app: app)
            .tabItem { Label(SaiTab.home.label, systemImage: tab == .home ? "house.fill" : "house") }
            .tag(SaiTab.home)
          SettingsView(app: app)
            .tabItem { Label(SaiTab.settings.label, systemImage: tab == .settings ? "gearshape.fill" : "gearshape") }
            .tag(SaiTab.settings)
          if app.devMode {
            LogsView(app: app)
              .tabItem { Label(SaiTab.logs.label, systemImage: "list.bullet.rectangle") }
              .tag(SaiTab.logs)
          }
        }
        .tint(colors.green)
        .onChange(of: tab) { _, new in selected = new }
      }
    }
    .background(colors.background.ignoresSafeArea())
    .alert(
      "Let Sai use your location?",
      isPresented: $app.locationRationaleOpen
    ) {
      Button("Continue") { app.onLocationRationale(true) }
      Button("Not now", role: .cancel) { app.onLocationRationale(false) }
    } message: {
      Text(
        "Sai-Fi can use your phone's location so questions like \"what's the weather\" or "
          + "\"what's near me\" just work, without you having to say where you are out loud.\n\n"
          + "It's read only when a question needs it, never streamed or tracked. Everything else "
          + "in the app works the same if you say no.")
    }
    .task(id: app.signedIn) {
      if app.signedIn && app.machines.isEmpty && !app.machinesFetchOk {
        app.loadMachines()
      }
    }
  }
}
