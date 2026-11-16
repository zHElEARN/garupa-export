import SwiftUI
import UIKit

struct ContentView: View {
    @State private var app = AppModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var confirmStop = false
    @State private var sharedProfile: ProfileDocument?
    @State private var copied = false

    var body: some View {
        NavigationStack {
            Form {
                Section("本地证书") {
                    LabeledContent("CA 状态", value: app.certificates.state.title)
                    Button("安装描述文件", action: app.certificates.installProfile)
                        .disabled(!app.certificates.canInstall)
                    if case .failed(let message) = app.certificates.state { errorText(message) }
                    if let error = app.certificates.installationError { errorText(error) }
                }
                Section("本地代理") {
                    Text(app.capture.status)
                    Text("127.0.0.1:8888").textSelection(.enabled)
                    Button(app.capture.phase == .stopped ? "启动代理" : "停止代理") {
                        if app.capture.phase == .stopped { Task { await app.startCapture() } }
                        else { confirmStop = true }
                    }
                    .disabled(app.capture.phase == .starting || app.capture.phase == .stopping ||
                              (app.capture.phase == .stopped && app.certificates.trustedAuthority == nil))
                    if let error = app.capture.error { errorText(error) }
                }
                Section("Bestdori 档案") {
                    switch app.capture.exportState {
                    case .parsing:
                        HStack { ProgressView(); Text("正在生成档案") }
                    case .ready(let profile):
                        Text(profile.name).font(.headline)
                        Text("卡牌 \(profile.cardCount) · 区域道具 \(profile.areaItemCount) · 潜能 \(profile.potentialCount)")
                        Button(copied ? "已复制档案" : "复制档案") {
                            UIPasteboard.general.string = profile.json
                            copied = true
                        }
                        Button("导出档案") { sharedProfile = profile }
                        ForEach(profile.warnings, id: \.self) { Text($0).font(.footnote) }
                    case .failed(let message): errorText(message)
                    case .empty: Text("暂无档案").foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Garupa Export")
        }
        .task { await app.activate() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await app.activate() } }
        }
        .onChange(of: profileID) { _, _ in copied = false }
        .alert("已关闭系统代理？", isPresented: $confirmStop) {
            Button("停止代理", role: .destructive) { Task { await app.capture.stop() } }
            Button("取消", role: .cancel) {}
        }
        .sheet(item: $sharedProfile) { ShareSheet(url: $0.url) }
    }

    private var profileID: UUID? {
        if case .ready(let profile) = app.capture.exportState { return profile.id }
        return nil
    }

    private func errorText(_ message: String) -> some View {
        Text(message).foregroundStyle(.red).textSelection(.enabled)
    }
}
