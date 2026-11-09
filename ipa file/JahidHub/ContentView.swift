//
//  ContentView.swift
//  JahidHub
//

import SwiftUI
import WebKit

// MARK: - Models

struct CatalogResponse: Codable {
    let metas: [MediaItem]?
}

struct MetaDetailResponse: Codable {
    let meta: MediaDetail?
}

struct MediaItem: Identifiable, Codable, Hashable {
    let id: String
    let name: String
    let type: String
    let poster: String?
    let year: String?
    let imdb_id: String?
    let description: String?
    let genres: [String]?
    
    var imdbId: String {
        return imdb_id ?? id
    }
    
    var posterURL: URL? {
        guard let p = poster, !p.isEmpty else { return nil }
        return URL(string: p)
    }
}

struct MediaDetail: Codable {
    let id: String
    let name: String
    let type: String
    let poster: String?
    let background: String?
    let year: String?
    let imdbRating: String?
    let description: String?
    let genres: [String]?
    let videos: [EpisodeItem]?
}

struct EpisodeItem: Identifiable, Codable, Hashable {
    let id: String
    let name: String?
    let season: Int
    let episode: Int
    let overview: String?
    let thumbnail: String?
    
    var displayTitle: String {
        if let n = name, !n.isEmpty {
            return "E\(episode): \(n)"
        }
        return "Episode \(episode)"
    }
}

enum StreamServer: String, CaseIterable, Identifiable {
    case vidlink = "VidLink HD"
    case autoembed = "AutoEmbed"
    case vidsrc = "VidSrc Pro"
    case superembed = "SuperEmbed"
    
    var id: String { rawValue }
    
    func streamURL(imdbId: String, season: Int = 1, episode: Int = 1, isSeries: Bool = false) -> URL? {
        let cleanId = imdbId.trimmingCharacters(in: .whitespacesAndNewlines)
        var str = ""
        switch self {
        case .vidlink:
            if isSeries {
                str = "https://vidlink.pro/tv/\(cleanId)/\(season)/\(episode)"
            } else {
                str = "https://vidlink.pro/movie/\(cleanId)"
            }
        case .autoembed:
            if isSeries {
                str = "https://player.autoembed.cc/embed/tv/\(cleanId)/\(season)/\(episode)"
            } else {
                str = "https://player.autoembed.cc/embed/movie/\(cleanId)"
            }
        case .vidsrc:
            if isSeries {
                str = "https://vidsrc.to/embed/tv/\(cleanId)/\(season)/\(episode)"
            } else {
                str = "https://vidsrc.to/embed/movie/\(cleanId)"
            }
        case .superembed:
            if isSeries {
                str = "https://multiembed.mov/?video_id=\(cleanId)&s=\(season)&e=\(episode)"
            } else {
                str = "https://multiembed.mov/?video_id=\(cleanId)"
            }
        }
        return URL(string: str)
    }
}

// MARK: - Media Store
class MediaStore: ObservableObject {
    @Published var trendingMovies: [MediaItem] = []
    @Published var trendingSeries: [MediaItem] = []
    @Published var searchResults: [MediaItem] = []
    @Published var watchlist: [MediaItem] = []
    @Published var isLoadingHome = false
    @Published var isSearching = false
    
    private let watchlistKey = "jahid_hub_watchlist_key"
    
    init() {
        loadWatchlist()
        fetchHomeData()
    }
    
    func fetchHomeData() {
        isLoadingHome = true
        
        let moviesURL = URL(string: "https://v3-cinemeta.strem.io/catalog/movie/top.json")!
        let seriesURL = URL(string: "https://v3-cinemeta.strem.io/catalog/series/top.json")!
        
        URLSession.shared.dataTask(with: moviesURL) { [weak self] data, _, _ in
            if let data = data, let res = try? JSONDecoder().decode(CatalogResponse.self, from: data) {
                DispatchQueue.main.async {
                    self?.trendingMovies = res.metas ?? []
                    self?.isLoadingHome = false
                }
            }
        }.resume()
        
        URLSession.shared.dataTask(with: seriesURL) { [weak self] data, _, _ in
            if let data = data, let res = try? JSONDecoder().decode(CatalogResponse.self, from: data) {
                DispatchQueue.main.async {
                    self?.trendingSeries = res.metas ?? []
                }
            }
        }.resume()
    }
    
    func search(query: String) {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty, let encoded = q.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
            searchResults = []
            return
        }
        
        isSearching = true
        let movieSearchURL = URL(string: "https://v3-cinemeta.strem.io/catalog/movie/top/search=\(encoded).json")!
        let seriesSearchURL = URL(string: "https://v3-cinemeta.strem.io/catalog/series/top/search=\(encoded).json")!
        
