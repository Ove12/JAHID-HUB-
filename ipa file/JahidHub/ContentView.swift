//
//  ContentView.swift
//  JahidHub
//

import SwiftUI
import WebKit

struct ContentView: View {
    @State private var selectedTab = 0
    
    var body: some View {
        TabView(selection: $selectedTab) {
            HomeView()
                .tabItem {
                    Label("Home", systemImage: "play.rectangle.fill")
                }
                .tag(0)
            
            SearchView()
                .tabItem {
                    Label("Search", systemImage: "magnifyingglass")
                }
                .tag(1)
            
            LibraryView()
                .tabItem {
                    Label("Library", systemImage: "film.stack")
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
    @State private var showPortal = false
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    // Hero Banner
                    ZStack(alignment: .bottomLeading) {
                        LinearGradient(
                            gradient: Gradient(colors: [Color.red.opacity(0.85), Color.black]),
                            startPoint: .topTrailing,
                            endPoint: .bottomLeading
                        )
                        .frame(height: 230)
                        .cornerRadius(16)
                        
                        VStack(alignment: .leading, spacing: 8) {
                            Text("JAHID HUB")
                                .font(.system(size: 32, weight: .black, design: .rounded))
                                .foregroundColor(.white)
                            
                            Text("Fast, ad-free streaming hub for iOS")
                                .font(.subheadline)
                                .foregroundColor(.white.opacity(0.85))
                            
                            Button(action: {
                                showPortal = true
                            }) {
                                HStack {
                                    Image(systemName: "play.fill")
                                    Text("Open Portal")
                                        .fontWeight(.bold)
                                }
                                .padding(.horizontal, 20)
                                .padding(.vertical, 10)
                                .background(Color.white)
                                .foregroundColor(.black)
                                .cornerRadius(25)
                            }
                            .padding(.top, 4)
                        }
                        .padding(20)
                    }
                    .padding(.horizontal)
                    
                    // Categories
                    Text("Featured Categories")
                        .font(.title2)
                        .fontWeight(.bold)
                        .padding(.horizontal)
                    
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 14) {
                        CategoryCard(title: "Movies", icon: "popcorn.fill", color: .purple)
                        CategoryCard(title: "TV Shows", icon: "tv.fill", color: .blue)
                        CategoryCard(title: "Anime Vault", icon: "sparkles", color: .orange)
                        CategoryCard(title: "Live Channels", icon: "antenna.radiowaves.left.and.right", color: .green)
                    }
                    .padding(.horizontal)
                }
                .padding(.vertical)
            }
            .navigationTitle("JAHID HUB")
            .sheet(isPresented: $showPortal) {
                WebViewContainer(url: URL(string: "https://joyflix-api.jehadjoy69.workers.dev/repo.json")!)
            }
        }
    }
}

struct CategoryCard: View {
    let title: String
    let icon: String
    let color: Color
    
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 28))
                .foregroundColor(.white)
            Text(title)
                .font(.headline)
                .foregroundColor(.white)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 100)
        .background(color.opacity(0.35))
        .background(Color(UIColor.secondarySystemBackground))
        .cornerRadius(14)
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .stroke(color.opacity(0.5), lineWidth: 1)
        )
    }
}

// MARK: - Search View
struct SearchView: View {
    @State private var query = ""
    
    var body: some View {
        NavigationView {
            VStack {
                TextField("Search movies, shows, anime...", text: $query)
                    .padding(12)
                    .background(Color(UIColor.secondarySystemBackground))
                    .cornerRadius(10)
                    .padding(.horizontal)
                
                Spacer()
                
                Image(systemName: "magnifyingglass.circle")
                    .font(.system(size: 64))
                    .foregroundColor(.gray)
                Text("Search thousands of titles")
                    .foregroundColor(.gray)
                    .padding(.top, 8)
                
                Spacer()
            }
            .navigationTitle("Search")
        }
    }
}

// MARK: - Library View
struct LibraryView: View {
    var body: some View {
        NavigationView {
            VStack {
                Image(systemName: "film")
                    .font(.system(size: 64))
                    .foregroundColor(.gray)
                Text("Your Library is empty")
                    .font(.headline)
                    .foregroundColor(.gray)
                    .padding(.top, 8)
                Text("Bookmarked titles will appear here.")
                    .font(.subheadline)
                    .foregroundColor(.gray.opacity(0.7))
            }
            .navigationTitle("Library")
        }
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
                        Text("Build Engine")
                        Spacer()
                        Text("Codemagic iOS (SwiftUI)").foregroundColor(.gray)
                    }
                }
            }
            .navigationTitle("Settings")
        }
    }
}

// MARK: - WebView Container
struct WebViewContainer: UIViewRepresentable {
    let url: URL
    
    func makeUIView(context: Context) -> WKWebView {
        let prefs = WKWebpagePreferences()
        prefs.allowsContentJavaScript = true
        let config = WKWebViewConfiguration()
        config.defaultWebpagePreferences = prefs
        return WKWebView(frame: .zero, configuration: config)
    }
    
    func updateUIView(_ uiView: WKWebView, context: Context) {
        let request = URLRequest(url: url)
        uiView.load(request)
    }
}
