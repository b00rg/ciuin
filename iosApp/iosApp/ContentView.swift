import ComposeApp
import SwiftUI
import UIKit

struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(measurementDevices: MeasurementDevices())
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