        let group = DispatchGroup()
        var combined: [MediaItem] = []
        
        group.enter()
        URLSession.shared.dataTask(with: movieSearchURL) { data, _, _ in
            defer { group.leave() }
            if let data = data, let res = try? JSONDecoder().decode(CatalogResponse.self, from: data) {
                if let metas = res.metas { combined.append(contentsOf: metas) }
            }
        }.resume()
        
        group.enter()
        URLSession.shared.dataTask(with: seriesSearchURL) { data, _, _ in
            defer { group.leave() }
            if let data = data, let res = try? JSONDecoder().decode(CatalogResponse.self, from: data) {
                if let metas = res.metas { combined.append(contentsOf: metas) }
            }
        }.resume()
        
        group.notify(queue: .main) { [weak self] in
            self?.searchResults = combined
            self?.isSearching = false
        }
    }
    
    func toggleWatchlist(item: MediaItem) {
        if isWatchlisted(item: item) {
            watchlist.removeAll { $0.id == item.id }
        } else {
            watchlist.append(item)
        }
        saveWatchlist()
    }
    
    func isWatchlisted(item: MediaItem) -> Bool {
        return watchlist.contains { $0.id == item.id }
    }
    
    private func saveWatchlist() {
        if let encoded = try? JSONEncoder().encode(watchlist) {
            UserDefaults.standard.set(encoded, forKey: watchlistKey)
        }
    }
    
    private func loadWatchlist() {
        if let data = UserDefaults.standard.data(forKey: watchlistKey),
           let list = try? JSONDecoder().decode([MediaItem].self, from: data) {
            self.watchlist = list
        }
    }
}

// MARK: - Main Tab ContentView
struct ContentView: View {
    @StateObject private var store = MediaStore()
    @State private var selectedTab = 0
    
    var body: some View {
        TabView(selection: $selectedTab) {
            HomeView()
                .environmentObject(store)
                .tabItem {
                    Label("Home", systemImage: "play.tv.fill")
                }
                .tag(0)
            
            SearchView()
                .environmentObject(store)
                .tabItem {
                    Label("Search", systemImage: "magnifyingglass")
                }
                .tag(1)
            
            WatchlistView()
                .environmentObject(store)
                .tabItem {
                    Label("My List", systemImage: "bookmark.fill")
                }
                .tag(2)
            
            SettingsView()
                .tabItem {
                    Label("Settings", systemImage: "gearshape.fill")
                }
                .tag(3)
        }
        .accentColor(.red)
    }
}

// MARK: - Home View
struct HomeView: View {
    @EnvironmentObject var store: MediaStore
    @State private var selectedMedia: MediaItem?
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    // Featured Hero Banner
                    if let hero = store.trendingMovies.first {
                        HeroBannerView(item: hero) {
                            selectedMedia = hero
                        }
                    }
                    
                    // Trending Movies Row
                    MediaRowSection(title: "🔥 Trending Movies", items: store.trendingMovies) { item in
                        selectedMedia = item
                    }
                    
                    // Popular TV Series Row
                    MediaRowSection(title: "📺 Popular TV Shows", items: store.trendingSeries) { item in
                        selectedMedia = item
                    }
                }
                .padding(.bottom, 24)
            }
            .navigationTitle("JAHID HUB")
            .sheet(item: $selectedMedia) { item in
                DetailView(item: item)
                    .environmentObject(store)
            }
        }
    }
}

// MARK: - Hero Banner View
struct HeroBannerView: View {
    let item: MediaItem
    let onPlay: () -> Void
    
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            AsyncImage(url: item.posterURL) { phase in
                switch phase {
                case .success(let image):
                    image
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                default:
                    LinearGradient(
                        gradient: Gradient(colors: [Color.red.opacity(0.8), Color.black]),
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                }
            }
            .frame(height: 380)
            .clipped()
            .overlay(
                LinearGradient(
                    gradient: Gradient(colors: [Color.clear, Color.black.opacity(0.4), Color.black]),
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
            
            VStack(alignment: .leading, spacing: 10) {
                Text(item.name)
                    .font(.system(size: 28, weight: .black, design: .rounded))
                    .foregroundColor(.white)
                    .lineLimit(2)
                    .shadow(radius: 4)
                
                if let y = item.year {
                    Text(y)
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .foregroundColor(.white.opacity(0.85))
                }
                
                Button(action: onPlay) {
                    HStack(spacing: 8) {
                        Image(systemName: "play.fill")
                        Text("Watch Now")
                            .fontWeight(.bold)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Color.red)
                    .foregroundColor(.white)
                    .cornerRadius(10)
                    .shadow(color: Color.red.opacity(0.5), radius: 8, x: 0, y: 4)
                }
                .padding(.top, 4)
            }
            .padding(18)
        }
        .cornerRadius(18)
        .padding(.horizontal)
    }
}

// MARK: - Horizontal Media Row
struct MediaRowSection: View {
    let title: String
    let items: [MediaItem]
    let onSelect: (MediaItem) -> Void
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.title2)
                .fontWeight(.bold)
                .foregroundColor(.white)
                .padding(.horizontal)
            
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 14) {
                    ForEach(items) { item in
                        MediaPosterCard(item: item)
                            .onTapGesture {
                                onSelect(item)
                            }
                    }
                }
                .padding(.horizontal)
            }
        }
    }
}

