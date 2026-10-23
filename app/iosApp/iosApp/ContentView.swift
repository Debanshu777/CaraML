import UIKit
import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
#if CARAML_BENCHMARK
        if let specification = ProcessInfo.processInfo.environment["CARAML_BENCHMARK"] {
            Text("CaraML model validation is running. Keep this app open.")
                .padding()
                .onAppear {
                    UIApplication.shared.isIdleTimerDisabled = true
                    AppleBenchmarkKt.startAppleBenchmark(specification: specification) { success in
                        exit(success.boolValue ? 0 : 1)
                    }
                }
        } else {
            ComposeView().ignoresSafeArea()
        }
#else
        ComposeView()
            .ignoresSafeArea()
#endif
    }
}



