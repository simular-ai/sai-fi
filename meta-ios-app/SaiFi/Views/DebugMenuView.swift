/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

//
// DebugMenuView.swift
//
// Debug-only overlay that provides access to mock device functionality during development.
// This view demonstrates how to integrate mock devices for testing DAT SDK features
// without requiring physical Meta wearable devices.
//

#if DEBUG

import SwiftUI

struct DebugMenuView: View {
  @Bindable var debugMenuViewModel: DebugMenuViewModel

  var body: some View {
    HStack {
      Spacer()
      VStack(spacing: 12) {
        Spacer()
        Button {
          debugMenuViewModel.showHfpSpike = true
        } label: {
          Image(systemName: "waveform.circle.fill")
            .foregroundStyle(.white)
            .padding()
            .background(.secondary)
            .clipShape(Circle())
            .shadow(radius: 4)
        }
        .accessibilityIdentifier("hfp_spike_button")

        Button {
          debugMenuViewModel.showLiveHarness = true
        } label: {
          Image(systemName: "antenna.radiowaves.left.and.right.circle.fill")
            .foregroundStyle(.white)
            .padding()
            .background(.secondary)
            .clipShape(Circle())
            .shadow(radius: 4)
        }
        .accessibilityIdentifier("live_harness_button")

        Button(action: {
          debugMenuViewModel.showDebugMenu = true
        }) {
          Image(systemName: "ladybug.fill")
            .foregroundStyle(.white)
            .padding()
            .background(.secondary)
            .clipShape(Circle())
            .shadow(radius: 4)
        }.accessibilityIdentifier("debug_menu_button")
        Spacer()
      }
      .padding(.trailing)
    }
    .sheet(isPresented: $debugMenuViewModel.showHfpSpike) {
      HfpSpikeView()
    }
    .sheet(isPresented: $debugMenuViewModel.showLiveHarness) {
      LiveHarnessView()
    }
  }
}

#endif