// MARK: - Poster Card
struct MediaPosterCard: View {
    let item: MediaItem
    
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            AsyncImage(url: item.posterURL) { phase in
                switch phase {
                case .success(let image):
                    image
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                default:
                    ZStack {
                        Color(UIColor.secondarySystemBackground)
                        Image(systemName: "film")
                            .font(.system(size: 28))
                            .foregroundColor(.gray)
                    }
                }
            }
            .frame(width: 125, height: 185)
            .cornerRadius(12)
            .shadow(color: Color.black.opacity(0.3), radius: 4, x: 0, y: 2)
            
            Text(item.name)
                .font(.caption)
                .fontWeight(.semibold)
                .foregroundColor(.white)
                .lineLimit(1)
                .frame(width: 125, alignment: .leading)
            
            if let y = item.year {
                Text(y)
                    .font(.caption2)
                    .foregroundColor(.gray)
            }
        }
    }
}

// MARK: - Search View
struct SearchView: View {
    @EnvironmentObject var store: MediaStore
    @State private var query = ""
    @State private var selectedMedia: MediaItem?
    
    let columns = [
        GridItem(.flexible(), spacing: 12),
        GridItem(.flexible(), spacing: 12),
        GridItem(.flexible(), spacing: 12)
    ]
    
    var body: some View {
        NavigationView {
            VStack {
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(.gray)
                    TextField("Search movies & TV shows...", text: $query)
                        .foregroundColor(.white)
                        .onChange(of: query) { newVal in
                            store.search(query: newVal)
                        }
                    if !query.isEmpty {
                        Button(action: {
                            query = ""
                            store.search(query: "")
                        }) {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundColor(.gray)
                        }
                    }
                }
                .padding(12)
                .background(Color(UIColor.secondarySystemBackground))
                .cornerRadius(12)
                .padding(.horizontal)
                .padding(.top, 8)
                
                if store.isSearching {
                    Spacer()
                    ProgressView("Searching titles...")
                    Spacer()
                } else if store.searchResults.isEmpty && !query.isEmpty {
                    Spacer()
                    Text("No results found for '\(query)'")
                        .foregroundColor(.gray)
                    Spacer()
                } else if store.searchResults.isEmpty {
                    Spacer()
                    VStack(spacing: 8) {
                        Image(systemName: "magnifyingglass.circle")
                            .font(.system(size: 60))
                            .foregroundColor(.gray.opacity(0.6))
                        Text("Type above to discover movies & shows")
                            .foregroundColor(.gray)
                    }
                    Spacer()
                } else {
                    ScrollView {
                        LazyVGrid(columns: columns, spacing: 14) {
                            ForEach(store.searchResults) { item in
                                MediaPosterCard(item: item)
                                    .onTapGesture {
                                        selectedMedia = item
                                    }
                            }
                        }
                        .padding(.horizontal)
                        .padding(.top, 12)
                    }
                }
            }
            .navigationTitle("Search")
            .sheet(item: $selectedMedia) { item in
                DetailView(item: item)
                    .environmentObject(store)
            }
        }
    }
}

// MARK: - Watchlist View
struct WatchlistView: View {
    @EnvironmentObject var store: MediaStore
    @State private var selectedMedia: MediaItem?
    
    let columns = [
        GridItem(.flexible(), spacing: 12),
        GridItem(.flexible(), spacing: 12),
        GridItem(.flexible(), spacing: 12)
    ]
    
