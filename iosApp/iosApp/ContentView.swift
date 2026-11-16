import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ContentView: View {
    @State private var app = AppModel()
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    @State private var confirmStop = false
    @State private var copied = false
    @State private var saved = false
    @State private var hostCopied = false
    @State private var profileToSave: ProfileDocument?
    @State private var saving = false
    @State private var saveError: String?
    private let proxyHost = "127.0.0.1"

    var body: some View {
        NavigationStack {
            Form {
                catalogSection
                certificateSection
                proxySection
                profileSection
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
        .onChange(of: profileID) { _, _ in
            copied = false
            saved = false
        }
    }

    private var catalogSection: some View {
        Section("Bestdori 卡牌数据") {
            LabeledContent("状态") {
                HStack {
                    if app.catalog.loading { ProgressView() }
                    Text(catalogStatus)
                }
            }
            if let cache = app.catalog.cache {
                LabeledContent("本地缓存", value: "\(cache.cardCount) 张卡牌")
                LabeledContent("最近更新") {
                    Text(Date(timeIntervalSince1970: Double(cache.updatedAtMillis) / 1000),
                         format: .dateTime.year().month().day().hour().minute())
                }
            }
            if let error = app.catalog.error { errorText(error) }
            Button(app.catalog.cache == nil ? "下载数据" : "刷新数据") {
                Task { await app.catalog.refresh() }
            }
            .disabled(app.catalog.loading)
        }
    }

    private var catalogStatus: String {
        if app.catalog.loading { return app.catalog.cache == nil ? "正在下载" : "正在刷新" }
        if app.catalog.upToDate { return "已是最新数据" }
        return app.catalog.cache == nil ? "未下载" : "已缓存"
    }

    private var certificateSection: some View {
        Section("本地证书") {
            LabeledContent("CA 状态", value: app.certificates.state.title)
            Button("安装描述文件", action: app.certificates.installProfile)
                .disabled(!app.certificates.canInstall)
            if case .failed(let message) = app.certificates.state { errorText(message) }
            if let error = app.certificates.installationError { errorText(error) }
        }
    }

    private var proxySection: some View {
        Section("本地代理") {
            LabeledContent("状态", value: app.capture.status)
            LabeledContent("主机", value: proxyHost).textSelection(.enabled)
            LabeledContent("端口", value: "8888").textSelection(.enabled)
            Button {
                UIPasteboard.general.string = proxyHost
                hostCopied = true
            } label: {
                Label(hostCopied ? "主机地址已复制" : "复制主机地址",
                      systemImage: hostCopied ? "checkmark" : "doc.on.doc")
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
        .alert("已关闭系统代理？", isPresented: $confirmStop) {
            Button("停止代理", role: .destructive) { Task { await app.capture.stop() } }
            Button("取消", role: .cancel) {}
        }
    }

    private var profileSection: some View {
        Section("Bestdori 档案") {
            switch app.profiles.state {
            case .parsing:
                LabeledContent("状态") { HStack { ProgressView(); Text("正在生成档案") } }
            case .ready(let profile):
                LabeledContent("状态", value: "档案已生成")
                VStack(alignment: .leading, spacing: 4) {
                    Text(profile.name).font(.headline)
                    Text("卡牌 \(profile.cardCount) · 区域道具 \(profile.areaItemCount) · 潜能 \(profile.potentialCount)")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                .textSelection(.enabled)
                if !profile.warnings.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        Label("转换提示（\(profile.warnings.count)）", systemImage: "exclamationmark.triangle")
                            .font(.subheadline.weight(.semibold))
                        ForEach(profile.warnings.indices, id: \.self) { index in
                            Text(profile.warnings[index]).font(.subheadline).textSelection(.enabled)
                        }
                    }
                }
                Button {
                    UIPasteboard.general.string = profile.json
                    copied = true
                } label: {
                    Label(copied ? "已复制档案" : "复制档案", systemImage: copied ? "checkmark" : "doc.on.doc")
                }
                Button {
                    profileToSave = profile
                    saving = true
                } label: {
                    Label(saved ? "已保存文件" : "保存文件", systemImage: saved ? "checkmark" : "square.and.arrow.down")
                }
                ShareLink(item: profile.url) { Label("分享", systemImage: "square.and.arrow.up") }
            case .failed(let message):
                LabeledContent("状态", value: "生成失败")
                errorText(message)
                Button("重新解析", action: app.profiles.retry)
            case .empty:
                LabeledContent("状态", value: "暂无档案")
            }
        }
        .fileExporter(isPresented: $saving,
                      document: profileToSave.map { ProfileFile(json: $0.json) },
                      contentType: .json,
                      defaultFilename: profileToSave?.url.deletingPathExtension().lastPathComponent ?? "bestdori") { result in
            switch result {
            case .success:
                // A new capture may have started while the system picker was open.
                if profileToSave?.id == profileID { saved = true }
            case .failure(let error):
                if (error as? CocoaError)?.code != .userCancelled { saveError = error.localizedDescription }
            }
        }
        .alert("保存失败", isPresented: Binding(
            get: { saveError != nil }, set: { if !$0 { saveError = nil } }
        )) {
            Button("好", role: .cancel) {}
        } message: {
            Text(saveError ?? "")
        }
    }

    private var profileID: UUID? {
        if case .ready(let profile) = app.profiles.state { return profile.id }
        return nil
    }

    private func errorText(_ message: String) -> some View {
        Text(message).foregroundStyle(.red).textSelection(.enabled)
    }
}
