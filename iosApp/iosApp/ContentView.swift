import SwiftUI
import UIKit

struct ContentView: View {
    @State private var app = AppModel()
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    @State private var confirmStop = false
    @State private var sharedProfile: ProfileDocument?
    @State private var copied = false
    @State private var hostCopied = false
    private let proxyHost = "127.0.0.1"

    var body: some View {
        NavigationStack {
            Form {
                Section("Bestdori 卡牌数据") {
                    Text("来源：bestdori.com。已下载的数据会保存在本机，下次打开直接使用，可离线生成档案。")
                        .font(.footnote).foregroundStyle(.secondary)
                    if let cache = app.catalog.cache {
                        LabeledContent("本地缓存", value: "\(cache.cardCount) 张卡牌")
                        LabeledContent("最近更新") {
                            Text(Date(timeIntervalSince1970: Double(cache.updatedAtMillis) / 1000), format: .dateTime)
                        }
                    } else {
                        Text("请先下载 Bestdori 卡牌数据，再启动代理")
                    }
                    if app.catalog.loading { HStack { ProgressView(); Text("正在获取卡牌数据") } }
                    if let error = app.catalog.error { errorText(error) }
                    if app.catalog.upToDate { Text("已是最新数据").foregroundStyle(.secondary) }
                    Button(app.catalog.cache == nil ? "下载数据" : "刷新数据") {
                        Task { await app.catalog.refresh() }
                    }
                    .disabled(app.catalog.loading)
                }
                Section("本地证书") {
                    LabeledContent("CA 状态", value: app.certificates.state.title)
                    Button("安装描述文件", action: app.certificates.installProfile)
                        .disabled(!app.certificates.canInstall)
                    if case .failed(let message) = app.certificates.state { errorText(message) }
                    if let error = app.certificates.installationError { errorText(error) }
                }
                Section("本地代理") {
                    Text(app.capture.status)
                    LabeledContent("主机", value: proxyHost).textSelection(.enabled)
                    LabeledContent("端口", value: "8888").textSelection(.enabled)
                    Button(hostCopied ? "主机地址已复制" : "复制主机地址") {
                        UIPasteboard.general.string = proxyHost
                        hostCopied = true
                    }
                    Button("打开设置") {
                        openURL(URL(string: UIApplication.openSettingsURLString)!)
                    }
                    Button(app.capture.phase == .stopped ? "启动代理" : "停止代理") {
                        if app.capture.phase == .stopped { Task { await app.startCapture() } }
                        else { confirmStop = true }
                    }
                    .disabled(app.capture.phase == .starting || app.capture.phase == .stopping ||
                              (app.capture.phase == .stopped &&
                               (app.certificates.trustedAuthority == nil || app.catalog.cache == nil)))
                    if let error = app.capture.error { errorText(error) }
                }
                Section("Bestdori 档案") {
                    switch app.profiles.state {
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
                    case .failed(let message):
                        errorText(message)
                        Button("重新解析", action: app.profiles.retry)
                    case .empty: Text("暂无档案").foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Garupa Export")
        }
        .task { await app.activate() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                hostCopied = false
                Task { await app.activate() }
            }
        }
        .onChange(of: profileID) { _, _ in copied = false }
        .alert("已关闭系统代理？", isPresented: $confirmStop) {
            Button("停止代理", role: .destructive) { Task { await app.capture.stop() } }
            Button("取消", role: .cancel) {}
        }
        .sheet(item: $sharedProfile) { ShareSheet(url: $0.url) }
    }

    private var profileID: UUID? {
        if case .ready(let profile) = app.profiles.state { return profile.id }
        return nil
    }

    private func errorText(_ message: String) -> some View {
        Text(message).foregroundStyle(.red).textSelection(.enabled)
    }
}