    var body: some View {
        NavigationView {
            Group {
                if store.watchlist.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "bookmark")
                            .font(.system(size: 64))
                            .foregroundColor(.gray.opacity(0.6))
                        Text("Your List is Empty")
                            .font(.title3)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                        Text("Bookmark your favorite movies and shows to watch anytime.")
                            .font(.subheadline)
                            .foregroundColor(.gray)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 32)
                    }
                } else {
                    ScrollView {
                        LazyVGrid(columns: columns, spacing: 14) {
                            ForEach(store.watchlist) { item in
                                MediaPosterCard(item: item)
                                    .onTapGesture {
                                        selectedMedia = item
                                    }
                            }
                        }
                        .padding(.horizontal)
                        .padding(.top, 12)
                    }
                }
            }
            .navigationTitle("My List")
            .sheet(item: $selectedMedia) { item in
                DetailView(item: item)
                    .environmentObject(store)
            }
        }
    }
}

// MARK: - Detail View & Episodes
struct DetailView: View {
    let item: MediaItem
    @EnvironmentObject var store: MediaStore
    @Environment(\.dismiss) var dismiss
    
    @State private var detail: MediaDetail?
    @State private var episodes: [EpisodeItem] = []
    @State private var selectedSeason: Int = 1
    @State private var selectedEpisode: Int = 1
    @State private var selectedServer: StreamServer = .vidlink
    @State private var showPlayer = false
    @State private var activeStreamURL: URL?
    
    var isSeries: Bool {
        return item.type.lowercased() == "series"
    }
    
    var seasons: [Int] {
        let set = Set(episodes.map { $0.season })
        return set.sorted()
    }
    
    var filteredEpisodes: [EpisodeItem] {
        return episodes.filter { $0.season == selectedSeason }
    }
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    // Header Poster
                    ZStack(alignment: .bottomLeading) {
                        AsyncImage(url: item.posterURL) { phase in
                            switch phase {
                            case .success(let image):
                                image
                                    .resizable()
                                    .aspectRatio(contentMode: .fill)
                            default:
                                Color(UIColor.secondarySystemBackground)
                            }
                        }
                        .frame(height: 300)
                        .clipped()
                        .overlay(
                            LinearGradient(
                                gradient: Gradient(colors: [Color.clear, Color.black]),
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                        
                        VStack(alignment: .leading, spacing: 6) {
                            Text(item.name)
                                .font(.title)
                                .fontWeight(.black)
                                .foregroundColor(.white)
                            
                            HStack(spacing: 12) {
                                if let y = item.year {
                                    Text(y).foregroundColor(.gray)
                                }
                                if let r = detail?.imdbRating {
                                    Label(r, systemImage: "star.fill")
                                        .foregroundColor(.yellow)
                                }
                                Text(item.type.uppercased())
                                    .font(.caption2)
                                    .fontWeight(.bold)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(Color.red)
                                    .foregroundColor(.white)
                                    .cornerRadius(4)
                            }
                            .font(.subheadline)
                        }
                        .padding(16)
                    }
                    
                    // Action Buttons (Play & Bookmark)
                    HStack(spacing: 12) {
                        Button(action: {
                            startPlayback(season: selectedSeason, episode: selectedEpisode)
                        }) {
                            HStack {
                                Image(systemName: "play.fill")
                                Text(isSeries ? "Play S\(selectedSeason):E\(selectedEpisode)" : "Play Movie")
                                    .fontWeight(.bold)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .background(Color.red)
                            .foregroundColor(.white)
                            .cornerRadius(12)
                        }
                        
                        Button(action: {
                            store.toggleWatchlist(item: item)
                        }) {
                            Image(systemName: store.isWatchlisted(item: item) ? "bookmark.fill" : "bookmark")
                                .font(.title3)
                                .padding(14)
                                .background(Color(UIColor.secondarySystemBackground))
                                .foregroundColor(.white)
                                .cornerRadius(12)
                        }
                    }
                    .padding(.horizontal)
                    
                    // Server Picker
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Streaming Server")
                            .font(.headline)
                            .foregroundColor(.white)
                        
                        Picker("Server", selection: $selectedServer) {
                            ForEach(StreamServer.allCases) { server in
                                Text(server.rawValue).tag(server)
                            }
                        }
                        .pickerStyle(SegmentedPickerStyle())
                    }
                    .padding(.horizontal)
                    
                    // Synopsis
                    if let desc = detail?.description ?? item.description, !desc.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Overview")
                                .font(.headline)
                                .foregroundColor(.white)
                            Text(desc)
                                .font(.subheadline)
                                .foregroundColor(.gray)
                                .lineSpacing(4)
                        }
                        .padding(.horizontal)
                    }
                    
