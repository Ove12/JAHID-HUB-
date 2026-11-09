# JAHID HUB - iOS Project (.ipa)

This folder contains the complete, ready-to-build iOS project for **JAHID HUB**, designed to compile into an `.ipa` file using **Codemagic**.

---

### How to Build Your .ipa on Codemagic:

1. **Push this repository to GitHub** (or connect your repo).
2. Sign in to **[Codemagic.io](https://codemagic.io)** with your GitHub account.
3. Click **"Add application"** and select this repository.
4. Select **"iOS App"** and choose **codemagic.yaml**.
5. Click **"Start new build"**!
6. Codemagic will launch an Apple Silicon macOS runner, compile the project, and export **`JahidHub-v1.0.0.ipa`**.

---

### How to Install the .ipa on Your iPhone / iPad:

Once downloaded from Codemagic artifacts, you can install the `.ipa` using any of these free sideloading tools:
- **Sideloadly** (Windows / Mac) -> Drag and drop the `.ipa`, type your Apple ID, click Start.
- **AltStore** -> Send to device and install.
- **Scarlet / TrollStore / Esign** -> Direct device installer.