                    // TV Series Seasons & Episodes
                    if isSeries {
                        VStack(alignment: .leading, spacing: 12) {
                            HStack {
                                Text("Episodes")
                                    .font(.headline)
                                    .foregroundColor(.white)
                                Spacer()
                                if !seasons.isEmpty {
                                    Picker("Season", selection: $selectedSeason) {
                                        ForEach(seasons, id: \.self) { s in
                                            Text("Season \(s)").tag(s)
                                        }
                                    }
                                    .pickerStyle(MenuPickerStyle())
                                }
                            }
                            .padding(.horizontal)
                            
                            LazyVStack(spacing: 10) {
                                ForEach(filteredEpisodes) { ep in
                                    HStack(spacing: 12) {
                                        Image(systemName: "play.circle.fill")
                                            .font(.title2)
                                            .foregroundColor(ep.episode == selectedEpisode && ep.season == selectedSeason ? .red : .gray)
                                        
                                        VStack(alignment: .leading, spacing: 4) {
                                            Text(ep.displayTitle)
                                                .font(.subheadline)
                                                .fontWeight(.semibold)
                                                .foregroundColor(.white)
                                            if let o = ep.overview, !o.isEmpty {
                                                Text(o)
                                                    .font(.caption2)
                                                    .foregroundColor(.gray)
                                                    .lineLimit(2)
                                            }
                                        }
                                        Spacer()
                                    }
                                    .padding(12)
                                    .background(Color(UIColor.secondarySystemBackground))
                                    .cornerRadius(10)
                                    .onTapGesture {
                                        selectedSeason = ep.season
                                        selectedEpisode = ep.episode
                                        startPlayback(season: ep.season, episode: ep.episode)
                                    }
                                }
                            }
                            .padding(.horizontal)
                        }
                    }
                }
            }
            .navigationBarItems(trailing: Button("Close") { dismiss() })
            .onAppear {
                loadDetails()
            }
            .fullScreenCover(isPresented: $showPlayer) {
                if let stream = activeStreamURL {
                    StreamingPlayerModal(url: stream)
                }
            }
        }
    }
    
    private func startPlayback(season: Int, episode: Int) {
        if let u = selectedServer.streamURL(imdbId: item.imdbId, season: season, episode: episode, isSeries: isSeries) {
            activeStreamURL = u
            showPlayer = true
        }
    }
    
    private func loadDetails() {
        let metaURL = URL(string: "https://v3-cinemeta.strem.io/meta/\(item.type)/\(item.imdbId).json")!
        URLSession.shared.dataTask(with: metaURL) { data, _, _ in
            if let data = data, let res = try? JSONDecoder().decode(MetaDetailResponse.self, from: data), let m = res.meta {
                DispatchQueue.main.async {
                    self.detail = m
                    if let v = m.videos {
                        self.episodes = v
                        if let first = v.first {
                            self.selectedSeason = first.season
                            self.selectedEpisode = first.episode
                        }
                    }
                }
            }
        }.resume()
    }
}

// MARK: - Native Video Stream Player Modal
struct StreamingPlayerModal: View {
    let url: URL
    @Environment(\.dismiss) var dismiss
    
    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.edgesIgnoringSafeArea(.all)
            
            InlineVideoPlayer(url: url)
                .edgesIgnoringSafeArea(.all)
            
            Button(action: { dismiss() }) {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: 30))
                    .foregroundColor(.white.opacity(0.85))
                    .padding(20)
            }
        }
    }
}

struct InlineVideoPlayer: UIViewRepresentable {
    let url: URL
    
    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        
        let prefs = WKWebpagePreferences()
        prefs.allowsContentJavaScript = true
        config.defaultWebpagePreferences = prefs
        
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.backgroundColor = .black
        webView.isOpaque = false
        webView.scrollView.isScrollEnabled = false
        return webView
    }
    
    func updateUIView(_ uiView: WKWebView, context: Context) {
        let req = URLRequest(url: url)
        uiView.load(req)
    }
}

// MARK: - Settings View
struct SettingsView: View {
    var body: some View {
        NavigationView {
            List {
                Section(header: Text("App Information")) {
                    HStack {
                        Text("App Name")
                        Spacer()
                        Text("JAHID HUB iOS").foregroundColor(.gray)
                    }
                    HStack {
                        Text("Version")
                        Spacer()
                        Text("1.0.0").foregroundColor(.gray)
                    }
                    HStack {
                        Text("Platform")
                        Spacer()
                        Text("iOS Sideload / Codemagic").foregroundColor(.gray)
                    }
                }
                
                Section(header: Text("Streaming Servers")) {
                    Text("• Server 1: VidLink HD (Fast 1080p)")
                    Text("• Server 2: AutoEmbed (Subtitles & Dual Audio)")
                    Text("• Server 3: VidSrc Pro (Backup Mirrors)")
                    Text("• Server 4: SuperEmbed (Global CDN)")
                }
            }
            .navigationTitle("Settings")
        }
    }
}
